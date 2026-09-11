package io.jrepoflow.agent;

import io.jrepoflow.llm.LlmClient;
import io.jrepoflow.llm.LlmTraceLogger;
import io.jrepoflow.context.ContextProfile;
import io.jrepoflow.context.TokenUsageFormatter;
import io.jrepoflow.history.ConversationLedger;
import io.jrepoflow.memory.AutoCompactionManager;
import io.jrepoflow.memory.MemoryManager;
import io.jrepoflow.prompt.PromptAssembler;
import io.jrepoflow.prompt.PromptContext;
import io.jrepoflow.prompt.PromptMode;
import io.jrepoflow.prompt.ProjectMemoryLoader;
import io.jrepoflow.render.PlainRenderer;
import io.jrepoflow.render.Renderer;
import io.jrepoflow.render.StatusInfo;
import io.jrepoflow.runtime.CancellationContext;
import io.jrepoflow.util.AnsiStyle;
import io.jrepoflow.tool.ToolRegistry;
import io.jrepoflow.tool.ToolRegistry.ToolExecutionResult;
import io.jrepoflow.tool.ToolRegistry.ToolInvocation;
import io.jrepoflow.tool.TurnToolPolicy;
import io.jrepoflow.util.TerminalMarkdownRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Agent 核心类 - 实现 ReAct 循环
 */
public class Agent {
    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    private LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory;
    private final MemoryManager memoryManager;
    private final AutoCompactionManager autoCompactionManager;
    private ConversationLedger conversationLedger = ConversationLedger.disabled();
    private Supplier<String> externalContextSupplier = () -> "";
    private Renderer renderer;
    private boolean returnFinalResponseWhenStreamed;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();

    public Agent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry());
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.conversationHistory = new ArrayList<>();
        this.memoryManager = new MemoryManager(llmClient);
        this.autoCompactionManager = new AutoCompactionManager(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(memoryManager::storeFact);
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));
    }

    /**
     * Attaches the append-only session ledger used by the CLI entry point. Existing
     * in-memory history is not replayed except for the current system prompt, because
     * callers attach the ledger immediately after constructing the Agent.
     */
    public void setConversationLedger(ConversationLedger conversationLedger) {
        ConversationLedger next = conversationLedger == null
                ? ConversationLedger.disabled()
                : conversationLedger;
        if (this.conversationLedger == next) {
            return;
        }
        this.conversationLedger = next;
        if (!conversationHistory.isEmpty()) {
            this.conversationLedger.appendMessage(
                    "react", "agent", "session_attach", conversationHistory.get(0));
        }
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.memoryManager.setLlmClient(llmClient);
        this.autoCompactionManager.setLlmClient(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    public void setReturnFinalResponseWhenStreamed(boolean returnFinalResponseWhenStreamed) {
        this.returnFinalResponseWhenStreamed = returnFinalResponseWhenStreamed;
    }

    /**
     * 获取渲染器；首次调用时如果未设置，懒加载一个 {@link PlainRenderer} 兜底，
     * 保证旧调用方（构造 Agent 后没有 setRenderer 的代码、单测等）行为不变。
     */
    private Renderer renderer() {
        if (renderer == null) {
            renderer = new PlainRenderer();
        }
        return renderer;
    }

    /**
     * 运行 Agent 循环
     */
    public String run(String userInput) {
        return run(userInput, userInput);
    }

    /**
     * Runs with expanded delivery content while deriving tool authority only from
     * the text the user actually submitted.
     */
    public String run(String userInput, String submittedUserInput) {
        log.info("ReAct run started: inputLength={}", userInput == null ? 0 : userInput.length());
        TurnToolPolicy turnToolPolicy = TurnToolPolicy.fromUserInput(submittedUserInput);
        // 检索相关长期记忆，注入到 system prompt
        ContextProfile contextProfile = memoryManager.getContextProfile();
        String memoryContext = memoryManager.buildContextForQuery(userInput, contextProfile.memoryContextTokens());
        updateSystemPromptWithMemory(memoryContext);

        appendConversationMessage(LlmClient.Message.user(userInput), "user_input");
        StringBuilder reasoningTranscript = new StringBuilder();
        StreamRenderer streamRenderer = new StreamRenderer(renderer());

        long startNanos = System.nanoTime();
        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);
        pushStatus(budget, startNanos, "running");

        // 主退出条件 = LLM 自己决定（不再调用工具就返回）；
        // budget 仅在显式 token / 硬轮数预算命中或检测到死循环时兜底；默认不限制轮数。
        while (true) {
            if (CancellationContext.isCancelled()) {
                log.info("ReAct run cancelled before iteration");
                pushStatus(budget, startNanos, "idle");
                return "⏹️ 已取消当前任务。";
            }
            // 调 LLM 前只评估真正发送的 conversationHistory。会话记忆快速路径和完整摘要
            // 回退路径都由同一个协调器管理，不再复制一份影子短期记忆。
            maybeCompactHistory();
            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                return finalizePartialResult(
                        exitReason, budget, startNanos, reasoningTranscript, streamRenderer);
            }

            int iteration = budget.beginIteration();

            try {
                List<LlmClient.Tool> toolDefinitions = llmClient.supportsTools()
                        ? toolRegistry.getToolDefinitions()
                        : null;
                TurnToolPolicy.ToolExposure toolExposure = turnToolPolicy.expose(toolDefinitions);
                logRequestContext("react iteration=" + iteration, toolExposure.definitions());
                streamRenderer.beginThinking();
                // 调用 LLM
                LlmClient.ChatResponse response = llmClient.chat(
                        conversationHistory,
                        toolExposure.definitions(),
                        streamRenderer
                );
                LlmTraceLogger.logReasoning(log, "react iteration=" + iteration, llmClient, response.reasoningContent());
                if (CancellationContext.isCancelled()) {
                    log.info("ReAct run cancelled after LLM response");
                    streamRenderer.finish();
                    pushStatus(budget, startNanos, "idle");
                    return "⏹️ 已取消当前任务。";
                }

                budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

                // 如果有工具调用
                if (response.hasToolCalls()) {
                    appendReasoning(reasoningTranscript, response.reasoningContent());
                    log.info("LLM requested {} tool call(s) in iteration {}", response.toolCalls().size(), iteration);
                    budget.recordToolCalls(response.toolCalls());
                    // 添加助手消息（包含工具调用）
                    appendConversationMessage(LlmClient.Message.assistant(
                            response.reasoningContent(),
                            response.content(),
                            response.toolCalls()
                    ), "llm_response");

                    // 工具输出可能插入正文流，先结束当前 Markdown 缓冲，避免跨轮内容错位。
                    streamRenderer.resetBetweenIterations();
                    renderer().appendToolCalls(
                            turnToolPolicy.visibleToolCalls(response.toolCalls(), toolExposure));

                    List<ToolExecutionResult> toolResults = executeToolCalls(
                            response.toolCalls(), iteration, turnToolPolicy, toolExposure);
                    for (ToolExecutionResult toolResult : toolResults) {
                        appendConversationMessage(
                                LlmClient.Message.tool(toolResult.id(), toolResult.result()),
                                "tool_execution");
                    }
                    pushStatus(budget, startNanos, "running");

                    // 继续循环，让 LLM 根据工具结果继续思考
                    continue;
                }

                // 没有工具调用，直接返回结果
                appendReasoning(reasoningTranscript, response.reasoningContent());
                // Keep the delivery view compatible with providers that do not require
                // final-turn reasoning replay, while the raw ledger preserves the complete response.
                conversationHistory.add(LlmClient.Message.assistant(response.content()));
                conversationLedger.appendMessage(
                        "react",
                        "agent",
                        "llm_response",
                        LlmClient.Message.assistant(
                                response.reasoningContent(),
                                response.content()));

                // 记录 token 使用
                memoryManager.recordTokenUsage(budget.totalInputTokens(), budget.totalOutputTokens(), budget.totalCachedInputTokens());
                pushStatus(budget, startNanos, "idle");
                log.info("ReAct run finished: inputTokens={}, outputTokens={}, reasoningChars={}, answerChars={}",
                        budget.totalInputTokens(),
                        budget.totalOutputTokens(),
                        response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                        response.content() == null ? 0 : response.content().length());
                if (log.isDebugEnabled()) {
                    log.debug("Assistant answer preview: {}", preview(response.content(), 500));
                }

                if (streamRenderer.hasStreamedOutput()) {
                    streamRenderer.finish();
                    return returnFinalResponseWhenStreamed ? (response.content() == null ? "" : response.content().trim()) : "";
                }
                streamRenderer.clearThinkingPanel();
                return formatUserFacingResponse(reasoningTranscript.toString(), response.content());

            } catch (IOException e) {
                log.error("LLM call failed in ReAct loop", e);
                streamRenderer.finish();
                return "❌ 调用 LLM 失败: " + e.getMessage();
            }
        }
    }

    /**
     * 预算安全阀命中后只允许一次无工具模型调用，把已完成工作整理成可交付的部分结果。
     * 这次调用不重新进入 ReAct 循环，也不暴露任何工具。
     */
    private String finalizePartialResult(
            AgentBudget.ExitReason exitReason,
            AgentBudget budget,
            long startNanos,
            StringBuilder reasoningTranscript,
            StreamRenderer streamRenderer) {
        String description = budget.describeExit(exitReason);
        log.warn("ReAct run exhausted budget: reason={}, iteration={}, tokens={}/{}",
                exitReason, budget.iteration(),
                budget.totalInputTokens() + budget.totalOutputTokens(), budget.tokenBudget());

        appendConversationMessage(
                LlmClient.Message.user(budget.finalizationInstruction(exitReason)),
                "budget_finalization");
        renderer().stream().println(AnsiStyle.section("⚠️ 执行预算已触发，正在整理部分结果"));

        try {
            streamRenderer.beginThinking();
            LlmClient.ChatResponse response = llmClient.chat(
                    conversationHistory,
                    List.of(),
                    streamRenderer);
            budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
            appendReasoning(reasoningTranscript, response.reasoningContent());

            String responseContent = response.content() == null ? "" : response.content().trim();
            String partialResult = formatPartialResult(description, responseContent);
            conversationHistory.add(LlmClient.Message.assistant(partialResult));
            conversationLedger.appendMessage(
                    "react",
                    "agent",
                    "budget_finalization_response",
                    LlmClient.Message.assistant(response.reasoningContent(), partialResult));
            memoryManager.recordTokenUsage(
                    budget.totalInputTokens(),
                    budget.totalOutputTokens(),
                    budget.totalCachedInputTokens());
            pushStatus(budget, startNanos, "idle");

            if (streamRenderer.hasStreamedOutput()) {
                streamRenderer.finish();
                return returnFinalResponseWhenStreamed ? partialResult : "";
            }
            streamRenderer.clearThinkingPanel();
            return formatUserFacingResponse(reasoningTranscript.toString(), partialResult);
        } catch (IOException e) {
            log.error("LLM finalization call failed after ReAct budget exhaustion", e);
            streamRenderer.finish();
            pushStatus(budget, startNanos, "idle");
            return formatPartialResult(description, "收尾调用失败：" + e.getMessage());
        }
    }

    private String formatPartialResult(String description, String content) {
        String heading = "⚠️ 部分完成（" + description + "）";
        return content == null || content.isBlank() ? heading : heading + "\n\n" + content;
    }

    /**
     * 清空对话历史并重建基础系统提示，不影响长期记忆条目
     */
    public void clearHistory() {
        autoCompactionManager.clear(conversationHistory);
        conversationLedger.appendEvent(
                "history_clear",
                "react",
                "agent",
                "slash_clear",
                java.util.Map.of("discardedViewMessages", conversationHistory.size()));
        conversationHistory.clear();
        appendConversationMessage(
                LlmClient.Message.system(buildSystemPrompt("")),
                "history_reset");

    }

    /**
     * 手动压缩当前 ReAct 对话历史，不等待上下文窗口阈值触发。
     */
    public CompactionResult compactHistoryNow() {
        long beforeTokens = estimateCurrentContextTokens();
        int beforeMessages = conversationHistory.size();
        try {
            AutoCompactionManager.Result result = autoCompactionManager.compactNow(conversationHistory);
            if (result.compacted()) {
                recordCompaction("manual", beforeMessages, beforeTokens);
            }
            return new CompactionResult(result.compacted(), beforeTokens, estimateCurrentContextTokens(), null);
        } catch (Exception e) {
            log.warn("manual conversationHistory compaction failed", e);
            return new CompactionResult(false, beforeTokens, estimateCurrentContextTokens(), e.getMessage());
        }
    }

    public record CompactionResult(boolean compacted, long beforeTokens, long afterTokens, String error) {
    }

    /** 当前状态栏快照：ctx 表示下一轮请求仍会携带的上下文估算，不含累计 in/out 用量。 */
    public StatusInfo currentStatus(String phase) {
        String normalizedPhase = phase == null || phase.isBlank() ? "idle" : phase;
        String model = llmClient == null ? "—" : llmClient.getModelName();
        long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
        long contextTokens = estimateCurrentContextTokens();
        if ("idle".equals(normalizedPhase)) {
            return StatusInfo.idle(model, contextWindow, contextTokens);
        }
        return StatusInfo.active(model, contextWindow, contextTokens, normalizedPhase);
    }

    /**
     * 将记忆上下文注入到 system prompt 中（替换 conversationHistory[0]）
     */
    private void updateSystemPromptWithMemory(String memoryContext) {
        LlmClient.Message systemMessage = LlmClient.Message.system(buildSystemPrompt(memoryContext));
        conversationHistory.set(0, systemMessage);
        conversationLedger.appendMessage(
                "react", "agent", "memory_context_refresh", systemMessage);
    }

    private String buildSystemPrompt(String memoryContext) {
        return promptAssembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .projectMemoryContext(buildProjectMemoryContext())
                .memoryContext(memoryContext)
                .externalContext(buildExternalContext())
                .toolsEnabled(llmClient == null || llmClient.supportsTools())
                .build());
    }

    private void maybeCompactHistory() {
        int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
        int beforeMessages = conversationHistory.size();
        long beforeTokens = estimateCurrentContextTokens();
        try {
            AutoCompactionManager.Result result = autoCompactionManager.compactIfNeeded(conversationHistory, trigger);
            if (result.compacted()) {
                recordCompaction("automatic:" + result.strategy().name().toLowerCase(), beforeMessages, beforeTokens);
                String strategy = result.strategy() == AutoCompactionManager.Strategy.SESSION_MEMORY
                        ? "会话记忆摘要"
                        : "完整对话摘要";
                renderer().stream().println("📦 上下文接近窗口上限，已通过" + strategy + "压缩后继续。");
            }
        } catch (Exception e) {
            log.warn("conversationHistory compaction failed", e);
        }
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context", e);
            return "";
        }
    }

    private String buildProjectMemoryContext() {
        try {
            return ProjectMemoryLoader.createDefault(Path.of(toolRegistry.getProjectPath())).loadForPrompt();
        } catch (Exception e) {
            log.warn("Failed to load PAI.md project memory", e);
            return "";
        }
    }

    /**
     * 获取对话历史（用于调试）
     */
    public List<LlmClient.Message> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    public ConversationLedger getConversationLedger() {
        return conversationLedger;
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public String getContextStatus() {
        io.jrepoflow.context.ContextProfile profile = memoryManager.getContextProfile();
        int window = profile.maxContextWindow();
        int triggerTokens = profile.compressionTriggerTokens();

        // 分类估算 token 占用
        int systemTokens = 0, userTokens = 0, assistantTokens = 0, toolTokens = 0;
        int systemCount = 0, userCount = 0, assistantCount = 0, toolCount = 0;
        for (LlmClient.Message msg : conversationHistory) {
            int t = io.jrepoflow.memory.TokenBudget.estimateMessagesTokens(java.util.List.of(msg));
            switch (msg.role()) {
                case "system" -> { systemTokens += t; systemCount++; }
                case "user" -> { userTokens += t; userCount++; }
                case "assistant" -> { assistantTokens += t; assistantCount++; }
                case "tool" -> { toolTokens += t; toolCount++; }
            }
        }
        int messagesTokens = userTokens + assistantTokens + toolTokens;
        int toolsSchemaTokens = estimateToolsSchemaTokens();
        int total = systemTokens + messagesTokens + toolsSchemaTokens;
        double ratio = window > 0 ? (double) total / window : 0;
        int triggerRemaining = Math.max(0, triggerTokens - total);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 Context Usage   %s   window: %s%n",
                modelLabel(), formatTokens(window)));
        sb.append("\n  ").append(progressBar(ratio, 30))
                .append(String.format("  %d%%  (%s / %s)%n",
                        (int) Math.round(ratio * 100), formatTokens(total), formatTokens(window)));
        sb.append("\n  当前占用细分:\n");
        sb.append(formatLine("System prompt",      systemTokens,    window, systemCount));
        sb.append(formatLine("Tools schema",       toolsSchemaTokens, window, -1));
        sb.append(formatLine("Conversation",       messagesTokens, window,
                userCount + assistantCount + toolCount));
        sb.append("    ─────────────────────────────────\n");
        sb.append(String.format("    合计:              %8s  (%4.1f%%)%n",
                formatTokens(total), ratio * 100));
        sb.append(String.format("%n  压缩阈值: %s (%d%%)   距压缩还有: %s%n",
                formatTokens(triggerTokens),
                (int) (profile.compressionTriggerRatio() * 100),
                formatTokens(triggerRemaining)));
        sb.append("  自动压缩: ")
                .append(autoCompactionManager.isSessionMemoryEnabled()
                        ? "Session Memory 优先，完整摘要回退"
                        : "完整摘要（Session Memory 实验开关关闭）")
                .append("\n");
        sb.append("  MCP resources 自动索引: ")
                .append(profile.mcpResourceIndexEnabled() ? "开启" : "关闭（window 不足 32k）")
                .append("\n");
        sb.append("  prompt cache: ").append(profile.promptCacheMode()).append("\n");
        sb.append("\n");
        sb.append(memoryManager.getSystemStatus());
        return sb.toString();
    }

    private String modelLabel() {
        if (llmClient == null) return "(no model)";
        return llmClient.getModelName() + " (" + llmClient.getProviderName() + ")";
    }

    private int estimateToolsSchemaTokens() {
        try {
            return io.jrepoflow.memory.MemoryEntry.estimateTokens(
                    new ObjectMapper().writeValueAsString(toolRegistry.getToolDefinitions()));
        } catch (Exception e) {
            return 0;
        }
    }

    private long estimateCurrentContextTokens() {
        long messageTokens = io.jrepoflow.memory.TokenBudget.estimateMessagesTokens(conversationHistory);
        return Math.max(0L, messageTokens + estimateToolsSchemaTokens());
    }

    private void logRequestContext(String scope, List<LlmClient.Tool> tools) {
        if (!log.isInfoEnabled()) {
            return;
        }
        int systemTokens = 0;
        int userTokens = 0;
        int assistantTokens = 0;
        int toolMessageTokens = 0;
        int messages = 0;
        for (int messageIndex = 0; messageIndex < conversationHistory.size(); messageIndex++) {
            LlmClient.Message msg = conversationHistory.get(messageIndex);
            messages++;
            int tokens = io.jrepoflow.memory.TokenBudget.estimateMessagesTokens(List.of(msg));
            switch (msg.role()) {
                case "system" -> systemTokens += tokens;
                case "user" -> userTokens += tokens;
                case "assistant" -> assistantTokens += tokens;
                case "tool" -> toolMessageTokens += tokens;
                default -> {
                }
            }
        }
        int toolsSchemaTokens = 0;
        int toolCount = tools == null ? 0 : tools.size();
        if (tools != null && !tools.isEmpty()) {
            try {
                toolsSchemaTokens = io.jrepoflow.memory.MemoryEntry.estimateTokens(
                        new ObjectMapper().writeValueAsString(tools));
            } catch (Exception e) {
                log.debug("Failed to estimate tools schema tokens", e);
            }
        }
        int estimatedTotal = systemTokens + userTokens + assistantTokens + toolMessageTokens + toolsSchemaTokens;
        log.info("LLM request context [{}]: messages={}, systemTokens={}, userTokens={}, assistantTokens={}, toolMessageTokens={}, tools={}, toolsSchemaTokens={}, estimatedTotal={}",
                scope, messages, systemTokens, userTokens, assistantTokens, toolMessageTokens,
                toolCount, toolsSchemaTokens, estimatedTotal);
    }

    private static String formatLine(String label, int tokens, int window, int count) {
        double pct = window > 0 ? (double) tokens / window * 100 : 0;
        String countLabel = count >= 0 ? String.format("  [%d 条]", count) : "";
        return String.format("    %-18s %8s  (%4.1f%%)%s%n",
                label + ":", formatTokens(tokens), pct, countLabel);
    }

    private static String progressBar(double ratio, int width) {
        ratio = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(ratio * width);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        bar.append("]");
        return bar.toString();
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000)     return String.format("%.1fk", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    /**
     * 获取工具注册表（用于同步项目路径等配置）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /** 把当前预算、耗时和执行阶段推送给 renderer。 */
    private void pushStatus(AgentBudget budget, long startNanos, String phase) {
        try {
            String model = llmClient == null ? "—" : llmClient.getModelName();
            long totalTokens = budget == null ? 0L
                    : (long) (budget.totalInputTokens() + budget.totalOutputTokens());
            long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
            long elapsed = (System.nanoTime() - startNanos) / 1_000_000L;
            String cost = budget == null ? null : TokenUsageFormatter.estimatedCostCny(
                    llmClient,
                    budget.totalInputTokens(),
                    budget.totalOutputTokens(),
                    budget.totalCachedInputTokens());
            renderer().updateStatus(StatusInfo.tokens(
                    model,
                    contextWindow,
                    estimateCurrentContextTokens(),
                    budget == null ? 0L : budget.totalInputTokens(),
                    budget == null ? 0L : budget.totalOutputTokens(),
                    budget == null ? 0L : budget.totalCachedInputTokens(),
                    cost,
                    elapsed,
                    phase == null || phase.isBlank()
                            ? (totalTokens > 0 || elapsed > 0 ? "running" : "idle")
                            : phase));
        } catch (Exception e) {
            log.debug("status push failed", e);
        }
    }

    private void appendReasoning(StringBuilder reasoningTranscript, String reasoningContent) {
        if (reasoningContent == null || reasoningContent.isBlank()) {
            return;
        }
        if (!reasoningTranscript.isEmpty()) {
            reasoningTranscript.append("\n\n");
        }
        reasoningTranscript.append(reasoningContent.trim());
    }

    private List<ToolExecutionResult> executeToolCalls(List<LlmClient.ToolCall> toolCalls,
                                                       int iteration,
                                                       TurnToolPolicy turnToolPolicy,
                                                       TurnToolPolicy.ToolExposure toolExposure) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Scheduling tool: {} (iteration={})", toolName, iteration);
            log.debug("Tool args [{}]: {}", toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Executing {} tool calls in parallel (iteration={})", invocations.size(), iteration);
        }
        List<ToolExecutionResult> results = turnToolPolicy.execute(toolRegistry, invocations, toolExposure);
        for (ToolExecutionResult result : results) {
            log.debug("Tool result preview [{}]: {}", result.name(), preview(result.result(), 300));
        }
        return results;
    }

    private void appendConversationMessage(LlmClient.Message message, String source) {
        conversationHistory.add(message);
        conversationLedger.appendMessage("react", "agent", source, message);
    }

    private void recordCompaction(String source, int beforeMessages, long beforeTokens) {
        conversationLedger.appendEvent(
                "compaction",
                "react",
                "agent",
                source,
                java.util.Map.of(
                        "beforeMessages", beforeMessages,
                        "afterMessages", conversationHistory.size(),
                        "beforeTokens", beforeTokens,
                        "afterTokens", estimateCurrentContextTokens()));
    }

    private String formatUserFacingResponse(String reasoningContent, String answer) {
        String normalizedReasoning = reasoningContent == null ? "" : reasoningContent.trim();
        String normalizedAnswer = answer == null ? "" : answer.trim();

        if (!renderer().rendersReasoning() || normalizedReasoning.isEmpty()) {
            return normalizedAnswer;
        }
        if (normalizedAnswer.isEmpty()) {
            return "🧠 思考过程:\n" + normalizedReasoning;
        }
        return "🧠 思考过程:\n" + normalizedReasoning + "\n\n▪ " + normalizedAnswer;
    }

    private String preview(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    /**
     * 流式输出渲染器，将 reasoning_content 与 content 分区展示。
     *
     * 服务器可能把 reasoning_content 切成多段下发，甚至在 content 开始之后追加 reasoning；
     * 终端是线性的，无法回头修改已写出的文字。渲染策略：
     *
     * 1. 在 content 出现之前，只要 reasoning 有实质内容（非空白），就立刻流式打印在"🧠 思考过程"下
     *    同一次用户输入只打印一次"🧠 思考过程"标题；工具调用后的后续推理继续归在同一块下
     * 2. 仅空白的 reasoning delta 会先暂存，不触发标题——避免出现"空的思考过程"
     * 3. content 一出现就收尾 reasoning 区，用低调标记进入正文并流式输出 content
     * 4. 如果 content 启动之后又收到 reasoning（服务器把思考内容追加在答案之后），
     *    缓冲到 lateReasoning，最终在 finish() 用"🧠 补充思考"标题独立展示，不会污染回复区
     */
    private static final class StreamRenderer implements LlmClient.StreamListener {
        private final Renderer renderer;
        private final PrintStream boundOut;  // null 表示延迟读取 System.out（保持旧测试兼容）
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder visibleReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningHeadingPrinted;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean thinkingQuotePrinted;
        private boolean streamedOutput;

        StreamRenderer() {
            this.renderer = null;
            this.boundOut = null;
        }

        StreamRenderer(PrintStream out) {
            this.renderer = null;
            this.boundOut = out;
        }

        StreamRenderer(Renderer renderer) {
            this.renderer = renderer;
            this.boundOut = renderer == null ? null : renderer.stream();
        }

        private PrintStream out() {
            return boundOut != null ? boundOut : System.out;
        }

        private boolean hasThinkingPanel() {
            return renderer != null && renderer.supportsThinkingPanel();
        }

        private boolean rendersReasoning() {
            return renderer == null || renderer.rendersReasoning();
        }

        private void beginThinking() {
            if (hasThinkingPanel()) {
                renderer.beginThinking("Thinking");
            }
        }

        private void clearThinkingPanel() {
            if (hasThinkingPanel()) {
                renderer.endThinking();
                pendingReasoning.setLength(0);
            }
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!rendersReasoning()) {
                return;
            }
            if (contentStarted) {
                // content 已开始，无法回头；缓冲到"补充思考"
                lateReasoning.append(delta);
                return;
            }
            visibleReasoning.append(delta);
            if (hasThinkingPanel()) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                renderer.appendThinking(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;  // 还没攒出实质内容，等
                }
                if (!containsLineBreak(pendingReasoning)) {
                    return;  // 避免先打印一个空标题，等有完整行或迭代切换时再 flush
                }
                printReasoningHeadingIfNeeded();
                reasoningRenderer = newMarkdownRenderer();
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
            } else {
                if (hasThinkingPanel()) {
                    renderer.appendThinking(delta);
                } else {
                    reasoningRenderer.append(delta);
                }
            }
            out().flush();
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (hasThinkingPanel()) {
                    finishThinkingPanelAndPrintQuote();
                } else if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out().println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    printReasoningHeadingIfNeeded();
                    TerminalMarkdownRenderer r = newMarkdownRenderer();
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out().println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                out().print(AnsiStyle.answerMarker() + " ");
                contentRenderer = newMarkdownRenderer();
                contentStarted = true;
                streamedOutput = true;
            }
            contentRenderer.append(delta);
            if (renderer != null) {
                renderer.appendAssistantContentDelta(delta);
            }
            out().flush();
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void resetBetweenIterations() {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            }
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            } else if (!hasThinkingPanel()) {
                flushPendingReasoning();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            if (renderer != null) {
                renderer.finishAssistantContent();
            }
            String late = lateReasoning.toString().trim();
            if (rendersReasoning() && !late.isEmpty()) {
                out().println();
                out().println(AnsiStyle.heading("🧠 补充思考"));
                TerminalMarkdownRenderer r = newMarkdownRenderer();
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            pendingReasoning.setLength(0);
            visibleReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            thinkingQuotePrinted = false;
            if (streamedOutput) {
                out().println();
            }
        }

        private void finish() {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            }
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
            } else if (!hasThinkingPanel()) {
                flushPendingReasoning();
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
            }
            if (renderer != null) {
                renderer.finishAssistantContent();
            }
            String late = lateReasoning.toString().trim();
            if (rendersReasoning() && !late.isEmpty()) {
                out().println();
                out().println(AnsiStyle.heading("🧠 补充思考"));
                TerminalMarkdownRenderer r = newMarkdownRenderer();
                r.append(late);
                r.finish();
                lateReasoning.setLength(0);
                streamedOutput = true;
            }
            if (streamedOutput) {
                out().println();
            }
        }

        private boolean containsLineBreak(CharSequence content) {
            for (int i = 0; i < content.length(); i++) {
                char ch = content.charAt(i);
                if (ch == '\n' || ch == '\r') {
                    return true;
                }
            }
            return false;
        }

        private void flushPendingReasoning() {
            String pending = pendingReasoning.toString();
            if (pending.isBlank()) {
                pendingReasoning.setLength(0);
                return;
            }
            printReasoningHeadingIfNeeded();
            TerminalMarkdownRenderer renderer = newMarkdownRenderer();
            renderer.append(pending);
            renderer.finish();
            pendingReasoning.setLength(0);
            streamedOutput = true;
        }

        private TerminalMarkdownRenderer newMarkdownRenderer() {
            if (renderer != null) {
                return new TerminalMarkdownRenderer(out(), renderer::terminalColumns);
            }
            return new TerminalMarkdownRenderer(out());
        }

        private void finishThinkingPanelAndPrintQuote() {
            if (!hasThinkingPanel()) {
                return;
            }
            if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                renderer.appendThinking(pendingReasoning.toString());
            }
            renderer.endThinking();
            pendingReasoning.setLength(0);
            printThinkingQuoteIfNeeded();
        }

        private void printThinkingQuoteIfNeeded() {
            if (thinkingQuotePrinted) {
                return;
            }
            if (!rendersReasoning()) {
                return;
            }
            String reasoning = visibleReasoning.toString()
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim();
            if (reasoning.isEmpty()) {
                return;
            }
            out().println(AnsiStyle.thinking("Thinking..."));
            for (String line : reasoning.split("\\R+")) {
                String normalized = line.replaceAll("\\s+", " ").trim();
                if (!normalized.isEmpty()) {
                    out().println(AnsiStyle.subtle("│ " + normalized));
                }
            }
            out().println();
            thinkingQuotePrinted = true;
            streamedOutput = true;
        }

        private void printReasoningHeadingIfNeeded() {
            if (!reasoningHeadingPrinted) {
                if (!rendersReasoning()) {
                    return;
                }
                out().println(AnsiStyle.heading("🧠 思考过程"));
                reasoningHeadingPrinted = true;
            }
        }
    }
}
