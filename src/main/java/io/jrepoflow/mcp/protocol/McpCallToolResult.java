package io.jrepoflow.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.jrepoflow.tool.ToolOutput;

import java.util.List;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
public record McpCallToolResult(List<McpContent> content, boolean isError) {
    public String formatForLlm() {
        return toToolOutput().text();
    }

    public ToolOutput toToolOutput() {
        if (content == null || content.isEmpty()) {
            return isError
                    ? ToolOutput.failure("MCP 工具返回错误，但没有错误正文")
                    : ToolOutput.text("");
        }
        String text = content.stream()
                .map(item -> {
                    String type = item.type() == null || item.type().isBlank() ? "text" : item.type();
                    if ("text".equals(type)) {
                        return item.text() == null ? "" : item.text();
                    }
                    if ("image".equals(type)) {
                        int size = item.data() == null ? 0 : item.data().length();
                        return "[MCP 工具返回图片内容，精简版不处理图片附件：mimeType="
                                + item.mimeType() + ", base64Length=" + size + "]";
                    }
                    return "[此工具返回了 " + type + "，请向用户描述结果]";
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));
        return new ToolOutput(text, !isError);
    }
}
