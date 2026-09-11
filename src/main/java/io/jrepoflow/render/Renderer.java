package io.jrepoflow.render;

import io.jrepoflow.llm.LlmClient;

import java.io.PrintStream;
import java.util.List;

/**
 * 终端渲染器抽象。
 *
 * <p>把流式输出、工具调用、审批和状态信息收口到一个接口，
 * 使 Agent 不依赖具体终端实现。
 *
 * <p>线程模型：所有方法应在调用方线程同步返回；
 * 异步实现需要自行保证输出顺序。
 */
public interface Renderer extends AutoCloseable {

    /** 启动渲染器（例如设置滚动区域、启动 GUI 主循环）。Main 必须先调用一次。 */
    void start();

    /** 开始一次用户任务输出。 */
    default void beginTurn() {
    }

    /** 进入用户输入前。 */
    default void beforeInput() {
    }

    /** 用户输入结束后。 */
    default void afterInput() {
    }

    /** 当前渲染器是否支持独立的模型思考面板。 */
    default boolean supportsThinkingPanel() {
        return false;
    }

    /** 当前渲染器是否应该把 reasoning/thinking 内容展示给用户。 */
    default boolean rendersReasoning() {
        return true;
    }

    /** 开始显示模型思考面板。plain renderer 保持 no-op，继续用正文流式输出。 */
    default void beginThinking(String label) {
    }

    /** 追加模型 reasoning delta 到思考面板。 */
    default void appendThinking(String delta) {
    }

    /** 结束并清理模型思考面板。 */
    default void endThinking() {
    }

    /** 追加 assistant 正文 delta。普通终端渲染器忽略；远程通道可用它做正文分片推送。 */
    default void appendAssistantContentDelta(String delta) {
    }

    /** assistant 正文流结束。普通终端渲染器忽略；远程通道可用它 flush 剩余正文。 */
    default void finishAssistantContent() {
    }

    /** 当前渲染器是否支持通用临时活动面板。 */
    default boolean supportsActivityPanel() {
        return supportsThinkingPanel();
    }

    /** 开始显示通用临时活动面板，用于非 Agent 思考的阻塞操作。 */
    default void beginActivity(String label, String detail) {
        beginThinking(label);
        if (detail != null && !detail.isBlank()) {
            appendThinking(detail);
        }
    }

    /**
     * 开始通用活动面板，并声明任务是否可由用户取消。
     *
     * <p>旧渲染器可忽略 cancelable，继续使用原活动面板行为。
     */
    default void beginActivity(String label, String detail, boolean cancelable) {
        beginActivity(label, detail);
    }

    /**
     * 更新活动面板中的当前阶段和确定性进度，不重置累计耗时。
     */
    default void updateActivity(String detail, int completed, int total) {
        if (detail != null && !detail.isBlank()) {
            appendThinking("\n" + detail);
        }
    }

    /** 结束并清理通用临时活动面板。 */
    default void endActivity() {
        endThinking();
    }

    /** 当前渲染器希望 LineReader 使用的左侧输入提示。 */
    default String inputPrompt() {
        return "> ";
    }

    /** 当前渲染器希望 LineReader 使用的右侧提示；返回 null 表示不显示。 */
    default String inputRightPrompt() {
        return null;
    }

    @Override
    void close();

    /**
     * 流式输出的目标 PrintStream。
     *
     * <p>Agent 和 PlanExecuteAgent 把流式 reasoning / content 写到这里。
     */
    PrintStream stream();

    /** 当前输出区域可用列数。Markdown 表格等宽布局按这个值做收缩和换行。 */
    default int terminalColumns() {
        return 120;
    }

    /**
     * 渲染一组工具调用的标签和关键参数。
     *
     * <p>PlainRenderer 将调用分组并打印关键参数。
     */
    void appendToolCalls(List<LlmClient.ToolCall> toolCalls);

    /**
     * 渲染一个文件 diff 块。
     *
     * @param filePath 文件路径
     * @param before   修改前内容（null 表示新建）
     * @param after    修改后内容（null 表示删除）
     */
    void appendDiff(String filePath, String before, String after);

    /** 更新执行状态。允许频繁调用，渲染器内部自行节流。 */
    void updateStatus(StatusInfo status);
}
