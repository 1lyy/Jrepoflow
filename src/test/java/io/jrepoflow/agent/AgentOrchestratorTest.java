package io.jrepoflow.agent;

import io.jrepoflow.llm.LlmClient;
import io.jrepoflow.memory.LongTermMemory;
import io.jrepoflow.memory.MemoryManager;
import io.jrepoflow.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOrchestratorTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesAndValidatesDependencyPlans() {
        AgentOrchestrator orchestrator = orchestrator(new QueueLlmClient(List.of()));
        List<AgentOrchestrator.ExecutionStep> steps = orchestrator.parsePlan("""
                ```json
                {"steps": [
                  {"id":"a","description":"读取项目","type":"analysis","dependencies":[]},
                  {"id":"b","description":"修改代码","type":"command","dependencies":["a"]}
                ]}
                ```
                """);

        assertEquals(2, steps.size());
        assertEquals("step_1", steps.get(0).id());
        assertEquals("ANALYSIS", steps.get(0).type());
        assertEquals(List.of("step_1"), steps.get(1).dependencies());

        assertTrue(orchestrator.parsePlan("""
                {"steps":[
                  {"id":"a","description":"A","dependencies":["b"]},
                  {"id":"b","description":"B","dependencies":["a"]}
                ]}
                """).isEmpty());
        assertTrue(orchestrator.parsePlan("""
                {"steps":[{"id":"a","description":"A","dependencies":["missing"]}]}
                """).isEmpty());
    }

    @Test
    void onlyReturnsReadyPendingSteps() {
        AgentOrchestrator orchestrator = orchestrator(new QueueLlmClient(List.of()));
        List<AgentOrchestrator.ExecutionStep> steps = new ArrayList<>(List.of(
                AgentOrchestrator.ExecutionStep.pending("step_1", "A", "ANALYSIS", List.of()),
                AgentOrchestrator.ExecutionStep.pending("step_2", "B", "ANALYSIS", List.of("step_1")),
                AgentOrchestrator.ExecutionStep.pending("step_3", "C", "ANALYSIS", List.of())
        ));

        assertEquals(List.of("step_1", "step_3"), orchestrator.getExecutableSteps(steps).stream()
                .map(AgentOrchestrator.ExecutionStep::id).toList());
        steps.set(0, steps.get(0).withResult("done"));
        assertEquals(List.of("step_2", "step_3"), orchestrator.getExecutableSteps(steps).stream()
                .map(AgentOrchestrator.ExecutionStep::id).toList());
    }

    @Test
    void parsesStructuredAndPlainTextReviews() {
        AgentOrchestrator orchestrator = orchestrator(new QueueLlmClient(List.of()));
        assertTrue(orchestrator.parseReviewApproval(
                "{\"approved\":true,\"summary\":\"通过\",\"issues\":[]}"));
        assertFalse(orchestrator.parseReviewApproval(
                "{\"approved\":false,\"summary\":\"未通过\",\"issues\":[\"缺少测试\"]}"));
        assertTrue(orchestrator.parseReviewApproval("审查通过，质量良好"));
        assertFalse(orchestrator.parseReviewApproval("结果不合格"));
        assertTrue(orchestrator.parseReviewIssues("""
                {"approved":false,"summary":"待修改","issues":["缺少错误处理","缺少测试"]}
                """).contains("缺少错误处理"));
    }

    @Test
    void retriesRejectedStepUntilApproved() {
        QueueLlmClient client = new QueueLlmClient(List.of(
                response("""
                        {"steps":[{"id":"s1","description":"执行任务","dependencies":[]}]}
                        """),
                response("第一次执行结果"),
                response("{\"approved\":false,\"issues\":[\"补充细节\"]}"),
                response("第二次执行结果"),
                response("{\"approved\":false,\"issues\":[\"补充结论\"]}"),
                response("第三次执行结果"),
                response("{\"approved\":true,\"summary\":\"通过\",\"issues\":[]}")));

        String result = orchestrator(client).run("测试审查重试");

        assertTrue(result.contains("第三次执行结果"));
        assertEquals(7, client.calls());
    }

    @Test
    void runsIndependentReadyStepsInParallel() {
        CountDownLatch workersInFlight = new CountDownLatch(2);
        AtomicInteger current = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        DispatchingLlmClient client = new DispatchingLlmClient(message -> {
            if (message.contains("请为以下任务制定执行计划")) {
                return response("""
                        {"steps":[
                          {"id":"a","description":"任务A","dependencies":[]},
                          {"id":"b","description":"任务B","dependencies":[]}
                        ]}
                        """);
            }
            if (message.contains("原始任务")) {
                return response("{\"approved\":true,\"summary\":\"通过\",\"issues\":[]}");
            }
            if (message.contains("任务A") || message.contains("任务B")) {
                int active = current.incrementAndGet();
                peak.accumulateAndGet(active, Math::max);
                workersInFlight.countDown();
                try {
                    assertTrue(workersInFlight.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    current.decrementAndGet();
                }
                return response(message.contains("任务A") ? "A完成" : "B完成");
            }
            return response("fallback");
        });

        String result = orchestrator(client).run("测试并行");

        assertTrue(result.contains("任务A"));
        assertTrue(result.contains("任务B"));
        assertEquals(2, peak.get());
    }

    private AgentOrchestrator orchestrator(LlmClient client) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(tempDir.toString());
        MemoryManager memory = new MemoryManager(
                client, 32_768, 128_000, new LongTermMemory(tempDir.toFile()));
        return new AgentOrchestrator(client, registry, memory,
                new PrintStream(new ByteArrayOutputStream()), 2);
    }

    private static LlmClient.ChatResponse response(String content) {
        return new LlmClient.ChatResponse("assistant", content, null, 10, 5);
    }

    private abstract static class StubLlmClient implements LlmClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public String getModelName() {
            return "stub";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        static String lastUserMessage(List<Message> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("user".equals(messages.get(i).role())) {
                    return messages.get(i).content();
                }
            }
            return "";
        }
    }

    private static final class QueueLlmClient extends StubLlmClient {
        private final Queue<ChatResponse> responses;
        private final AtomicInteger calls = new AtomicInteger();

        private QueueLlmClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public synchronized ChatResponse chat(List<Message> messages, List<Tool> tools,
                                              StreamListener listener) throws IOException {
            calls.incrementAndGet();
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("缺少预设响应");
            }
            return response;
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class DispatchingLlmClient extends StubLlmClient {
        private final Function<String, ChatResponse> dispatcher;

        private DispatchingLlmClient(Function<String, ChatResponse> dispatcher) {
            this.dispatcher = dispatcher;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return dispatcher.apply(lastUserMessage(messages));
        }
    }
}
