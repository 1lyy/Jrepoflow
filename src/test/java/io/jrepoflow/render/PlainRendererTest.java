package io.jrepoflow.render;

import io.jrepoflow.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainRendererTest {
    @Test
    void groupsToolCallsAndPrintsKeyParameter() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PlainRenderer renderer = new PlainRenderer(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        renderer.appendToolCalls(List.of(
                call("one", "read_file", "{\"path\":\"README.md\"}"),
                call("two", "read_file", "{\"path\":\"pom.xml\"}")));

        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("读取文件 × 2"));
        assertTrue(output.contains("README.md"));
        assertTrue(output.contains("pom.xml"));
    }

    @Test
    void formatsMcpToolName() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PlainRenderer renderer = new PlainRenderer(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        renderer.appendToolCalls(List.of(call("one", "mcp__demo__echo", "{}")));

        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("调用 MCP 工具 demo__echo"));
    }

    private static LlmClient.ToolCall call(String id, String name, String args) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, args));
    }
}
