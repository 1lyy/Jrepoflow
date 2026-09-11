package io.jrepoflow.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderBenchmarkCompatibilityTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void hunyuanDefaultsToHy4PreviewOnTokenHub() {
        HunyuanClient client = new HunyuanClient("test-key");

        assertEquals("hy4-preview", client.getModelName());
        assertEquals("https://tokenhub.tencentmaas.com/v1/chat/completions", client.getApiUrl());
        assertEquals("hunyuan", client.getProviderName());
    }

    @Test
    void deepSeekV4UsesHighReasoningBenchmarkSettings() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            enqueueTextResponse(server);
            DeepSeekClient client = new DeepSeekClient(
                    "test-key", "deepseek-v4-flash", server.url("/chat/completions").toString());

            client.chat(List.of(LlmClient.Message.user("你好")), null);

            JsonNode root = requestBody(server.takeRequest());
            assertEquals("deepseek-v4-flash", root.path("model").asText());
            assertEquals(1.0, root.path("temperature").asDouble());
            assertEquals(0.95, root.path("top_p").asDouble());
            assertEquals("max", root.path("reasoning_effort").asText());
            assertEquals("enabled", root.path("thinking").path("type").asText());
            assertEquals(1_000_000, client.maxContextWindow());
        }
    }

    @Test
    void olderDeepSeekModelDoesNotReceiveV4OnlyBenchmarkSettings() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            enqueueTextResponse(server);
            DeepSeekClient client = new DeepSeekClient(
                    "test-key", "deepseek-chat", server.url("/chat/completions").toString());

            client.chat(List.of(LlmClient.Message.user("你好")), null);

            JsonNode root = requestBody(server.takeRequest());
            assertFalse(root.has("temperature"));
            assertFalse(root.has("top_p"));
            assertFalse(root.has("reasoning_effort"));
            assertFalse(root.has("thinking"));
        }
    }

    @Test
    void glm53UsesOneMillionContextAndKeepsThinkingAcrossToolTurns() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            enqueueTextResponse(server);
            GLMClient client = new GLMClient(
                    "test-key", "glm-5.3-flash", server.url("/chat/completions").toString());

            client.chat(List.of(thinkingToolCallMessage()), List.of(readFileTool()));

            JsonNode root = requestBody(server.takeRequest());
            JsonNode assistant = root.path("messages").get(0);
            assertEquals("glm-5.3-flash", root.path("model").asText());
            assertEquals(1.0, root.path("temperature").asDouble());
            assertEquals(0.95, root.path("top_p").asDouble());
            assertEquals("max", root.path("reasoning_effort").asText());
            assertEquals("enabled", root.path("thinking").path("type").asText());
            assertFalse(root.path("thinking").path("clear_thinking").asBoolean(true));
            assertTrue(root.path("tool_stream").asBoolean());
            assertTrue(root.path("stream_options").path("include_usage").asBoolean());
            assertEquals("hidden reasoning", assistant.path("reasoning_content").asText());
            assertEquals("call_1", assistant.path("tool_calls").get(0).path("id").asText());
            assertTrue(root.path("tools").isArray());
            assertEquals(1_000_000, client.maxContextWindow());
            assertTrue(client.supportsImageInput());
        }
    }

    @Test
    void olderGlmModelKeepsExistingRequestAndContextBehavior() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            enqueueTextResponse(server);
            GLMClient client = new GLMClient(
                    "test-key", "glm-5.1", server.url("/chat/completions").toString());

            client.chat(List.of(thinkingToolCallMessage()), null);

            JsonNode root = requestBody(server.takeRequest());
            assertFalse(root.has("temperature"));
            assertFalse(root.has("top_p"));
            assertFalse(root.has("reasoning_effort"));
            assertFalse(root.has("thinking"));
            assertFalse(root.has("tool_stream"));
            assertFalse(root.has("stream_options"));
            assertFalse(root.path("messages").get(0).has("reasoning_content"));
            assertEquals(200_000, client.maxContextWindow());
            assertTrue(client.supportsImageInput());
        }
    }

    @Test
    void hunyuanUsesTokenHubProtocolAndParsesToolUsageStream() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("""
                            data: {"choices":[{"delta":{"role":"assistant","reasoning_content":"先读文件。","tool_calls":[{"index":0,"id":"call_remote","function":{"name":"read_file","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                            data: {"choices":[],"usage":{"prompt_tokens":21,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":5}}}

                            data: [DONE]

                            """));
            HunyuanClient client = new HunyuanClient(
                    "test-key", "hy4-preview", server.url("/v1").toString());

            LlmClient.ChatResponse response = client.chat(
                    List.of(thinkingToolCallMessage()), List.of(readFileTool()));

            RecordedRequest request = server.takeRequest();
            JsonNode root = requestBody(request);
            JsonNode assistant = root.path("messages").get(0);
            assertEquals("Bearer test-key", request.getHeader("Authorization"));
            assertEquals("hy4-preview", root.path("model").asText());
            assertEquals("high", root.path("reasoning_effort").asText());
            assertTrue(root.path("stream_options").path("include_usage").asBoolean());
            assertEquals("hidden reasoning", assistant.path("reasoning_content").asText());
            assertEquals("call_1", assistant.path("tool_calls").get(0).path("id").asText());
            assertTrue(root.path("tools").isArray());
            assertEquals("先读文件。", response.reasoningContent());
            assertEquals("call_remote", response.toolCalls().get(0).id());
            assertEquals("read_file", response.toolCalls().get(0).function().name());
            assertEquals(21, response.inputTokens());
            assertEquals(7, response.outputTokens());
            assertEquals(5, response.cachedInputTokens());
            assertEquals(1_000_000, client.maxContextWindow());
            assertFalse(client.supportsImageInput());
            assertTrue(client.supportsPromptCaching());
            assertEquals("hunyuan-prefix-cache", client.promptCacheMode());
        }
    }

    private static LlmClient.Message thinkingToolCallMessage() {
        return LlmClient.Message.assistant(
                "hidden reasoning",
                "",
                List.of(new LlmClient.ToolCall(
                        "call_1",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"README.md\"}"))));
    }

    private static LlmClient.Tool readFileTool() {
        return new LlmClient.Tool(
                "read_file",
                "Read a file",
                MAPPER.createObjectNode().put("type", "object"));
    }

    private static void enqueueTextResponse(MockWebServer server) {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"choices":[{"delta":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":1}}

                        data: [DONE]

                        """));
    }

    private static JsonNode requestBody(RecordedRequest request) throws Exception {
        return MAPPER.readTree(request.getBody().readUtf8());
    }
}
