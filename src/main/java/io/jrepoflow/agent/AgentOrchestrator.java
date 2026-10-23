package io.jrepoflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jrepoflow.llm.LlmClient;
import io.jrepoflow.memory.MemoryManager;
import io.jrepoflow.runtime.CancellationContext;
import io.jrepoflow.tool.ToolRegistry;
import io.jrepoflow.util.AnsiStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 中心化 Multi-Agent 编排器。
 *
 * <p>Planner 生成 DAG，Orchestrator 将 ready batch 分配给池化 Worker，随后由独立
 * Reviewer 审查结果。审查失败时把结构化反馈回传给原 Worker，最多重试两次。Agent
 * 之间不点对点通信，所有消息和共享步骤状态都经过 Orchestrator。</p>
 */
public final class AgentOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_WORKERS = 2;
    private static final int MAX_RETRIES_PER_STEP = 2;

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final MemoryManager memoryManager;
    private final PrintStream out;
    private final SubAgent planner;
    private final List<SubAgent> workers;
    private final SubAgent reviewer;

    record ExecutionStep(String id, String description, String type,
                         List<String> dependencies, String result, StepStatus status) {
        ExecutionStep {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }

        static ExecutionStep pending(String id, String description, String type, List<String> dependencies) {
            return new ExecutionStep(id, description, type, dependencies, null, StepStatus.PENDING);
        }

        ExecutionStep started() {
            return new ExecutionStep(id, description, type, dependencies, result, StepStatus.RUNNING);
        }

        ExecutionStep withResult(String value) {
            return new ExecutionStep(id, description, type, dependencies, value, StepStatus.COMPLETED);
        }

        ExecutionStep withFailed(String error) {
            return new ExecutionStep(id, description, type, dependencies, error, StepStatus.FAILED);
        }
    }

    enum StepStatus {
        PENDING, RUNNING, COMPLETED, FAILED
    }

    public AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry, MemoryManager memoryManager) {
        this(llmClient, toolRegistry, memoryManager, System.out, DEFAULT_WORKERS);
    }

    AgentOrchestrator(LlmClient llmClient, ToolRegistry toolRegistry, MemoryManager memoryManager,
                      PrintStream out, int workerCount) {
        if (llmClient == null) {
            throw new IllegalArgumentException("llmClient is required");
        }
        if (toolRegistry == null) {
            throw new IllegalArgumentException("toolRegistry is required");
        }
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager == null ? new MemoryManager(llmClient) : memoryManager;
        this.out = out == null ? System.out : out;
        this.planner = new SubAgent("planner", AgentRole.PLANNER, llmClient, toolRegistry);
        int size = Math.max(1, workerCount);
        List<SubAgent> workerList = new ArrayList<>(size);
        for (int i = 1; i <= size; i++) {
            workerList.add(new SubAgent("worker-" + i, AgentRole.WORKER, llmClient, toolRegistry));
        }
        this.workers = List.copyOf(workerList);
        this.reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, toolRegistry);
    }

    public String run(String userInput) {
        if (userInput == null || userInput.isBlank()) {
            return "❌ Multi-Agent 任务不能为空。";
        }
        log.info("Multi-Agent run started: inputLength={}", userInput.length());

        out.println(AnsiStyle.heading("📋 第一阶段：规划"));
        out.println("🧑‍💼 规划者正在分析任务...\n");
        String planningInput = "请为以下任务制定执行计划：\n" + userInput;
        String memoryContext = memoryManager.buildContextForQuery(
                userInput, memoryManager.getContextProfile().memoryContextTokens());
        if (!memoryContext.isBlank()) {
            planningInput += "\n\n可参考的项目记忆：\n" + memoryContext;
        }

        AgentMessage planResult = planner.execute(AgentMessage.task("orchestrator", planningInput), out);
        planner.clearHistory();
        if (planResult.type() == AgentMessage.Type.ERROR) {
            return "❌ 规划阶段失败：" + planResult.content();
        }

        List<ExecutionStep> steps = parsePlan(planResult.content());
        if (steps.isEmpty()) {
            return "❌ 规划失败：无法解析有效、无环且依赖完整的执行计划。";
        }

        out.println(AnsiStyle.heading("📋 执行计划"));
        out.println(summarizeSteps(steps));
        out.println(AnsiStyle.heading("⚡ 第二阶段：执行与审查"));

        Map<String, Integer> retryCount = new ConcurrentHashMap<>();
        int serialWorkerCursor = 0;
        int batchIndex = 0;
        while (!CancellationContext.isCancelled()) {
            List<ExecutionStep> ready = getExecutableSteps(steps);
            if (ready.isEmpty()) {
                break;
            }
            batchIndex++;
            if (ready.size() == 1) {
                ExecutionStep step = ready.get(0);
                SubAgent worker = workers.get(serialWorkerCursor++ % workers.size());
                runStep(step, steps, retryCount, worker, reviewer,
                        buildStepContext(steps, step), out);
                worker.clearHistory();
            } else {
                out.printf("⚡ 批次 #%d：%d 个独立步骤并行执行（最多 %d 个 Worker）%n%n",
                        batchIndex, ready.size(), workers.size());
                runBatchParallel(ready, steps, retryCount);
            }
        }

        if (CancellationContext.isCancelled()) {
            return "⏹️ 已取消 Multi-Agent 协作任务。\n" + buildFinalResult(steps);
        }
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.PENDING) {
                out.println("⏭️ 步骤 [" + step.id() + "] 因依赖未完成而跳过: " + step.description());
            }
        }
        return buildFinalResult(steps);
    }

    List<ExecutionStep> parsePlan(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            return List.of();
        }
        try {
            String cleaned = planJson.replaceAll("```json\\s*", "")
                    .replaceAll("```\\s*", "")
                    .trim();
            JsonNode root = JSON.readTree(cleaned);
            JsonNode nodes = root.path("steps");
            if (!nodes.isArray() || nodes.isEmpty()) {
                nodes = root.path("tasks");
            }
            if (!nodes.isArray() || nodes.isEmpty()) {
                return List.of();
            }

            Map<String, String> idMapping = new LinkedHashMap<>();
            List<JsonNode> rawNodes = new ArrayList<>();
            int index = 1;
            for (JsonNode node : nodes) {
                String originalId = node.path("id").asText().trim();
                String description = node.path("description").asText().trim();
                if (originalId.isEmpty() || description.isEmpty() || idMapping.containsKey(originalId)) {
                    return List.of();
                }
                idMapping.put(originalId, "step_" + index++);
                rawNodes.add(node);
            }

            List<ExecutionStep> steps = new ArrayList<>();
            for (JsonNode node : rawNodes) {
                String originalId = node.path("id").asText().trim();
                String mappedId = idMapping.get(originalId);
                String type = node.path("type").asText("ANALYSIS").trim().toUpperCase();
                LinkedHashSet<String> dependencies = new LinkedHashSet<>();
                JsonNode dependencyNodes = node.path("dependencies");
                if (dependencyNodes.isArray()) {
                    for (JsonNode dependencyNode : dependencyNodes) {
                        String dependency = idMapping.get(dependencyNode.asText().trim());
                        if (dependency == null || dependency.equals(mappedId)) {
                            return List.of();
                        }
                        dependencies.add(dependency);
                    }
                }
                steps.add(ExecutionStep.pending(mappedId,
                        node.path("description").asText().trim(), type, List.copyOf(dependencies)));
            }
            return hasCycle(steps) ? List.of() : steps;
        } catch (Exception e) {
            log.warn("Failed to parse Multi-Agent plan: {}", e.getMessage());
            return List.of();
        }
    }

    List<ExecutionStep> getExecutableSteps(List<ExecutionStep> steps) {
        Map<String, StepStatus> statuses = new HashMap<>();
        for (ExecutionStep step : steps) {
            statuses.put(step.id(), step.status());
        }
        return steps.stream()
                .filter(step -> step.status() == StepStatus.PENDING)
                .filter(step -> step.dependencies().stream()
                        .allMatch(dependency -> statuses.get(dependency) == StepStatus.COMPLETED))
                .toList();
    }

    boolean parseReviewApproval(String reviewContent) {
        if (reviewContent == null || reviewContent.isBlank()) {
            return false;
        }
        try {
            JsonNode root = JSON.readTree(stripCodeFence(reviewContent));
            JsonNode approved = root.path("approved");
            return !approved.isMissingNode() && !approved.isNull() && approved.asBoolean(false);
        } catch (Exception ignored) {
            String lower = reviewContent.toLowerCase();
            boolean negative = lower.contains("未通过") || lower.contains("不通过")
                    || lower.contains("不合格") || lower.contains("有问题")
                    || lower.contains("\"approved\": false") || lower.contains("\"approved\":false");
            boolean positive = lower.contains("通过") || lower.contains("合格")
                    || lower.contains("\"approved\": true") || lower.contains("\"approved\":true");
            return !negative && positive;
        }
    }

    String parseReviewIssues(String reviewContent) {
        if (reviewContent == null || reviewContent.isBlank()) {
            return "审查未返回具体问题";
        }
        try {
            JsonNode root = JSON.readTree(stripCodeFence(reviewContent));
            for (String field : List.of("issues", "suggestions")) {
                JsonNode values = root.path(field);
                if (values.isArray() && !values.isEmpty()) {
                    StringBuilder result = new StringBuilder();
                    values.forEach(value -> result.append("- ").append(value.asText()).append('\n'));
                    return result.toString().trim();
                }
            }
            String summary = root.path("summary").asText().trim();
            if (!summary.isEmpty()) {
                return summary;
            }
        } catch (Exception ignored) {
        }
        return "审查未通过，请改进执行结果";
    }

    public void clearHistory() {
        planner.clearHistory();
        reviewer.clearHistory();
        workers.forEach(SubAgent::clearHistory);
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    private void runBatchParallel(List<ExecutionStep> batch, List<ExecutionStep> steps,
                                  Map<String, Integer> retryCount) {
        int parallelism = Math.min(batch.size(), workers.size());
        ExecutorService executor = Executors.newFixedThreadPool(parallelism, runnable -> {
            Thread thread = new Thread(runnable, "jrepoflow-team-worker");
            thread.setDaemon(true);
            return thread;
        });
        BlockingQueue<SubAgent> workerPool = new LinkedBlockingQueue<>(workers);
        Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (ExecutionStep step : batch) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                buffers.put(step.id(), buffer);
                PrintStream stepOut = new PrintStream(buffer, true, StandardCharsets.UTF_8);
                String context = buildStepContext(steps, step);
                futures.add(executor.submit(() -> {
                    SubAgent worker = null;
                    SubAgent localReviewer = new SubAgent(
                            "reviewer-" + step.id(), AgentRole.REVIEWER, llmClient, toolRegistry);
                    try {
                        worker = workerPool.take();
                        runStep(step, steps, retryCount, worker, localReviewer, context, stepOut);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        updateStep(steps, step.id(), step.withFailed("并行执行被中断"));
                    } catch (RuntimeException e) {
                        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                        updateStep(steps, step.id(), step.withFailed("并行执行异常: " + message));
                        log.warn("Parallel team step {} failed", step.id(), e);
                    } finally {
                        if (worker != null) {
                            worker.clearHistory();
                            workerPool.offer(worker);
                        }
                        stepOut.flush();
                    }
                }));
            }

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    log.warn("Parallel team step future failed", e.getCause());
                }
            }
        } finally {
            executor.shutdownNow();
        }

        for (ExecutionStep step : batch) {
            ByteArrayOutputStream buffer = buffers.get(step.id());
            if (buffer != null && buffer.size() > 0) {
                out.print(buffer.toString(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    private void runStep(ExecutionStep step, List<ExecutionStep> steps,
                         Map<String, Integer> retryCount, SubAgent worker, SubAgent stepReviewer,
                         String context, PrintStream target) {
        updateStep(steps, step.id(), step.started());
        target.println("🛠️ " + worker.getName() + " 执行步骤 [" + step.id() + "]: " + step.description());
        AgentMessage task = AgentMessage.task("orchestrator", step.description());
        AgentMessage result = worker.executeWithContext(task, context, target);
        if (result.type() == AgentMessage.Type.ERROR || result.content() == null || result.content().isBlank()) {
            String error = result.content() == null || result.content().isBlank() ? "执行结果为空" : result.content();
            updateStep(steps, step.id(), step.withFailed(error));
            target.println("❌ 步骤 [" + step.id() + "] 执行失败：" + error + "\n");
            return;
        }

        String acceptedResult = result.content();
        target.println("🔍 " + stepReviewer.getName() + " 正在审查步骤 [" + step.id() + "]...");
        AgentMessage review = stepReviewer.review(step.description(), acceptedResult, target);
        stepReviewer.clearHistory();
        if (review.type() == AgentMessage.Type.ERROR) {
            target.println("⚠️ 审查调用失败，保留当前执行结果：" + review.content() + "\n");
            updateStep(steps, step.id(), step.withResult(acceptedResult));
            return;
        }

        boolean approved = parseReviewApproval(review.content());
        String issues = parseReviewIssues(review.content());
        int retries = retryCount.getOrDefault(step.id(), 0);
        while (!approved && retries < MAX_RETRIES_PER_STEP && !CancellationContext.isCancelled()) {
            retries++;
            retryCount.put(step.id(), retries);
            target.println("⚠️ 步骤 [" + step.id() + "] 审查未通过，开始第 " + retries + " 次重试");
            target.println("   反馈: " + issues + "\n");
            String feedbackContext = context + "\n\n之前的执行结果被审查拒绝，原因：\n" + issues;
            AgentMessage retryResult = worker.executeWithContext(task, feedbackContext, target);
            if (retryResult.type() == AgentMessage.Type.ERROR
                    || retryResult.content() == null || retryResult.content().isBlank()) {
                issues = retryResult.content() == null || retryResult.content().isBlank()
                        ? "重试结果为空" : retryResult.content();
                continue;
            }
            acceptedResult = retryResult.content();
            AgentMessage retryReview = stepReviewer.review(step.description(), acceptedResult, target);
            stepReviewer.clearHistory();
            if (retryReview.type() == AgentMessage.Type.ERROR) {
                target.println("⚠️ 重试结果审查调用失败，保留当前结果。\n");
                approved = true;
                break;
            }
            approved = parseReviewApproval(retryReview.content());
            issues = parseReviewIssues(retryReview.content());
        }

        updateStep(steps, step.id(), step.withResult(acceptedResult));
        if (approved) {
            target.println("✅ 步骤 [" + step.id() + "] 审查通过\n");
        } else {
            target.println("⚠️ 步骤 [" + step.id() + "] 超过最大重试次数，保留最后结果\n");
        }
    }

    private synchronized void updateStep(List<ExecutionStep> steps, String id, ExecutionStep updated) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(id)) {
                steps.set(i, updated);
                return;
            }
        }
    }

    private String buildStepContext(List<ExecutionStep> steps, ExecutionStep current) {
        StringBuilder context = new StringBuilder("总任务上下文：\n");
        for (ExecutionStep step : steps) {
            if (step.status() != StepStatus.COMPLETED || !current.dependencies().contains(step.id())) {
                continue;
            }
            context.append("已完成的依赖步骤 [").append(step.id()).append("]: ")
                    .append(step.description()).append('\n');
            if (step.result() != null && !step.result().isBlank()) {
                String preview = step.result().length() > 2_000
                        ? step.result().substring(0, 2_000) + "..." : step.result();
                context.append("结果：").append(preview).append('\n');
            }
        }
        return context.toString();
    }

    private String summarizeSteps(List<ExecutionStep> steps) {
        StringBuilder summary = new StringBuilder();
        for (ExecutionStep step : steps) {
            summary.append("  ⏳ [").append(step.id()).append("] ")
                    .append(step.description()).append(" (依赖: ")
                    .append(step.dependencies().isEmpty() ? "无" : String.join(", ", step.dependencies()))
                    .append(")\n");
        }
        return summary.toString();
    }

    private String buildFinalResult(List<ExecutionStep> steps) {
        boolean complete = steps.stream().allMatch(step -> step.status() == StepStatus.COMPLETED);
        boolean failed = steps.stream().anyMatch(step -> step.status() == StepStatus.FAILED);
        StringBuilder result = new StringBuilder();
        if (complete) {
            result.append("✅ Multi-Agent 协作任务完成！\n\n");
        } else if (failed) {
            result.append("⚠️ Multi-Agent 协作任务未完全完成，存在失败步骤。\n\n");
        } else {
            result.append("⚠️ Multi-Agent 协作任务部分完成，仍有未执行步骤。\n\n");
        }
        result.append("📋 执行总结：\n");
        for (ExecutionStep step : steps) {
            String icon = switch (step.status()) {
                case COMPLETED -> "✅";
                case FAILED -> "❌";
                default -> "⏳";
            };
            result.append('[').append(step.id()).append("] ").append(icon).append(' ')
                    .append(step.description()).append('\n');
            if (step.result() != null && !step.result().isBlank()) {
                String preview = step.result().length() > 200
                        ? step.result().substring(0, 200) + "..." : step.result();
                result.append("   结果：").append(preview).append('\n');
            }
        }
        return result.toString();
    }

    private boolean hasCycle(List<ExecutionStep> steps) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        for (ExecutionStep step : steps) {
            indegree.put(step.id(), step.dependencies().size());
            for (String dependency : step.dependencies()) {
                dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(step.id());
            }
        }
        Queue<String> ready = new ArrayDeque<>();
        indegree.forEach((id, degree) -> {
            if (degree == 0) {
                ready.add(id);
            }
        });
        Set<String> visited = new HashSet<>();
        while (!ready.isEmpty()) {
            String id = ready.remove();
            visited.add(id);
            for (String dependent : dependents.getOrDefault(id, List.of())) {
                int next = indegree.computeIfPresent(dependent, (ignored, value) -> value - 1);
                if (next == 0) {
                    ready.add(dependent);
                }
            }
        }
        return visited.size() != steps.size();
    }

    private String stripCodeFence(String value) {
        return value.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();
    }
}
