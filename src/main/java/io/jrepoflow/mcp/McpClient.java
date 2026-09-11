package io.jrepoflow.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jrepoflow.mcp.jsonrpc.JsonRpcClient;
import io.jrepoflow.mcp.protocol.McpCallToolRequest;
import io.jrepoflow.mcp.protocol.McpCallToolResult;
import io.jrepoflow.mcp.protocol.McpInitializeRequest;
import io.jrepoflow.mcp.protocol.McpSchemaSanitizer;
import io.jrepoflow.mcp.protocol.McpToolDescriptor;
import io.jrepoflow.mcp.transport.McpTransport;
import io.jrepoflow.tool.ToolOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP 客户端核心：初始化握手、工具发现和工具调用。
 *
 * <p>协议层只依赖 JSON-RPC，不感知具体传输；stdio 与 Streamable HTTP 由
 * {@link McpTransport} 的不同实现提供。</p>
 */
public final class McpClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_INITIALIZE_TIMEOUT_SECONDS = 60;
    private static final String INITIALIZE_TIMEOUT_PROPERTY = "jrepoflow.mcp.initialize.timeout.seconds";
    private static final String INITIALIZE_TIMEOUT_ENV = "JREPOFLOW_MCP_INITIALIZE_TIMEOUT_SECONDS";

    private final String serverName;
    private final JsonRpcClient rpc;
    private final McpTransport transport;

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
        this.rpc = new JsonRpcClient(transport);
    }

    public void initialize() throws IOException {
        rpc.request("initialize", McpInitializeRequest.toJson(), initializeTimeoutSeconds());
        rpc.sendNotification("notifications/initialized", JsonNodeFactory.instance.objectNode());
    }

    static int initializeTimeoutSeconds() {
        String configured = System.getProperty(INITIALIZE_TIMEOUT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(INITIALIZE_TIMEOUT_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        }
        try {
            int seconds = Integer.parseInt(configured.trim());
            return seconds > 0 ? seconds : DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        }
    }

    public List<McpToolDescriptor> listTools() throws IOException {
        JsonNode result = rpc.request("tools/list", JsonNodeFactory.instance.objectNode(), 30);
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) {
            return List.of();
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            descriptors.add(new McpToolDescriptor(
                    serverName,
                    name,
                    McpToolDescriptor.namespaced(serverName, name),
                    tool.path("description").asText(""),
                    McpSchemaSanitizer.sanitize(tool.path("inputSchema"))));
        }
        return descriptors;
    }

    public String callTool(String toolName, String argumentsJson) throws IOException {
        return callToolOutput(toolName, argumentsJson).text();
    }

    public ToolOutput callToolOutput(String toolName, String argumentsJson) throws IOException {
        JsonNode args = argumentsJson == null || argumentsJson.isBlank()
                ? JsonNodeFactory.instance.objectNode()
                : MAPPER.readTree(argumentsJson);
        ObjectNode params = McpCallToolRequest.toJson(toolName, args);
        JsonNode result = rpc.request("tools/call", params, 60);
        McpCallToolResult callResult = MAPPER.treeToValue(result, McpCallToolResult.class);
        ToolOutput output = callResult.toToolOutput();
        return callResult.isError()
                ? ToolOutput.failure("MCP 工具返回错误: " + output.text())
                : output;
    }

    public List<String> stderrLines() {
        return transport.stderrLines();
    }

    public Long processId() {
        return transport.processId();
    }

    public String transportName() {
        return transport.transportName();
    }

    @Override
    public void close() {
        rpc.close();
    }
}
