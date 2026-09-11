package io.jrepoflow.render;

/** Agent 执行状态的不可变快照。 */
public record StatusInfo(
        String model,
        long totalTokens,
        long contextWindow,
        long inputTokens,
        long outputTokens,
        long cachedInputTokens,
        String estimatedCost,
        long elapsedMillis,
        String phase,
        String mcpSummary
) {
    public static StatusInfo idle(String model, long contextWindow, long contextTokens) {
        return new StatusInfo(model, Math.max(0L, contextTokens), contextWindow,
                0L, 0L, 0L, null, 0L, "idle", null);
    }

    public static StatusInfo active(String model, long contextWindow, long contextTokens, String phase) {
        return new StatusInfo(model, Math.max(0L, contextTokens), contextWindow,
                0L, 0L, 0L, null, 0L, normalizePhase(phase), null);
    }

    public static StatusInfo tokens(String model,
                                    long contextWindow,
                                    long contextTokens,
                                    long inputTokens,
                                    long outputTokens,
                                    long cachedInputTokens,
                                    String estimatedCost,
                                    long elapsedMillis,
                                    String phase) {
        return new StatusInfo(model, Math.max(0L, contextTokens), contextWindow,
                Math.max(0L, inputTokens), Math.max(0L, outputTokens),
                Math.max(0L, cachedInputTokens), estimatedCost,
                Math.max(0L, elapsedMillis), normalizePhase(phase), null);
    }

    public StatusInfo withEnvironment(String mcpSummary) {
        return new StatusInfo(model, totalTokens, contextWindow, inputTokens, outputTokens,
                cachedInputTokens, estimatedCost, elapsedMillis, phase,
                mcpSummary == null || mcpSummary.isBlank() ? null : mcpSummary.trim());
    }

    private static String normalizePhase(String phase) {
        return phase == null || phase.isBlank() ? "running" : phase;
    }
}
