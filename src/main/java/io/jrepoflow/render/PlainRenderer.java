package io.jrepoflow.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jrepoflow.llm.LlmClient;
import io.jrepoflow.util.AnsiStyle;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只依赖标准输出的轻量终端渲染器。 */
public final class PlainRenderer implements Renderer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PrintStream out;

    public PlainRenderer() {
        this(System.out);
    }

    PlainRenderer(PrintStream out) {
        this.out = out;
    }

    @Override
    public void start() {
    }

    @Override
    public void close() {
        // System.out 的生命周期由进程管理。
    }

    @Override
    public PrintStream stream() {
        return out;
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return;

        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall call : toolCalls) {
            grouped.computeIfAbsent(call.function().name(), ignored -> new ArrayList<>()).add(call);
        }
        grouped.forEach((name, calls) -> {
            out.println(AnsiStyle.subtle("  " + toolLabel(name, calls.size())));
            for (LlmClient.ToolCall call : calls) {
                String detail = extractKeyParam(name, call.function().arguments());
                if (!detail.isEmpty()) out.println(AnsiStyle.subtle("    └ " + detail));
            }
        });
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        int beforeLength = before == null ? 0 : before.length();
        int afterLength = after == null ? 0 : after.length();
        out.printf("%s %d -> %d chars%n", filePath == null ? "(unnamed)" : filePath,
                beforeLength, afterLength);
    }

    @Override
    public void updateStatus(StatusInfo status) {
        // Plain 模式不额外刷状态行，避免打断流式正文。
    }

    private static String toolLabel(String name, int count) {
        return switch (name) {
            case "read_file" -> "读取文件 × " + count;
            case "write_file" -> "写入文件 × " + count;
            case "list_dir" -> "列出目录 × " + count;
            case "glob_files", "grep_code", "search_code" -> "检索代码 × " + count;
            case "execute_command" -> "执行命令 × " + count;
            case "create_project" -> "创建项目 × " + count;
            case "save_memory" -> "保存长期记忆 × " + count;
            default -> name != null && name.startsWith("mcp__")
                    ? "调用 MCP 工具 " + name.substring("mcp__".length()) + " × " + count
                    : String.valueOf(name) + " × " + count;
        };
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return "";
        try {
            JsonNode node = JSON.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "list_dir" -> "path";
                case "glob_files" -> "pattern";
                case "grep_code" -> "pattern";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code" -> "query";
                case "save_memory" -> "fact";
                default -> null;
            };
            String value = key == null ? argsJson : node.path(key).asText("");
            return value.length() > 80 ? value.substring(0, 77) + "..." : value;
        } catch (Exception ignored) {
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }
}
