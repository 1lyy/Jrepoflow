package io.jrepoflow.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentMessageTest {
    @Test
    void createsTaskAndResultMessages() {
        AgentMessage task = AgentMessage.task("orchestrator", "分析代码结构");
        assertEquals("orchestrator", task.fromAgent());
        assertNull(task.fromRole());
        assertEquals(AgentMessage.Type.TASK, task.type());

        AgentMessage result = AgentMessage.result("worker-1", AgentRole.WORKER, "完成");
        assertEquals(AgentRole.WORKER, result.fromRole());
        assertEquals(AgentMessage.Type.RESULT, result.type());
        assertEquals("完成", result.content());
    }

    @Test
    void createsReviewerAndErrorMessages() {
        assertEquals(AgentMessage.Type.FEEDBACK,
                AgentMessage.feedback("reviewer", "补充测试").type());
        assertEquals(AgentMessage.Type.APPROVAL,
                AgentMessage.approval("reviewer", "通过").type());
        assertEquals(AgentMessage.Type.REJECTION,
                AgentMessage.rejection("reviewer", "未通过").type());
        assertEquals(AgentMessage.Type.ERROR,
                AgentMessage.error("worker-1", AgentRole.WORKER, "失败").type());
    }
}
