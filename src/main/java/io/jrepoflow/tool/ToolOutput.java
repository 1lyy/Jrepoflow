package io.jrepoflow.tool;

/**
 * 工具执行的类型化结果。
 *
 * <p>成功标记与文本分离，避免通过匹配错误文案来决定任务状态。</p>
 */
public record ToolOutput(String text, boolean successful) {
    public ToolOutput {
        text = text == null ? "" : text;
    }

    public static ToolOutput text(String text) {
        return new ToolOutput(text, true);
    }

    public static ToolOutput failure(String text) {
        return new ToolOutput(text, false);
    }
}
