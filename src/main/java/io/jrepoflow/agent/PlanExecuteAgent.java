package io.jrepoflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jrepoflow.history.ConversationLedger;
import io.jrepoflow.llm.LlmClient;
import io.jrepoflow.llm.LlmTraceLogger;
import io.jrepoflow.memory.AutoCompactionManager;
import io.jrepoflow.memory.MemoryManager;
import io.jrepoflow.plan.*;
import io.jrepoflow.prompt.PromptAssembler;
import io.jrepoflow.prompt.PromptContext;
import io.jrepoflow.prompt.PromptMode;
import io.jrepoflow.prompt.ProjectMemoryLoader;
import io.jrepoflow.runtime.CancellationContext;
import io.jrepoflow.util.AnsiStyle;
import io.jrepoflow.tool.ToolRegistry;
import io.jrepoflow.tool.ToolRegistry.ToolExecutionResult;
import io.jrepoflow.tool.ToolRegistry.ToolInvocation;
import io.jrepoflow.tool.TurnToolPolicy;
import io.jrepoflow.util.TerminalMarkdownRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent - 先规划后执行
 */
public class PlanExecuteAgent {
    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private record PlanRunOutcome(String result, boolean persistAssistantMessage) {
        static PlanRunOutcome executed(String result) {
            return new PlanRunOutcome(result, true);
        }

        static PlanRunOutcome canceled(String result) {
            return new PlanRunOutcome(result, false);
        }

        static PlanRunOutcome failed(String result) {
            return new PlanRunOutcome(result, true);
        }
    }

    private record TaskRunResult(String result, boolean streamedOutput) {
        static TaskRunResult of(String result, boolean streamedOutput) {
            return new TaskRunResult(result, streamedOutput);
        }
    }

    private record TaskExecutionResult(Task task, String result, boolean streamedOutput, Exception error) {
        static TaskExecutionResult success(Task task, TaskRunResult taskRunResult) {
            return new TaskExecutionResult(task, taskRunResult.result(), taskRunResult.streamedOutput(), null);
        }

        static TaskExecutionResult failure(Task task, Exception error) {
            return new TaskExecutionResult(task, null, false, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    public enum PlanReviewAction {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() {
            return new PlanReviewDecision(PlanReviewAction.EXECUTE, null);
        }

        public static PlanReviewDecision supplement(String feedback) {
            return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback);
        }

        public static PlanReviewDecision cancel() {
            return new PlanReviewDecision(PlanReviewAction.CANCEL, null);
        }
    }

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final PlanReviewHandler reviewHandler;
    private final MemoryManager memoryManager;
    private final AutoCompactionManager autoCompactionManager;
    private final PrintStream out;
    private ConversationLedger conversationLedger = ConversationLedger.disabled();
    private Supplier<String> externalContextSupplier = () -> "";
    private TurnToolPolicy turnToolPolicy = TurnToolPolicy.forExplicitTask("");
    private String submittedPolicyInput = "";
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();

    public PlanExecuteAgent(LlmClient llmClient) {
        this(llmClient, (goal, plan) -> PlanReviewDecision.execute());
    }

    public PlanExecuteAgent(LlmClient llmClient, PlanReviewHandler reviewHandler) {
        this(llmClient, new ToolRegistry(), null, null, reviewHandler);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler,
                            PrintStream out) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler, out);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, planner, memoryManager, reviewHandler, null);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler, PrintStream out) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry != null ? toolRegistry : new ToolRegistry();
        this.out = out == null ? deferredSystemOut() : out;
        this.planner = planner != null ? planner : new Planner(llmClient, this.out);
        this.reviewHandler = reviewHandler == null ? (goal, plan) -> PlanReviewDecision.execute() : reviewHandler;
        this.memoryManager = memoryManager != null ? memoryManager : new MemoryManager(llmClient);
        this.autoCompactionManager = new AutoCompactionManager(llmClient);
        this.toolRegistry.setContextProfile(this.memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.toolRegistry.setScopedMemorySaver(this.memoryManager::storeFact);
        this.planner.setProjectMemorySupplier(this::buildProjectMemoryContext);
    }

    private static PrintStream deferredSystemOut() {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                System.out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                System.out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                System.out.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public void setConversationLedger(ConversationLedger conversationLedger) {
        this.conversationLedger = conversationLedger == null
                ? ConversationLedger.disabled()
                : conversationLedger;
        this.planner.setConversationLedger(this.conversationLedger);
    }

    ConversationLedger getConversationLedger() {
        return conversationLedger;
    }

    private void maybeCompactHistory(List<LlmClient.Message> messages, PrintStream out, String actor) {
        int trigger = memoryManager.getContextProfile().compressionTriggerTokens();
        int beforeMessages = messages.size();
        try {
            AutoCompactionManager.Result result = autoCompactionManager.compactIfNeeded(messages, trigger);
            if (result.compacted()) {
                conversationLedger.appendEvent(
                        "compaction",
                        "plan",
                        actor,
                        "automatic",
                        Map.of(
                                "beforeMessages", beforeMessages,
                                "afterMessages", messages.size(),
                                "strategy", result.strategy().name().toLowerCase()));
                if (out != null) {
                    out.println("📦 上下文接近窗口上限，已把早期对话压缩为摘要后继续。");
                }
            }
        } catch (Exception e) {
            log.warn("conversationHistory compaction failed", e);
        }
    }

    /**
     * 运行任务（自动判断是否需要规划）
     */
    public String run(String userInput) {
        return run(userInput, userInput);
    }

    /** Use submittedUserInput for policy decisions and userInput for expanded task context. */
    public String run(String userInput, String submittedUserInput) {
        log.info("Plan run started: inputLength={}", userInput == null ? 0 : userInput.length());
        submittedPolicyInput = submittedUserInput == null ? "" : submittedUserInput;
        turnToolPolicy = TurnToolPolicy.fromUserInput(submittedUserInput);
        conversationLedger.appendMessage(
                "plan", "plan-agent", "user_input", LlmClient.Message.user(userInput));
        StreamState streamState = new StreamState();
        try {
            if (CancellationContext.isCancelled()) {
                conversationLedger.appendEvent(
                        "run_cancelled", "plan", "plan-agent", "before_planning", Map.of());
                return "⏹️ 已取消当前计划执行。";
            }
            PlanRunOutcome outcome = runWithPlan(userInput, streamState);
            if (outcome.persistAssistantMessage() && outcome.result() != null && !outcome.result().isBlank()) {
                conversationLedger.appendMessage(
                        "plan",
                        "plan-agent",
                        "run_result",
                        LlmClient.Message.assistant(outcome.result()));
            }
            if (streamState.hasStreamedOutput() && (outcome.result() == null || outcome.result().isBlank())) {
                return "";
            }
            return outcome.result();
        } catch (Exception e) {
            log.error("Plan run failed", e);
            String errorMessage = "❌ 执行失败: " + e.getMessage();
            conversationLedger.appendMessage(
                    "plan",
                    "plan-agent",
                    "run_error",
                    LlmClient.Message.assistant(errorMessage));
            return errorMessage;
        }
    }

/**
     * 使用Plan-and-Execute模式执行
     */
    private PlanRunOutcome runWithPlan(String goal, StreamState streamState) throws IOException {
        ExecutionPlan plan = planner.createPlan(goal);
        return reviewAndExecutePlan(plan, streamState);
    }

    private PlanRunOutcome reviewAndExecutePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        while (true) {
            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
            if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                return PlanRunOutcome.executed(executePlan(plan, streamState));
            }

            if (decision.action() == PlanReviewAction.CANCEL) {
                return PlanRunOutcome.canceled("⏹️ 已取消本次计划执行。");
            }

            String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
            if (feedback.isEmpty()) {
                return PlanRunOutcome.executed(executePlan(plan, streamState));
            }

            out.println("📝 已收到补充要求，正在重新规划...\n");
            String revisedGoal = plan.getGoal() + "\n补充要求：" + feedback;
            submittedPolicyInput = submittedPolicyInput + "\n补充要求：" + feedback;
            turnToolPolicy = TurnToolPolicy.fromUserInput(submittedPolicyInput);
            plan = planner.createPlan(revisedGoal);
        }
    }

    private String executePlan(ExecutionPlan plan, StreamState streamState) throws IOException {
        log.info("Executing plan: goal='{}', taskCount={}", plan.getGoal(), plan.getAllTasks().size());
        out.println("🚀 开始执行计划...\n");

        plan.markStarted();
        StringBuilder finalResult = new StringBuilder();
        Map<String, Boolean> streamedTaskOutputs = new HashMap<>();

        while (true) {
            if (CancellationContext.isCancelled()) {
                return "⏹️ 已取消当前计划执行。";
            }
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (executableTasks.isEmpty()) {
                break;
            }

            List<TaskExecutionResult> batchResults = executeTaskBatch(
                    plan, executableTasks, streamState);
            for (TaskExecutionResult batchResult : batchResults) {
                Task task = batchResult.task();

                if (!batchResult.failed()) {
                    task.markCompleted(batchResult.result());
                    streamedTaskOutputs.put(task.getId(), batchResult.streamedOutput());
                    log.info("Task completed: {} status={} resultChars={}",
                            task.getId(), task.getStatus(), batchResult.result() == null ? 0 : batchResult.result().length());
                    if (batchResult.streamedOutput() || batchResult.result() == null || batchResult.result().isBlank()) {
                        out.println("✅ 完成 [" + task.getId() + "]\n");
                    } else {
                        out.println("✅ 完成 [" + task.getId() + "]: "
                                + batchResult.result().substring(0, Math.min(100, batchResult.result().length())) + "\n");
                    }
                    continue;
                }

                Exception error = batchResult.error();
                task.markFailed(error.getMessage());
                log.warn("Task failed: {} error={}", task.getId(), error.getMessage());
                out.println("❌ 失败 [" + task.getId() + "]: " + error.getMessage() + "\n");

                if (plan.getProgress() < 0.5) {
                    out.println("🔄 尝试重新规划...\n");
                    ExecutionPlan replanned = planner.replan(plan, error.getMessage());
                    return reviewAndExecutePlan(replanned, streamState).result();
                }

                if (!finalResult.isEmpty()) {
                    finalResult.append("\n");
                }
                finalResult.append("任务 ").append(task.getId()).append(" 失败: ").append(error.getMessage());
            }
        }

        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            return "⚠️ 计划未能继续推进，存在未满足依赖的任务。";
        }

        String planSummary = finalResult.isEmpty()
                ? buildFinalResult(plan, streamedTaskOutputs)
                : finalResult.toString();

        if (plan.hasFailed()) {
            plan.markFailed();
            if (planSummary.isBlank()) {
                return "⚠️ 计划部分完成，有任务失败。";
            }
            return "⚠️ 计划部分完成，有任务失败。\n" + planSummary;
        }

        plan.markCompleted();
        if (planSummary.isBlank()) {
            return "✅ 计划执行完成！";
        }
        return "✅ 计划执行完成！\n" + planSummary;
    }

    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    private List<TaskExecutionResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks,
                                                       StreamState streamState) {
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            log.info("Executing single task: {} type={}", task.getId(), task.getType());
            out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
            task.markStarted();

            try {
                return List.of(TaskExecutionResult.success(task, executeTask(
                        plan.getGoal(), plan, task, streamState, out)));
            } catch (Exception e) {
                return List.of(TaskExecutionResult.failure(task, e));
            }
        }

        String parallelTaskIds = executableTasks.stream()
                .map(Task::getId)
                .collect(Collectors.joining(", "));
        log.info("Executing parallel batch: {}", parallelTaskIds);
        out.println("⚡ 本轮并行执行 " + executableTasks.size() + " 个任务: " + parallelTaskIds);

        // 只并行当前 ready batch；后继任务要等本批状态统一落盘后才会被下一轮选中。
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(executableTasks.size(), 4), r -> {
            Thread t = new Thread(r, "jrepoflow-plan-executor");
            t.setDaemon(true);
            return t;
        });
        try {
            Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
            List<Future<TaskExecutionResult>> futures = new ArrayList<>();
            for (Task task : executableTasks) {
                out.println("▶️ 并行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                buffers.put(task.getId(), baos);
                PrintStream taskOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
                futures.add(executor.submit(() -> {
                    try {
                        return TaskExecutionResult.success(task, executeTask(
                                plan.getGoal(), plan, task, streamState, taskOut));
                    } catch (Exception e) {
                        return TaskExecutionResult.failure(task, e);
                    }
                }));
            }

            // Future 按提交顺序收集，完成先后不会扰乱 DAG 中声明的输出顺序。
            List<TaskExecutionResult> results = new ArrayList<>();
            for (Future<TaskExecutionResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), e));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    Exception error = cause instanceof Exception exception
                            ? exception
                            : new RuntimeException(cause);
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), error));
                }
            }

            // 按任务顺序 flush 各缓冲区到 stdout，避免并行输出交错
            for (Task task : executableTasks) {
                ByteArrayOutputStream buf = buffers.get(task.getId());
                if (buf != null && buf.size() > 0) {
                    out.print(buf.toString(StandardCharsets.UTF_8));
                    out.flush();
                }
            }

            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 执行单个任务（支持多轮工具调用）
     */
    private TaskRunResult executeTask(String goal, ExecutionPlan plan, Task task,
                                      StreamState streamState, PrintStream out) throws IOException {
        TurnToolPolicy taskToolPolicy = turnToolPolicy.fork();
        return executeTaskWithPolicy(goal, plan, task, streamState, out, taskToolPolicy);
    }

    private TaskRunResult executeTaskWithPolicy(
            String goal, ExecutionPlan plan, Task task, StreamState streamState, PrintStream out,
            TurnToolPolicy taskToolPolicy) throws IOException {
        String prompt = promptAssembler.assemble(PromptMode.PLAN, PromptContext.builder()
                .projectMemoryContext(buildProjectMemoryContext())
                .variable("taskType", task.getType())
                .variable("taskDescription", task.getDescription())
                .externalContext(buildExternalContext())
                .toolsEnabled(llmClient == null || llmClient.supportsTools())
                .build());

        // 注入长期记忆上下文
        String memoryContext = memoryManager.buildContextForQuery(
                task.getDescription(),
                memoryManager.getContextProfile().memoryContextTokens());
        String taskInput = buildTaskContext(goal, plan, task);
        if (!memoryContext.isEmpty()) {
            taskInput = taskInput + "\n\n" + memoryContext;
        }
        String actor = "task:" + task.getId();
        List<LlmClient.Message> messages = new ArrayList<>();
        appendTaskMessage(messages, actor, "system_prompt", LlmClient.Message.system(prompt));
        appendTaskMessage(
                messages,
                actor,
                "task_input",
                LlmClient.Message.user(taskInput));

        StringBuilder allResults = new StringBuilder();
        TaskStreamRenderer streamRenderer = new TaskStreamRenderer(task.getId(), streamState, out);
        AgentBudget budget = AgentBudget.fromLlmClient(llmClient);

        while (true) {
            if (CancellationContext.isCancelled()) {
                streamRenderer.finish();
                return TaskRunResult.of("⏹️ 已取消任务 [" + task.getId() + "]。",
                        streamRenderer.hasStreamedOutput());
            }

            AgentBudget.ExitReason exitReason = budget.check();
            if (exitReason != AgentBudget.ExitReason.WITHIN_BUDGET) {
                return finalizePartialTask(
                        task,
                        messages,
                        actor,
                        exitReason,
                        budget,
                        allResults,
                        streamRenderer,
                        out);
            }
            int iteration = budget.beginIteration();

            // 调 LLM 前评估 messages 是否接近 window 上限；超阈值压缩早期消息为摘要。
            maybeCompactHistory(messages, out, actor);

            List<LlmClient.Tool> toolDefinitions = llmClient.supportsTools()
                    ? toolRegistry.getToolDefinitions()
                    : null;
            TurnToolPolicy.ToolExposure toolExposure = taskToolPolicy.expose(toolDefinitions);
            LlmClient.ChatResponse response = llmClient.chat(
                    messages,
                    toolExposure.definitions(),
                    streamRenderer
            );
            LlmTraceLogger.logReasoning(log,
                    "plan-task task=" + task.getId() + " iteration=" + iteration,
                    llmClient,
                    response.reasoningContent());
            if (CancellationContext.isCancelled()) {
                streamRenderer.finish();
                return TaskRunResult.of("⏹️ 已取消任务 [" + task.getId() + "]。",
                        streamRenderer.hasStreamedOutput());
            }

            budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());

            log.info("Task {} iteration {} response: toolCalls={}, reasoningChars={}, contentChars={}",
                    task.getId(),
                    iteration,
                    response.toolCalls() == null ? 0 : response.toolCalls().size(),
                    response.reasoningContent() == null ? 0 : response.reasoningContent().length(),
                    response.content() == null ? 0 : response.content().length());

            if (!response.hasToolCalls()) {
                conversationLedger.appendMessage(
                        "plan",
                        actor,
                        "llm_response",
                        LlmClient.Message.assistant(response.reasoningContent(), response.content()));
                memoryManager.recordTokenUsage(
                        budget.totalInputTokens(),
                        budget.totalOutputTokens(),
                        budget.totalCachedInputTokens());
                if (!allResults.isEmpty() && (response.content() == null || response.content().isBlank())) {
                    String toolOnlyResult = allResults.toString().trim();
                    streamRenderer.finish();
                    return TaskRunResult.of(toolOnlyResult, streamRenderer.hasStreamedOutput());
                }
                streamRenderer.finish();
                return TaskRunResult.of(response.content(), streamRenderer.hasStreamedOutput());
            }

            // 有工具调用：执行工具并将结果回灌到消息历史
            budget.recordToolCalls(response.toolCalls());
            printToolCalls(out, taskToolPolicy.visibleToolCalls(response.toolCalls(), toolExposure));
            appendTaskMessage(messages, actor, "llm_response", LlmClient.Message.assistant(
                    response.reasoningContent(),
                    response.content(),
                    response.toolCalls()
            ));

            // 工具输出会插入正文流，先 flush Markdown 缓冲并重置迭代渲染状态。
            streamRenderer.resetBetweenIterations();

            List<ToolExecutionResult> toolResults = executeToolCalls(
                    task.getId(), response.toolCalls(), taskToolPolicy, toolExposure);
            for (ToolExecutionResult toolResult : toolResults) {
                allResults.append(toolResult.result()).append("\n");
                appendTaskMessage(
                        messages,
                        actor,
                        "tool_execution",
                        LlmClient.Message.tool(toolResult.id(), toolResult.result()));
            }
        }
    }

    /** 命中显式预算后，以一次无工具调用整理计划任务的部分成果。 */
    private TaskRunResult finalizePartialTask(
            Task task,
            List<LlmClient.Message> messages,
            String actor,
            AgentBudget.ExitReason exitReason,
            AgentBudget budget,
            StringBuilder allResults,
            TaskStreamRenderer streamRenderer,
            PrintStream out) {
        String description = budget.describeExit(exitReason);
        log.warn("Plan task {} exhausted budget: reason={}, iteration={}, tokens={}/{}",
                task.getId(), exitReason, budget.iteration(),
                budget.totalInputTokens() + budget.totalOutputTokens(), budget.tokenBudget());
        appendTaskMessage(
                messages,
                actor,
                "budget_finalization",
                LlmClient.Message.user(budget.finalizationInstruction(exitReason)));
        out.println(AnsiStyle.section("⚠️ 任务 [" + task.getId() + "] 执行预算已触发，正在整理部分结果"));

        String content;
        try {
            LlmClient.ChatResponse response = llmClient.chat(messages, List.of(), streamRenderer);
            budget.recordTokens(response.inputTokens(), response.outputTokens(), response.cachedInputTokens());
            content = response.content() == null ? "" : response.content().trim();
            appendTaskMessage(
                    messages,
                    actor,
                    "budget_finalization_response",
                    LlmClient.Message.assistant(response.reasoningContent(), response.content()));
        } catch (IOException e) {
            log.error("Plan task {} finalization call failed after budget exhaustion", task.getId(), e);
            content = "收尾调用失败：" + e.getMessage();
        }

        if (content.isBlank()) {
            content = allResults.toString().trim();
        }
        String partialResult = formatPartialResult(description, content);
        memoryManager.recordTokenUsage(
                budget.totalInputTokens(),
                budget.totalOutputTokens(),
                budget.totalCachedInputTokens());
        streamRenderer.finish();
        return TaskRunResult.of(partialResult, streamRenderer.hasStreamedOutput());
    }

    private String formatPartialResult(String description, String content) {
        String heading = "⚠️ 部分完成（" + description + "）";
        return content == null || content.isBlank() ? heading : heading + "\n\n" + content;
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context for plan task", e);
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

    private List<ToolExecutionResult> executeToolCalls(String taskId,
                                                       List<LlmClient.ToolCall> toolCalls,
                                                       TurnToolPolicy taskToolPolicy,
                                                       TurnToolPolicy.ToolExposure toolExposure) {
        List<ToolInvocation> invocations = new ArrayList<>();
        for (LlmClient.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.function().name();
            String toolArgs = toolCall.function().arguments();
            log.info("Task {} scheduling tool {}", taskId, toolName);
            log.debug("Task {} tool args [{}]: {}", taskId, toolName, toolArgs);
            invocations.add(new ToolInvocation(toolCall.id(), toolName, toolArgs));
        }

        if (invocations.size() > 1) {
            log.info("Task {} executing {} tool calls in parallel", taskId, invocations.size());
        }
        List<ToolExecutionResult> results = taskToolPolicy.execute(toolRegistry, invocations, toolExposure);
        for (ToolExecutionResult result : results) {
            log.debug("Task {} tool result preview [{}]: {}", taskId, result.name(), preview(result.result(), 300));
        }
        return results;
    }

    private void appendTaskMessage(List<LlmClient.Message> messages, String actor,
                                   String source, LlmClient.Message message) {
        messages.add(message);
        conversationLedger.appendMessage("plan", actor, source, message);
    }

    private static void printToolCalls(PrintStream out, List<LlmClient.ToolCall> toolCalls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<LlmClient.ToolCall> calls = group.getValue();
            out.println(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (LlmClient.ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    out.println(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
    }

    private static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "search_code" -> "🔍 搜索代码 " + count + " 次";
            case "save_memory" -> "💾 保存长期记忆 " + count + " 条";
            default -> toolName != null && toolName.startsWith("mcp__")
                    ? formatMcpLabel(toolName, count)
                    : "🔧 " + toolName + " × " + count;
        };
    }

    private static String formatMcpLabel(String toolName, int count) {
        String[] parts = toolName.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
        return count == 1
                ? "🔌 调用 MCP 工具 " + display
                : "🔌 调用 MCP 工具 " + display + " × " + count;
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON_MAPPER.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code" -> "query";
                case "save_memory" -> "fact";
                default -> null;
            };
            if (key == null) {
                return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
            }
            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }

    private static final class StreamState {
        private volatile boolean streamedOutput;

        private void markStreamed() {
            this.streamedOutput = true;
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }
    }

    private static final class TaskStreamRenderer implements LlmClient.StreamListener {
        private final String taskId;
        private final StreamState streamState;
        private final PrintStream out;
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean streamedOutput;

        private TaskStreamRenderer(String taskId, StreamState streamState, PrintStream out) {
            this.taskId = taskId;
            this.streamState = streamState;
            this.out = out;
        }

        @Override
        public synchronized void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (contentStarted) {
                lateReasoning.append(delta);
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            } else {
                reasoningRenderer.append(delta);
            }
            out.flush();
        }

        @Override
        public synchronized void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out.println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                    TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out.println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                // content 可能只是 tool-call 前的叙述，也可能是最终回答，用"输出"避免误导。
                out.println(AnsiStyle.section("🤖 任务输出 [" + taskId + "]"));
                contentRenderer = new TerminalMarkdownRenderer(out);
                contentStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            }
            contentRenderer.append(delta);
            out.flush();
        }

        private synchronized void finish() {
            if (streamedOutput) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                if (contentRenderer != null) {
                    contentRenderer.finish();
                }
                flushLateReasoning();
                out.println("\n");
            }
        }

        /**
         * 两次 iteration 之间（通常是一次 tool-call 分支完成后）调用：收尾当前渲染器并重置状态，
         * 让下一轮迭代能重新打印 🧠 / 🤖 标题，避免被工具输出打断后发生错位。
         */
        private synchronized void resetBetweenIterations() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            flushLateReasoning();
            pendingReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            if (streamedOutput) {
                out.println();
            }
        }

        private synchronized boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void flushLateReasoning() {
            String late = lateReasoning.toString().trim();
            if (late.isEmpty()) {
                lateReasoning.setLength(0);
                return;
            }
            out.println();
            out.println(AnsiStyle.heading("🧠 补充思考 [" + taskId + "]"));
            TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(out);
            renderer.append(late);
            renderer.finish();
            lateReasoning.setLength(0);
        }
    }

    private String buildTaskContext(String goal, ExecutionPlan plan, Task task) {
        StringBuilder context = new StringBuilder();
        context.append("总目标：").append(goal).append("\n");
        context.append("当前任务：").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务：无\n");
        } else {
            context.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep == null) {
                    continue;
                }
                context.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getStatus())
                        .append("\n");
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    context.append(dep.getResult()).append("\n");
                }
            }
        }

        context.append("请执行此任务。如果是ANALYSIS或VERIFICATION类型，请基于以上上下文直接给出结果。");
        return context.toString();
    }

    private String buildFinalResult(ExecutionPlan plan, Map<String, Boolean> streamedTaskOutputs) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
            if (Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId()))) {
                continue;
            }
            if (task.getResult() == null || task.getResult().isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append("[").append(task.getId()).append("] ").append(task.getResult());
        }

        if (!result.isEmpty()) {
            return result.toString();
        }

        return plan.getAllTasks().stream()
                .filter(task -> !Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId())))
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
    }

}
