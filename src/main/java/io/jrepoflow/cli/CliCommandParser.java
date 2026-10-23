package io.jrepoflow.cli;

/** 将终端输入解析成有限命令集；未识别的斜杠命令不会回退给模型。 */
final class CliCommandParser {
    enum CommandType {
        NONE, UNKNOWN_COMMAND, HELP, EXIT, CLEAR, COMPACT, PLAN, TEAM,
        MEMORY_STATUS, MEMORY_CLEAR, MEMORY_LIST, MEMORY_DELETE, MEMORY_SEARCH, MEMORY_SAVE,
        INDEX, SEARCH, GRAPH, CONTEXT_STATUS, POLICY_STATUS, AUDIT_TAIL,
        MCP_LIST, MCP_RESTART, MCP_LOGS, MCP_DISABLE, MCP_ENABLE
    }

    record ParsedCommand(CommandType type, String payload) {
        static ParsedCommand none() {
            return new ParsedCommand(CommandType.NONE, null);
        }
    }

    private CliCommandParser() {
    }

    static ParsedCommand parse(String input) {
        if (input == null || input.trim().isEmpty()) {
            return ParsedCommand.none();
        }
        String value = input.trim();
        if (!value.startsWith("/")) {
            return new ParsedCommand(CommandType.NONE, value);
        }

        String[] parts = value.split("\\s+", 2);
        String name = parts[0].toLowerCase();
        String payload = parts.length == 2 ? parts[1].trim() : null;
        return switch (name) {
            case "/help" -> new ParsedCommand(CommandType.HELP, null);
            case "/exit", "/quit" -> new ParsedCommand(CommandType.EXIT, null);
            case "/clear" -> new ParsedCommand(CommandType.CLEAR, null);
            case "/compact" -> new ParsedCommand(CommandType.COMPACT, null);
            case "/plan" -> new ParsedCommand(CommandType.PLAN, payload);
            case "/team" -> new ParsedCommand(CommandType.TEAM, payload);
            case "/context", "/ctx" -> new ParsedCommand(CommandType.CONTEXT_STATUS, null);
            case "/save" -> new ParsedCommand(CommandType.MEMORY_SAVE, payload);
            case "/index" -> new ParsedCommand(CommandType.INDEX, payload);
            case "/search" -> new ParsedCommand(CommandType.SEARCH, payload);
            case "/graph" -> new ParsedCommand(CommandType.GRAPH, payload);
            case "/policy" -> new ParsedCommand(CommandType.POLICY_STATUS, null);
            case "/audit" -> new ParsedCommand(CommandType.AUDIT_TAIL, payload);
            case "/memory", "/mem" -> parseMemory(payload);
            case "/mcp" -> parseMcp(payload);
            default -> new ParsedCommand(CommandType.UNKNOWN_COMMAND, value);
        };
    }

    private static ParsedCommand parseMemory(String payload) {
        if (payload == null || payload.isBlank()) {
            return new ParsedCommand(CommandType.MEMORY_STATUS, null);
        }
        String[] parts = payload.split("\\s+", 2);
        String argument = parts.length == 2 ? parts[1].trim() : null;
        return switch (parts[0].toLowerCase()) {
            case "list" -> new ParsedCommand(CommandType.MEMORY_LIST, null);
            case "clear" -> new ParsedCommand(CommandType.MEMORY_CLEAR, null);
            case "search" -> new ParsedCommand(CommandType.MEMORY_SEARCH, argument);
            case "delete" -> new ParsedCommand(CommandType.MEMORY_DELETE, argument);
            default -> new ParsedCommand(CommandType.UNKNOWN_COMMAND, payload);
        };
    }

    private static ParsedCommand parseMcp(String payload) {
        if (payload == null || payload.isBlank()) {
            return new ParsedCommand(CommandType.MCP_LIST, null);
        }
        String[] parts = payload.split("\\s+", 2);
        String serverName = parts.length == 2 ? parts[1].trim() : null;
        return switch (parts[0].toLowerCase()) {
            case "restart" -> new ParsedCommand(CommandType.MCP_RESTART, serverName);
            case "logs" -> new ParsedCommand(CommandType.MCP_LOGS, serverName);
            case "disable" -> new ParsedCommand(CommandType.MCP_DISABLE, serverName);
            case "enable" -> new ParsedCommand(CommandType.MCP_ENABLE, serverName);
            default -> new ParsedCommand(CommandType.UNKNOWN_COMMAND, payload);
        };
    }
}
