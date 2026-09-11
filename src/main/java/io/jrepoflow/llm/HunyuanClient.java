package io.jrepoflow.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

public class HunyuanClient extends AbstractOpenAiCompatibleClient {

    private static final String DEFAULT_BASE_URL = "https://tokenhub.tencentmaas.com/v1";
    private static final String DEFAULT_MODEL = "hy4-preview";

    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public HunyuanClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL, DEFAULT_BASE_URL);
    }

    public HunyuanClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        this.apiUrl = toChatCompletionsUrl(baseUrl);
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    @Override
    protected String getModel() {
        return model;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    protected boolean shouldSendReasoningContentInRequestHistory() {
        return true;
    }

    @Override
    protected void customizeRequestBody(ObjectNode requestBody) {
        requestBody.put("reasoning_effort", "high");
        requestBody.putObject("stream_options").put("include_usage", true);
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public String getProviderName() {
        return "hunyuan";
    }

    @Override
    public int maxContextWindow() {
        return 1_000_000;
    }

    @Override
    public boolean supportsImageInput() {
        return false;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    public String promptCacheMode() {
        return "hunyuan-prefix-cache";
    }

    private static String toChatCompletionsUrl(String baseUrl) {
        String normalized = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
        String withoutTrailingSlash = normalized.replaceAll("/+$", "");
        if (withoutTrailingSlash.endsWith("/chat/completions")) {
            return withoutTrailingSlash;
        }
        return withoutTrailingSlash + "/chat/completions";
    }
}
