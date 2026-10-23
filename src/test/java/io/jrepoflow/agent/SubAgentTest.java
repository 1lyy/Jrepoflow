package io.jrepoflow.agent;

import io.jrepoflow.llm.LlmClient;
import io.jrepoflow.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubAgentTest {
    @Test
    void onlyWorkerUsesToolsWhenModelSupportsThem() {
        LlmClient client = new StubLlmClient(true);
        ToolRegistry registry = new ToolRegistry();

        assertFalse(new SubAgent("planner", AgentRole.PLANNER, client, registry).usesTools());
        assertTrue(new SubAgent("worker", AgentRole.WORKER, client, registry).usesTools());
        assertFalse(new SubAgent("reviewer", AgentRole.REVIEWER, client, registry).usesTools());
        assertFalse(new SubAgent("worker", AgentRole.WORKER,
                new StubLlmClient(false), registry).usesTools());
    }

    private static final class StubLlmClient implements LlmClient {
        private final boolean supportsTools;

        private StubLlmClient(boolean supportsTools) {
            this.supportsTools = supportsTools;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return new ChatResponse("assistant", "完成", null, 1, 1);
        }

        @Override
        public String getModelName() {
            return "stub";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public boolean supportsTools() {
            return supportsTools;
        }
    }
}
