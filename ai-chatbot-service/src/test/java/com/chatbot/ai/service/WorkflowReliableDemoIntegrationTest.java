package com.chatbot.ai.service;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.domain.vo.RunWorkflowRequest;
import com.chatbot.ai.domain.vo.SaveWorkflowRequest;
import com.chatbot.ai.domain.workflow.WorkflowDefinition;
import com.chatbot.ai.domain.workflow.WorkflowEdge;
import com.chatbot.ai.domain.workflow.WorkflowNode;
import com.chatbot.ai.domain.workflow.WorkflowRun;
import com.chatbot.ai.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "app.storage.root=target/test-workflow-demo-data",
        "spring.datasource.url=jdbc:h2:mem:workflow-demo-test;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
class WorkflowReliableDemoIntegrationTest {
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser("admin-1", "admin", "管理员", "ADMIN");
    @Autowired
    private WorkflowService workflowService;
    @Autowired
    private WorkflowRepository workflowRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void seededDemoUsesRealRepositoryExecutorSnapshotAndDurableApproval() {
        WorkflowDefinition demo = workflowService.list().stream()
                .filter(item -> "commerce-after-sale-reliable-demo".equals(item.getCode()))
                .findFirst().orElseThrow();

        WorkflowRun waiting = workflowService.run(demo.getId(), new RunWorkflowRequest(
                "demo-conversation", Map.of("orderNo", "ORD-20260918-001"), null), ADMIN);

        assertThat(waiting.getStatus()).isEqualTo("waiting");
        assertThat(waiting.getDefinitionVersion()).isNotBlank();
        assertThat(waiting.getCheckpoint()).isEqualTo("waiting-approval");
        assertThat(waiting.getSteps()).extracting(WorkflowRun.RunStep::getNodeId)
                .containsExactly("demo-input", "demo-query", "demo-condition", "demo-approval");
        assertThat(waiting.getSteps().stream().filter(step -> "demo-condition".equals(step.getNodeId()))
                .findFirst().orElseThrow().getOutput()).isEqualTo(Map.of(
                        "matched", true, "field", "result.orderStatus", "actual", "已完成"));
        WorkflowRun persistedWaiting = workflowService.getRun(demo.getId(), waiting.getId(), ADMIN);
        assertThat(persistedWaiting.getStatus()).isEqualTo("waiting");
        assertThat(persistedWaiting.getWaitingNodeId()).isEqualTo("demo-approval");

        // Edit the live definition while approval is waiting. Resume must still use the run snapshot.
        WorkflowNode replacementStart = WorkflowNode.builder().id("replacement-start").type("input")
                .name("新开始").config("{}").build();
        WorkflowNode replacementEnd = WorkflowNode.builder().id("replacement-end").type("output")
                .name("新结束").config("{}").build();
        workflowService.update(demo.getId(), new SaveWorkflowRequest(demo.getName(), demo.getScenarioCode(),
                demo.getDescription(), true, List.of(replacementStart, replacementEnd),
                List.of(new WorkflowEdge("replacement-edge", "replacement-start", "replacement-end", null))));

        List<WorkflowService.RunEvent> events = workflowService.resumeRun(demo.getId(), waiting.getId(), true,
                ADMIN).collectList().block();
        WorkflowRun completed = (WorkflowRun) events.stream().filter(event -> "complete".equals(event.name()))
                .reduce((first, second) -> second).orElseThrow().data();

        assertThat(completed.getStatus()).isEqualTo("completed");
        assertThat(completed.getSteps()).extracting(WorkflowRun.RunStep::getNodeId)
                .containsExactly("demo-input", "demo-query", "demo-condition", "demo-approval", "demo-success");
        assertThat(workflowService.getRun(demo.getId(), waiting.getId(), ADMIN).getStatus()).isEqualTo("completed");
    }

    @Test
    void startupRecoveryRestoresOnlySafeApprovalClaimAndInterruptsOtherRunningCheckpoint() {
        WorkflowDefinition demo = workflowService.list().stream()
                .filter(item -> "commerce-after-sale-reliable-demo".equals(item.getCode()))
                .findFirst().orElseThrow();
        WorkflowRun claimedApproval = runningRun(demo.getId(), "approval-claimed", "demo-approval");
        WorkflowRun inFlightNode = runningRun(demo.getId(), "node-before:demo-query", null);
        workflowRepository.saveRun(claimedApproval, Map.of("values", Map.of("orderNo", "ORD-20260918-001")));
        workflowRepository.saveRun(inFlightNode, Map.of("values", Map.of("orderNo", "ORD-20260918-001")));

        // Re-running the idempotent startup initializer simulates the recovery pass
        // that a new application process performs against the existing database.
        workflowRepository.initialize();

        WorkflowRun restored = workflowRepository.findRun(demo.getId(), claimedApproval.getId()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo("waiting");
        assertThat(restored.getCheckpoint()).isEqualTo("waiting-approval");
        assertThat(restored.getStatusMessage()).contains("安全恢复为待审批");
        WorkflowRun interrupted = workflowRepository.findRun(demo.getId(), inFlightNode.getId()).orElseThrow();
        assertThat(interrupted.getStatus()).isEqualTo("interrupted");
        assertThat(interrupted.getCheckpoint()).isEqualTo("interrupted");
        assertThat(interrupted.getStatusMessage()).contains("不会自动继续");
    }

    @Test
    void startupRecoveryCreatesExplainableStateWhenRunningRowHasNoStateRow() throws Exception {
        WorkflowDefinition demo = workflowService.list().stream()
                .filter(item -> "commerce-after-sale-reliable-demo".equals(item.getCode()))
                .findFirst().orElseThrow();
        String runId = "missing-state-" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO ai_workflow_run(id, workflow_id, conversation_id, status, steps_json, started_at)
                VALUES (?, ?, ?, 'running', '[]', ?)
                """, runId, demo.getId(), "missing-state-conversation",
                Timestamp.from(Instant.parse("2026-09-24T00:00:00Z")));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_workflow_run_state WHERE run_id=?", Integer.class, runId)).isZero();

        workflowRepository.initialize();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ai_workflow_run WHERE id=?", String.class, runId)).isEqualTo("interrupted");
        String stateJson = jdbcTemplate.queryForObject(
                "SELECT state_json FROM ai_workflow_run_state WHERE run_id=?", String.class, runId);
        JsonNode persistedState = objectMapper.readTree(stateJson);
        assertThat(persistedState.path("checkpoint").asText()).isEqualTo("interrupted");
        assertThat(persistedState.path("statusMessage").asText())
                .contains("服务在节点执行期间中断")
                .contains("不会自动继续");

        WorkflowRun recovered = workflowRepository.findRun(demo.getId(), runId).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo("interrupted");
        assertThat(recovered.getCheckpoint()).isEqualTo("interrupted");
        assertThat(recovered.getStatusMessage()).isEqualTo(persistedState.path("statusMessage").asText());
    }

    private WorkflowRun runningRun(String workflowId, String checkpoint, String waitingNodeId) {
        return WorkflowRun.builder()
                .id(UUID.randomUUID().toString())
                .workflowId(workflowId)
                .workflowName("恢复测试")
                .ownerId(ADMIN.id())
                .mode("execution")
                .status("running")
                .checkpoint(checkpoint)
                .statusMessage("模拟进程退出前的运行状态")
                .waitingNodeId(waitingNodeId)
                .steps(List.of())
                .input(Map.of("orderNo", "ORD-20260918-001"))
                .output(Map.of())
                .traversedEdges(List.of())
                .startedAt(Instant.now())
                .build();
    }
}
