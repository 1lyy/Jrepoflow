package io.jrepoflow.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CliCommandParserTest {
    @Test
    void plainTextRemainsAgentInput() {
        assertEquals(CliCommandParser.CommandType.NONE, CliCommandParser.parse("解释这段代码").type());
    }

    @Test
    void parsesCoreCommandsAndPayloads() {
        assertCommand("/plan 重构订单服务", CliCommandParser.CommandType.PLAN, "重构订单服务");
        assertCommand("/team 重构订单服务", CliCommandParser.CommandType.TEAM, "重构订单服务");
        assertCommand("/index src", CliCommandParser.CommandType.INDEX, "src");
        assertCommand("/search 用户登录", CliCommandParser.CommandType.SEARCH, "用户登录");
        assertCommand("/graph UserService", CliCommandParser.CommandType.GRAPH, "UserService");
        assertCommand("/save 使用 Java 17", CliCommandParser.CommandType.MEMORY_SAVE, "使用 Java 17");
    }

    @Test
    void parsesMemorySubcommands() {
        assertCommand("/memory", CliCommandParser.CommandType.MEMORY_STATUS, null);
        assertCommand("/memory list", CliCommandParser.CommandType.MEMORY_LIST, null);
        assertCommand("/memory search 数据库", CliCommandParser.CommandType.MEMORY_SEARCH, "数据库");
        assertCommand("/memory delete abc", CliCommandParser.CommandType.MEMORY_DELETE, "abc");
        assertCommand("/memory clear", CliCommandParser.CommandType.MEMORY_CLEAR, null);
    }

    @Test
    void parsesMcpSubcommands() {
        assertCommand("/mcp", CliCommandParser.CommandType.MCP_LIST, null);
        assertCommand("/mcp restart filesystem", CliCommandParser.CommandType.MCP_RESTART, "filesystem");
        assertCommand("/mcp logs filesystem", CliCommandParser.CommandType.MCP_LOGS, "filesystem");
        assertCommand("/mcp disable filesystem", CliCommandParser.CommandType.MCP_DISABLE, "filesystem");
        assertCommand("/mcp enable filesystem", CliCommandParser.CommandType.MCP_ENABLE, "filesystem");
    }

    @Test
    void unknownSlashCommandDoesNotReachAgent() {
        assertEquals(CliCommandParser.CommandType.UNKNOWN_COMMAND,
                CliCommandParser.parse("/unknown value").type());
    }

    private static void assertCommand(String input, CliCommandParser.CommandType type, String payload) {
        CliCommandParser.ParsedCommand result = CliCommandParser.parse(input);
        assertEquals(type, result.type());
        assertEquals(payload, result.payload());
    }
}
