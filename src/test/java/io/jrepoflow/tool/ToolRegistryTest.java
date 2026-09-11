package io.jrepoflow.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.jrepoflow.mcp.protocol.McpToolDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {
    @Test
    void exposesDefinitionsInStableOrder() {
        ToolRegistry registry = new ToolRegistry();
        List<String> names = registry.getToolDefinitions().stream().map(tool -> tool.name()).toList();
        assertEquals(names.stream().sorted().toList(), names);
    }

    @Test
    void unknownToolReturnsTypedFailure() {
        ToolRegistry registry = new ToolRegistry();
        ToolOutput output = registry.executeToolOutput("missing", "{}");
        assertFalse(output.successful());
        assertTrue(output.text().contains("未知工具"));
    }

    @Test
    void readsOnlyRequestedLines(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Sample.java"), "one\ntwo\nthree\nfour");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(root.toString());

        String result = registry.executeTool("read_file",
                "{\"path\":\"Sample.java\",\"offset\":2,\"limit\":2}");

        assertTrue(result.contains("lines 2-3 of 4"));
        assertTrue(result.contains("2 | two"));
        assertFalse(result.contains("1 | one"));
    }

    @Test
    void globAndGrepStayInsideProject(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/service"));
        Files.writeString(root.resolve("src/service/UserService.java"),
                "class UserService { void findUser() {} }");
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(root.toString());

        String glob = registry.executeTool("glob_files", "{\"pattern\":\"**/*Service.java\"}");
        String grep = registry.executeTool("grep_code", "{\"pattern\":\"findUser\",\"glob\":\"**/*.java\"}");

        assertTrue(glob.contains("src/service/UserService.java"));
        assertTrue(grep.contains("src/service/UserService.java:1"));
    }

    @Test
    void rejectsPathEscape(@TempDir Path root) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(root.toString());
        String result = registry.executeTool("read_file", "{\"path\":\"../secret.txt\"}");
        assertTrue(result.contains("策略拒绝"));
    }

    @Test
    void removesCredentialsFromChildEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("PATH", "/usr/bin");
        environment.put("JAVA_HOME", "/jdk");
        environment.put("DEEPSEEK_API_KEY", "secret");
        environment.put("DATABASE_PASSWORD", "secret");

        ToolRegistry.removeSensitiveCommandEnvironment(environment);

        assertEquals(Map.of("PATH", "/usr/bin", "JAVA_HOME", "/jdk"), environment);
    }

    @Test
    void passesMemoryScopeToSaver() {
        ToolRegistry registry = new ToolRegistry();
        StringBuilder captured = new StringBuilder();
        registry.setScopedMemorySaver((fact, scope) -> captured.append(scope).append(':').append(fact));

        String result = registry.executeTool("save_memory",
                "{\"fact\":\"使用 Java 17\",\"scope\":\"project\"}");

        assertTrue(result.contains("已保存"));
        assertEquals("project:使用 Java 17", captured.toString());
    }

    @Test
    void executesIndependentToolsInParallelAndKeepsInputOrder() {
        ToolRegistry registry = new ToolRegistry(60, 5);
        registerSlowTool(registry, "first", 250);
        registerSlowTool(registry, "second", 250);
        long started = System.nanoTime();

        List<ToolRegistry.ToolExecutionResult> results = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("1", "mcp__test__first", "{}"),
                new ToolRegistry.ToolInvocation("2", "mcp__test__second", "{}")));

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertEquals(List.of("1", "2"), results.stream().map(ToolRegistry.ToolExecutionResult::id).toList());
        assertTrue(elapsedMs < 450, "两个 250ms 工具应并行完成，实际 " + elapsedMs + "ms");
    }

    private static void registerSlowTool(ToolRegistry registry, String name, long millis) {
        McpToolDescriptor descriptor = new McpToolDescriptor(
                "test", name, "mcp__test__" + name, "slow test tool",
                JsonNodeFactory.instance.objectNode());
        registry.registerMcpTool(descriptor, ignored -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return name;
        });
    }
}
