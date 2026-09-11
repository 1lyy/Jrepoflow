package io.jrepoflow.prompt;

public enum PromptMode {
    AGENT("modes/agent.md"),
    PLAN("modes/plan.md"),
    PLANNER("modes/planner.md");

    private final String resourcePath;

    PromptMode(String resourcePath) {
        this.resourcePath = resourcePath;
    }

    public String resourcePath() {
        return resourcePath;
    }
}
