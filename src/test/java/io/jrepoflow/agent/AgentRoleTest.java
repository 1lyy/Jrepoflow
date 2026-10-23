package io.jrepoflow.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentRoleTest {
    @Test
    void exposesThreeNamedRoles() {
        assertEquals(3, AgentRole.values().length);
        assertEquals("规划者", AgentRole.PLANNER.getDisplayName());
        assertEquals("执行者", AgentRole.WORKER.getDisplayName());
        assertEquals("检查者", AgentRole.REVIEWER.getDisplayName());
        for (AgentRole role : AgentRole.values()) {
            assertFalse(role.getDescription().isBlank());
        }
    }
}
