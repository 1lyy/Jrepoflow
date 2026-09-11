package io.jrepoflow.memory;

import io.jrepoflow.llm.LlmClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * 自动压缩协调器：优先使用实验性的会话记忆摘要，失败后回退到完整对话摘要。
 */
public class AutoCompactionManager {
    public static final String SESSION_MEMORY_PROPERTY = "jrepoflow.compaction.session-memory.enabled";
    public static final String SESSION_MEMORY_ENV = "JREPOFLOW_SESSION_MEMORY_COMPACTION_ENABLED";

    private final ConversationHistoryCompactor fullCompactor;
    private final SessionMemoryCompactor sessionMemoryCompactor;

    public AutoCompactionManager(LlmClient llmClient) {
        this(llmClient, sessionMemoryEnabledByConfiguration());
    }

    AutoCompactionManager(LlmClient llmClient, boolean sessionMemoryEnabled) {
        this(new ConversationHistoryCompactor(llmClient),
                new SessionMemoryCompactor(llmClient, sessionMemoryEnabled));
    }

    AutoCompactionManager(
            ConversationHistoryCompactor fullCompactor,
            SessionMemoryCompactor sessionMemoryCompactor) {
        this.fullCompactor = fullCompactor;
        this.sessionMemoryCompactor = sessionMemoryCompactor;
    }

    public void setLlmClient(LlmClient llmClient) {
        fullCompactor.setLlmClient(llmClient);
        sessionMemoryCompactor.setLlmClient(llmClient);
    }

    public Result compactIfNeeded(List<LlmClient.Message> history, int triggerTokens) {
        if (triggerTokens <= 0) return Result.none();
        // 阈值前异步预计算增量摘要，真正越线时可以直接替换早期消息，避免阻塞长任务。
        sessionMemoryCompactor.prepareIfNeeded(history, triggerTokens);
        if (TokenBudget.estimateMessagesTokens(history) < triggerTokens) {
            return Result.none();
        }
        if (sessionMemoryCompactor.compactIfReady(history, triggerTokens)) {
            return new Result(true, Strategy.SESSION_MEMORY);
        }
        // 增量摘要可能尚未完成、已失效或压缩收益不足；完整摘要是确定性的兜底路径。
        boolean compacted = fullCompactor.compactIfNeeded(history, triggerTokens);
        if (compacted) {
            sessionMemoryCompactor.clear(history);
            return new Result(true, Strategy.FULL_SUMMARY);
        }
        return Result.none();
    }

    /** 手动 /compact 始终使用稳定的完整摘要路径。 */
    public Result compactNow(List<LlmClient.Message> history) {
        sessionMemoryCompactor.clear(history);
        boolean compacted = fullCompactor.compactNow(history);
        return compacted
                ? new Result(true, Strategy.FULL_SUMMARY)
                : Result.none();
    }

    public void clear(List<LlmClient.Message> history) {
        sessionMemoryCompactor.clear(history);
    }

    public boolean isSessionMemoryEnabled() {
        return sessionMemoryCompactor.isEnabled();
    }

    public enum Strategy {
        NONE,
        SESSION_MEMORY,
        FULL_SUMMARY
    }

    public record Result(boolean compacted, Strategy strategy) {
        static Result none() {
            return new Result(false, Strategy.NONE);
        }
    }

    static boolean sessionMemoryEnabledByConfiguration() {
        String property = System.getProperty(SESSION_MEMORY_PROPERTY);
        if (property != null) return truthy(property);
        String env = System.getenv(SESSION_MEMORY_ENV);
        if (env != null) return truthy(env);
        String projectDotEnv = readDotEnv(new File(".env"), SESSION_MEMORY_ENV);
        if (projectDotEnv != null) return truthy(projectDotEnv);
        return truthy(readDotEnv(new File(System.getProperty("user.home"), ".env"), SESSION_MEMORY_ENV));
    }

    private static boolean truthy(String value) {
        if (value == null) return false;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "1".equals(normalized) || "true".equals(normalized)
                || "yes".equals(normalized) || "on".equals(normalized);
    }

    private static String readDotEnv(File file, String key) {
        if (file == null || !file.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (trimmed.startsWith(key + "=")) {
                    return trimmed.substring(key.length() + 1).trim();
                }
            }
        } catch (IOException ignored) {
            // 配置读取失败时保持默认关闭，完整摘要路径仍然可用。
        }
        return null;
    }
}
