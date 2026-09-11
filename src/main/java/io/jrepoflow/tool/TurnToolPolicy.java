package io.jrepoflow.tool;

import io.jrepoflow.llm.LlmClient;
import io.jrepoflow.tool.ToolRegistry.ToolExecutionResult;
import io.jrepoflow.tool.ToolRegistry.ToolInvocation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单轮工具边界。
 *
 * <p>每个 ReAct turn 和 DAG 分支持有独立实例，所有调用都从这里进入
 * {@link ToolRegistry}，便于后续增加分支级配额或审计规则。</p>
 */
public final class TurnToolPolicy {
    private TurnToolPolicy() {
    }

    public static TurnToolPolicy fromUserInput(String ignored) {
        return new TurnToolPolicy();
    }

    public static TurnToolPolicy forExplicitTask(String ignored) {
        return new TurnToolPolicy();
    }

    public TurnToolPolicy fork() {
        return new TurnToolPolicy();
    }

    public ToolExposure expose(List<LlmClient.Tool> definitions) {
        List<LlmClient.Tool> safe = definitions == null ? List.of() : List.copyOf(definitions);
        Set<String> names = new LinkedHashSet<>();
        safe.forEach(tool -> names.add(tool.name()));
        return new ToolExposure(safe, names);
    }

    public List<LlmClient.ToolCall> visibleToolCalls(List<LlmClient.ToolCall> calls,
                                                     ToolExposure ignored) {
        return calls == null ? List.of() : calls;
    }

    public List<ToolExecutionResult> execute(ToolRegistry registry,
                                             List<ToolInvocation> invocations,
                                             ToolExposure ignored) {
        return registry.executeTools(invocations);
    }

    public record ToolExposure(List<LlmClient.Tool> definitions, Set<String> advertisedNames) {
        public ToolExposure {
            definitions = definitions == null ? List.of() : List.copyOf(definitions);
            advertisedNames = advertisedNames == null ? Set.of() : Set.copyOf(advertisedNames);
        }

        public static ToolExposure none() {
            return new ToolExposure(List.of(), Set.of());
        }
    }
}
