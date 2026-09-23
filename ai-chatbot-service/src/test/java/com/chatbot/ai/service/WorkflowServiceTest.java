package com.chatbot.ai.service;

import com.chatbot.ai.domain.vo.RunWorkflowRequest;
import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.domain.workflow.WorkflowDefinition;
import com.chatbot.ai.domain.workflow.WorkflowEdge;
import com.chatbot.ai.domain.workflow.WorkflowNode;
import com.chatbot.ai.domain.workflow.WorkflowRun;
import com.chatbot.ai.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class WorkflowServiceTest {
    private WorkflowRepository repository;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        service = new WorkflowService(repository, mapper,
                new WorkflowNodeExecutor(null, null, null, null, null, mapper));
        when(repository.saveRun(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.updateRun(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void executesOnlyTheActualConditionalBranchAndStreamsTraversedEdges() {
        when(repository.findById("wf")).thenReturn(Optional.of(workflow(
                List.of(node("start", "input", "{}"),
                        node("branch", "condition", "{\"field\":\"question\",\"operator\":\"contains\",\"value\":\"权限\"}"),
                        node("yes", "output", "{}"), node("no", "output", "{}")),
                List.of(edge("start", "branch", null), edge("branch", "yes", "true"), edge("branch", "no", "false")))));

        List<WorkflowService.RunEvent> events = service.streamRun("wf",
                new RunWorkflowRequest("conversation-1", Map.of("question", "申请权限"), null))
                .collectList().block();

        assertThat(events).isNotNull();
        assertThat(events.stream().filter(event -> event.name().equals("node-start"))
                .map(event -> payload(event).get("nodeId"))).containsExactly("start", "branch", "yes");
        assertThat(events.stream().filter(event -> event.name().equals("edge-traverse"))
                .map(event -> payload(event).get("target"))).containsExactly("branch", "yes");
        WorkflowRun run = (WorkflowRun) events.get(events.size() - 1).data();
        assertThat(run.getMode()).isEqualTo("execution");
        assertThat(run.getStatus()).isEqualTo("completed");
        assertThat(run.getSteps()).extracting(WorkflowRun.RunStep::getNodeId)
                .containsExactly("start", "branch", "yes");
        assertThat(run.getSteps().get(1).getOutput()).isInstanceOf(Map.class);
        assertThat(run.getTraversedEdges()).extracting(WorkflowEdge::getTarget).containsExactly("branch", "yes");
        verify(repository).saveRun(eq(run), any());
    }

    @Test
    void choosesFalseBranchForNonmatchingInput() {
        when(repository.findById("wf")).thenReturn(Optional.of(workflow(
                List.of(node("start", "input", "{}"),
                        node("branch", "condition", "{\"field\":\"question\",\"operator\":\"contains\",\"value\":\"权限\"}"),
                        node("yes", "output", "{}"), node("no", "output", "{}")),
                List.of(edge("start", "branch", null), edge("branch", "yes", "true"), edge("branch", "no", "false")))));
        WorkflowRun run = service.run("wf", new RunWorkflowRequest(null, Map.of("question", "咨询设备"), null));
        assertThat(run.getStatus()).isEqualTo("completed");
        assertThat(run.getSteps()).extracting(WorkflowRun.RunStep::getNodeId).containsExactly("start", "branch", "no");
    }

    @Test
    void unconfiguredConditionFailsInsteadOfPretendingToExecute() {
        when(repository.findById("wf")).thenReturn(Optional.of(workflow(
                List.of(node("start", "input", "{}"), node("branch", "condition", "{}"),
                        node("end", "output", "{}")), null)));
        WorkflowRun run = service.run("wf", new RunWorkflowRequest(null, Map.of("question", "申请权限"), null));
        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getSteps()).extracting(WorkflowRun.RunStep::getStatus)
                .containsExactly("completed", "failed");
        assertThat(run.getSteps().get(1).getDetail()).contains("未配置判断规则");
    }

    @Test
    void explicitEmptyEdgesDoNotFallBackToSequentialExecution() {
        when(repository.findById("wf")).thenReturn(Optional.of(workflow(
                List.of(node("start", "input", "{}"), node("end", "output", "{}")), List.of())));
        WorkflowRun run = service.run("wf", new RunWorkflowRequest(null, Map.of("question", "hello"), null));
        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getSteps()).extracting(WorkflowRun.RunStep::getNodeId).containsExactly("start");
        assertThat(run.getSteps().get(0).getDetail()).contains("没有后续连线");
    }

    @Test
    void approvalPersistsWaitingStateAndResumeExecutesRemainingNode() {
        WorkflowDefinition definition = workflow(List.of(node("start", "input", "{}"),
                node("approval", "approval", "{}"), node("end", "output", "{}")), null);
        when(repository.findById("wf")).thenReturn(Optional.of(definition));
        WorkflowRun waiting = service.run("wf", new RunWorkflowRequest(null, Map.of("question", "申请权限"), null));
        assertThat(waiting.getStatus()).isEqualTo("waiting");
        assertThat(waiting.getWaitingNodeId()).isEqualTo("approval");
        assertThat(waiting.getCompletedAt()).isNull();
        when(repository.findRun("wf", waiting.getId())).thenReturn(Optional.of(waiting));
        when(repository.claimWaitingRun("wf", waiting.getId())).thenReturn(true);
        when(repository.findRunContext(waiting.getId())).thenReturn(Map.of(
                "values", Map.of("question", "申请权限"), "knowledgeBaseId", ""));

        List<WorkflowService.RunEvent> resumed = service.resumeRun("wf", waiting.getId(), true,
                        new AuthenticatedUser("admin-1", "admin", "管理员", "ADMIN"))
                .collectList().block();

        assertThat(resumed).isNotNull();
        WorkflowRun completed = (WorkflowRun) resumed.get(resumed.size() - 1).data();
        assertThat(completed.getStatus()).isEqualTo("completed");
        assertThat(completed.getSteps()).extracting(WorkflowRun.RunStep::getStatus)
                .containsExactly("completed", "completed", "completed");
        assertThat(completed.getTraversedEdges()).extracting(WorkflowEdge::getTarget)
                .containsExactly("approval", "end");
        verify(repository).updateRun(eq(completed), any());
    }

    @Test
    void redactsSecretInputBeforeEventsAndPersistence() {
        when(repository.findById("wf")).thenReturn(Optional.of(workflow(
                List.of(node("start", "input", "{}"), node("end", "output", "{}")), null)));
        WorkflowRun run = service.run("wf", new RunWorkflowRequest(null,
                Map.of("question", "hello", "password", "do-not-store"), null));
        assertThat(run.getInput()).containsEntry("password", "[已隐藏]");
        assertThat(run.getSteps().get(0).getInput().toString()).doesNotContain("do-not-store");
    }

    @Test
    void rejectsOversizedInputBeforeSendingAnyRunEvents() {
        when(repository.findById("wf")).thenReturn(Optional.of(workflow(
                List.of(node("start", "input", "{}"), node("end", "output", "{}")), null)));
        assertThatThrownBy(() -> service.run("wf", new RunWorkflowRequest(null,
                Map.of("question", "a".repeat(16_385)), null)))
                .hasMessageContaining("16 KB");
    }

    @Test
    void rejectsWorkflowsWithTooManyModelNodes() {
        when(repository.findById("wf")).thenReturn(Optional.of(workflow(
                List.of(node("start", "input", "{}"), node("m1", "model", "{}"),
                        node("m2", "model", "{}"), node("m3", "model", "{}"),
                        node("m4", "model", "{}"), node("end", "output", "{}")), null)));
        assertThatThrownBy(() -> service.run("wf", new RunWorkflowRequest(null,
                Map.of("question", "hello"), null)))
                .hasMessageContaining("最多 30 个节点、3 个模型节点");
    }

    private WorkflowDefinition workflow(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        return WorkflowDefinition.builder().id("wf").name("测试工作流")
                .enabled(true).nodes(nodes).edges(edges).build();
    }

    private WorkflowNode node(String id, String type, String config) {
        return WorkflowNode.builder().id(id).name(id).type(type).config(config).build();
    }

    private WorkflowEdge edge(String source, String target, String branch) {
        return new WorkflowEdge("edge-" + source + "-" + target, source, target, branch);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(WorkflowService.RunEvent event) {
        return (Map<String, Object>) event.data();
    }
}
