package io.jrepoflow.agent;

/** Multi-Agent 协作中的固定角色。 */
public enum AgentRole {
    PLANNER("规划者", "分析用户目标并生成带依赖关系的执行计划"),
    WORKER("执行者", "执行具体步骤并按需调用仓库工具"),
    REVIEWER("检查者", "审查步骤结果并给出结构化反馈");

    private final String displayName;
    private final String description;

    AgentRole(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
