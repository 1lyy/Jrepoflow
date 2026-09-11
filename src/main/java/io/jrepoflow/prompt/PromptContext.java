package io.jrepoflow.prompt;

import java.util.LinkedHashMap;
import java.util.Map;

public record PromptContext(
        String projectMemoryContext,
        String memoryContext,
        String externalContext,
        boolean toolsEnabled,
        Map<String, String> variables
) {
    public static Builder builder() {
        return new Builder();
    }

    public static PromptContext empty() {
        return builder().build();
    }

    public String variable(String key) {
        if (variables == null || key == null) {
            return "";
        }
        return variables.getOrDefault(key, "");
    }

    public static final class Builder {
        private String projectMemoryContext = "";
        private String memoryContext = "";
        private String externalContext = "";
        private boolean toolsEnabled = true;
        private final Map<String, String> variables = new LinkedHashMap<>();

        public Builder projectMemoryContext(String projectMemoryContext) {
            this.projectMemoryContext = normalize(projectMemoryContext);
            return this;
        }

        public Builder memoryContext(String memoryContext) {
            this.memoryContext = normalize(memoryContext);
            return this;
        }

        public Builder externalContext(String externalContext) {
            this.externalContext = normalize(externalContext);
            return this;
        }

        public Builder toolsEnabled(boolean toolsEnabled) {
            this.toolsEnabled = toolsEnabled;
            return this;
        }

        public Builder variable(String key, Object value) {
            if (key != null && !key.isBlank() && value != null) {
                this.variables.put(key.trim(), String.valueOf(value));
            }
            return this;
        }

        public PromptContext build() {
            return new PromptContext(projectMemoryContext, memoryContext, externalContext,
                    toolsEnabled, Map.copyOf(variables));
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
