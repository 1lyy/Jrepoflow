package io.jrepoflow.agent;

import io.jrepoflow.llm.LlmClient;
import io.jrepoflow.llm.LlmTraceLogger;
import io.jrepoflow.prompt.ProjectMemoryLoader;
import io.jrepoflow.prompt.PromptAssembler;
import io.jrepoflow.prompt.PromptContext;
import io.jrepoflow.prompt.PromptMode;
import io.jrepoflow.runtime.CancellationContext;
import io.jrepoflow.tool.ToolRegistry;
import io.jrepoflow.tool.ToolRegistry.ToolExecutionResult;
import io.jrepoflow.tool.ToolRegistry.ToolInvocation;
import io.jrepoflow.tool.TurnToolPolicy;
import io.jrepoflow.util.AnsiStyle;
import io.jrepoflow.util.TerminalMarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 具有独立角色提示词和短期对话历史的轻量子 Agent。
 *
 * <p>Planner 和 Reviewer 只输出结构化分析，只有 Worker 能看到并调用工具。多个
 * Worker 共享线程安全的 LLM 客户端和 ToolRegistry，但由 Orchestrator 池化分配，
 * 保证同一个 SubAgent 不会同时处理两个步骤。</p>
 */
public final class SubAgent {
    private static final Logger log = LoggerFactory.getLogger(SubAgent.class);
    private static final int MAX_ITERATIONS = 10;

    private final String name;
    private final AgentRole role;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final List<LlmClient.Message> conversationHistory = new ArrayList<>();
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();

    public SubAgent(String name, AgentRole role, LlmClient llmClient, ToolRegistry toolRegistry) {
        this.name = name;
        this.role = role;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt()));
    }

    public AgentMessage execute(AgentMessage task) {
        return execute(task, System.out);
    }

    public AgentMessage execute(AgentMessage task, PrintStream out) {
        if (task == null || task.content() == null || task.content().isBlank()) {
            return AgentMessage.error(name, role, "任务内容为空");
        }
        PrintStream target = out == null ? System.out : out;
        log.info("[{}] executing task from {}: type={}", name, task.fromAgent(), task.type());
        conversationHistory.add(LlmClient.Message.user(task.content()));

        TurnToolPolicy toolPolicy = TurnToolPolicy.forExplicitTask(task.content());
        TeamStreamRenderer renderer = new TeamStreamRenderer(name, role, target);
        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
            if (CancellationContext.isCancelled()) {
                renderer.finish();
                return AgentMessage.error(name, role, "用户取消了当前协作任务");
            }
            try {
                List<LlmClient.Tool> definitions = usesTools()
                        ? toolRegistry.getToolDefinitions()
                        : List.of();
                TurnToolPolicy.ToolExposure exposure = toolPolicy.expose(definitions);
                LlmClient.ChatResponse response = llmClient.chat(
                        conversationHistory, exposure.definitions(), renderer);
                LlmTraceLogger.logReasoning(log, "team-agent=" + name + " iteration=" + iteration,
                        llmClient, response.reasoningContent());

                if (!response.hasToolCalls()) {
                    conversationHistory.add(LlmClient.Message.assistant(
                            response.reasoningContent(), response.content()));
                    renderer.finish();
                    return AgentMessage.result(name, role, response.content());
                }

                conversationHistory.add(LlmClient.Message.assistant(
                        response.reasoningContent(), response.content(), response.toolCalls()));
                List<ToolInvocation> invocations = response.toolCalls().stream()
                        .map(call -> new ToolInvocation(
                                call.id(), call.function().name(), call.function().arguments()))
                        .toList();
                List<ToolExecutionResult> results = toolPolicy.execute(toolRegistry, invocations, exposure);
                for (ToolExecutionResult result : results) {
                    conversationHistory.add(LlmClient.Message.tool(result.id(), result.result()));
                }
                renderer.resetBetweenIterations();
            } catch (IOException | RuntimeException e) {
                log.warn("[{}] execution failed", name, e);
                renderer.finish();
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                return AgentMessage.error(name, role, "LLM 调用失败: " + message);
            }
        }

        renderer.finish();
        return AgentMessage.error(name, role, "达到最大迭代次数限制，任务未完成");
    }

    public AgentMessage executeWithContext(AgentMessage task, String context) {
        return executeWithContext(task, context, System.out);
    }

    public AgentMessage executeWithContext(AgentMessage task, String context, PrintStream out) {
        String enriched = task.content();
        if (context != null && !context.isBlank()) {
            enriched = context.trim() + "\n\n当前任务：" + task.content();
        }
        return execute(new AgentMessage(task.fromAgent(), task.fromRole(), enriched, task.type()), out);
    }

    public AgentMessage review(String originalTask, String executionResult) {
        return review(originalTask, executionResult, System.out);
    }

    public AgentMessage review(String originalTask, String executionResult, PrintStream out) {
        String input = "原始任务：" + originalTask + "\n\n执行结果：\n" + executionResult;
        return execute(AgentMessage.task("orchestrator", input), out);
    }

    public void clearHistory() {
        LlmClient.Message system = conversationHistory.get(0);
        conversationHistory.clear();
        conversationHistory.add(system);
    }

    boolean usesTools() {
        return role == AgentRole.WORKER && llmClient.supportsTools();
    }

    public String getName() {
        return name;
    }

    public AgentRole getRole() {
        return role;
    }

    private String buildSystemPrompt() {
        PromptMode mode = switch (role) {
            case PLANNER -> PromptMode.TEAM_PLANNER;
            case WORKER -> PromptMode.TEAM_WORKER;
            case REVIEWER -> PromptMode.TEAM_REVIEWER;
        };
        return promptAssembler.assemble(mode, PromptContext.builder()
                .projectMemoryContext(loadProjectMemory())
                .toolsEnabled(role == AgentRole.WORKER && llmClient.supportsTools())
                .build());
    }

    private String loadProjectMemory() {
        try {
            return ProjectMemoryLoader.createDefault(
                    Path.of(toolRegistry.getProjectPath()).toAbsolutePath().normalize()).loadForPrompt();
        } catch (Exception e) {
            log.debug("[{}] project memory unavailable: {}", name, e.getMessage());
            return "";
        }
    }

    private static final class TeamStreamRenderer implements LlmClient.StreamListener {
        private final String agentName;
        private final AgentRole role;
        private final PrintStream out;
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningStarted;
        private boolean contentStarted;

        private TeamStreamRenderer(String agentName, AgentRole role, PrintStream out) {
            this.agentName = agentName;
            this.role = role;
            this.out = out;
        }

        @Override
        public synchronized void onReasoningDelta(String delta) {
            if (delta == null || delta.isBlank() || contentStarted) {
                return;
            }
            if (!reasoningStarted) {
                out.println(AnsiStyle.heading("🧠 " + role.getDisplayName() + "思考 [" + agentName + "]"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningStarted = true;
            }
            reasoningRenderer.append(delta);
            out.flush();
        }

        @Override
        public synchronized void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out.println();
                }
                out.println(AnsiStyle.section("🤖 " + role.getDisplayName() + "输出 [" + agentName + "]"));
                contentRenderer = new TerminalMarkdownRenderer(out);
                contentStarted = true;
            }
            contentRenderer.append(delta);
            out.flush();
        }

        private synchronized void resetBetweenIterations() {
            finishRenderers();
            reasoningStarted = false;
            contentStarted = false;
        }

        private synchronized void finish() {
            finishRenderers();
        }

        private void finishRenderers() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            if (reasoningStarted || contentStarted) {
                out.println();
            }
        }
    }
}
