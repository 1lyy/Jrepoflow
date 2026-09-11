package io.jrepoflow.memory;

import io.jrepoflow.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AutoCompactionManagerTest {

    @Test
    void prefersReadySessionMemoryOverFullSummary() {
        StubFullCompactor full = new StubFullCompactor(true);
        StubSessionCompactor session = new StubSessionCompactor(true);
        AutoCompactionManager manager = new AutoCompactionManager(full, session);
        List<LlmClient.Message> history = largeHistory();

        AutoCompactionManager.Result result = manager.compactIfNeeded(history, 1);

        assertTrue(result.compacted());
        assertEquals(AutoCompactionManager.Strategy.SESSION_MEMORY, result.strategy());
        assertEquals(0, full.calls.get());
    }

    @Test
    void fallsBackToFullSummaryWhenSessionMemoryIsUnavailable() {
        StubFullCompactor full = new StubFullCompactor(true);
        StubSessionCompactor session = new StubSessionCompactor(false);
        AutoCompactionManager manager = new AutoCompactionManager(full, session);

        AutoCompactionManager.Result result = manager.compactIfNeeded(largeHistory(), 1);

        assertTrue(result.compacted());
        assertEquals(AutoCompactionManager.Strategy.FULL_SUMMARY, result.strategy());
        assertEquals(1, full.calls.get());
    }

    @Test
    void manualCompactAlwaysUsesFullSummary() {
        StubFullCompactor full = new StubFullCompactor(true);
        StubSessionCompactor session = new StubSessionCompactor(true);
        AutoCompactionManager manager = new AutoCompactionManager(full, session);

        AutoCompactionManager.Result result = manager.compactNow(largeHistory());

        assertEquals(AutoCompactionManager.Strategy.FULL_SUMMARY, result.strategy());
        assertEquals(1, full.manualCalls.get());
        assertEquals(0, session.compactCalls.get());
    }

    @Test
    void systemPropertyEnablesExperimentalPath() {
        String old = System.getProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY);
        try {
            System.setProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY, "true");
            assertTrue(AutoCompactionManager.sessionMemoryEnabledByConfiguration());
            System.setProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY, "false");
            assertFalse(AutoCompactionManager.sessionMemoryEnabledByConfiguration());
        } finally {
            if (old == null) System.clearProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY);
            else System.setProperty(AutoCompactionManager.SESSION_MEMORY_PROPERTY, old);
        }
    }

    private static List<LlmClient.Message> largeHistory() {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user("question"));
        history.add(LlmClient.Message.assistant("answer"));
        return history;
    }

    private static final class StubFullCompactor extends ConversationHistoryCompactor {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger manualCalls = new AtomicInteger();
        private final boolean result;

        private StubFullCompactor(boolean result) {
            super(null);
            this.result = result;
        }

        @Override
        public boolean compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
            calls.incrementAndGet();
            return result;
        }

        @Override
        public boolean compactNow(List<LlmClient.Message> history) {
            manualCalls.incrementAndGet();
            return result;
        }
    }

    private static final class StubSessionCompactor extends SessionMemoryCompactor {
        private final boolean compactResult;
        private final AtomicInteger compactCalls = new AtomicInteger();

        private StubSessionCompactor(boolean compactResult) {
            super(null, true);
            this.compactResult = compactResult;
        }

        @Override
        public boolean prepareIfNeeded(List<LlmClient.Message> history, int autoCompactTriggerTokens) {
            return true;
        }

        @Override
        public boolean compactIfReady(List<LlmClient.Message> history, int autoCompactTriggerTokens) {
            compactCalls.incrementAndGet();
            return compactResult;
        }
    }
}
