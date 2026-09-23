package com.chatbot.ai.service;

import com.chatbot.ai.domain.vo.RunWorkflowRequest;
import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.domain.workflow.WorkflowDefinition;
import com.chatbot.ai.domain.workflow.WorkflowEdge;
import com.chatbot.ai.domain.workflow.WorkflowNode;
import com.chatbot.ai.domain.workflow.WorkflowRun;
import com.chatbot.ai.domain.scenario.ScenarioDefinition;
import com.chatbot.ai.repository.CustomerServiceDataRepository;
import com.chatbot.ai.repository.KnowledgeCatalogRepository;
import com.chatbot.ai.repository.ScenarioRepository;
import com.chatbot.ai.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;

class WorkflowServiceTest {
    private static final AuthenticatedUser ADMIN = new AuthenticatedUser("admin-1", "admin", "管理员", "ADMIN");
    private WorkflowRepository repository;
    private WorkflowService service;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        service = new WorkflowService(repository, mapper,
                new WorkflowNodeExecutor(null, null, null, null, null, null, mapper));
        when(repository.saveRun(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.updateRun(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.interruptRunningRun(any(), any())).thenReturn(true);
    }

    @Test
    void executesOnlyTheActualConditionalBranchAndStreamsTraversedEdges() {
        when(repository.findById("wf")).thenReturn(Optional.of(workflow(
                List.of(node("start", "input", "{}"),
                        node("branch", "condition", "{\"field\":\"question\",\"operator\":\"contains\",\"value\":\"权限\"}"),
                        node("yes", "output", "{}"), node("no", "output", "{}")),
                List.of(edge("start", "branch", null), edge("branch", "yes", "true"), edge("branch", "no", "false")))));

        List<WorkflowService.RunEvent> events = service.streamRun("wf",
                new RunWorkflowRequest("conversation-1", Map.of("question", "申请权限"), null), ADMIN)
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
        WorkflowRun run = service.run("wf", new RunWorkflowRequest(null, Map.of("question", "咨询设备"), null), ADMIN);
        assertThat(run.getStatus()).isEqualTo("completed");
        assertThat(run.getSteps()).extracting(WorkflowRun.RunStep::getNodeId).containsExactly("start", "branch", "no");
    }

    @Test
    void unconfiguredConditionFailsInsteadOfPretendingToExecute() {
        when(repository.findById("wf")).thenReturn(Optional.of(workflow(
                List.of(node("start", "input", "{}"), node("branch", "condition", "{}"),
                        node("end", "output", "{}")), null)));
        WorkflowRun run = service.run("wf", new RunWorkflowRequest(null, Map.of("question", "申请权限"), null), ADMIN);
        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getSteps()).extracting(WorkflowRun.RunStep::getStatus)
                .containsExactly("completed", "failed");
        assertThat(run.getSteps().get(1).getDetail()).contains("未配置判断规则");
    }

    @Test
    void explicitEmptyEdgesDoNotFallBackToSequentialExecution() {
        when(repository.findById("wf")).thenReturn(Optional.of(workflow(
                List.of(node("start", "input", "{}"), node("end", "output", "{}")), List.of())));
        WorkflowRun run = service.run("wf", new RunWorkflowRequest(null, Map.of("question", "hello"), null), ADMIN);
        assertThat(run.getStatus()).isEqualTo("failed");
        assertThat(run.getSteps()).extracting(WorkflowRun.RunStep::getNodeId).containsExactly("start");
        assertThat(run.getSteps().get(0).getDetail()).contains("没有后续连线");
    }

    @Test
    void approvalPersistsWaitingStateAndResumeExecutesRemainingNode() {
        WorkflowDefinition definition = workflow(List.of(node("start", "input", "{}"),
                node("approval", "approval", "{}"), node("end", "output", "{}")), null);
        when(repository.findById("wf")).thenReturn(Optional.of(definition));
        WorkflowRun waiting = service.run("wf", new RunWorkflowRequest(null, Map.of("question", "申请权限"), null), ADMIN);
        assertThat(waiting.getStatus()).isEqualTo("waiting");
        assertThat(waiting.getWaitingNodeId()).isEqualTo("approval");
        assertThat(waiting.getCompletedAt()).isNull();
        when(repository.findRun("wf", waiting.getId())).thenReturn(Optional.of(waiting));
        when(repository.claimWaitingRun(any(), any())).thenAnswer(invocation -> {
            WorkflowRun claimed = invocation.getArgument(0);
            claimed.setStatus("running");
            claimed.setCheckpoint("approval-claimed");
            return true;
        });
        when(repository.findRunContext(waiting.getId())).thenReturn(Map.of(
                "values", Map.of("question", "申请权限"), "knowledgeBaseId", "",
                "workflowSnapshot", definition, "definitionVersion", waiting.getDefinitionVersion()));

        List<WorkflowService.RunEvent> resumed = service.resumeRun("wf", waiting.getId(), true,
                        ADMIN)
                .collectList().block();

        assertThat(resumed).isNotNull();
        WorkflowRun completed = (WorkflowRun) resumed.get(resumed.size() - 1).data();
        assertThat(completed.getStatus()).isEqualTo("completed");
        assertThat(completed.getSteps()).extracting(WorkflowRun.RunStep::getStatus)
                .containsExactly("completed", "completed", "completed");
        assertThat(completed.getTraversedEdges()).extracting(WorkflowEdge::getTarget)
                .containsExactly("approval", "end");
        verify(repository, atLeastOnce()).updateRun(eq(completed), any());
    }

    @Test
    void workflowInputApprovedFlagCannotBypassApprovalNode() {
        WorkflowDefinition definition = workflow(List.of(node("start", "input", "{}"),
                node("approval", "approval", "{}"), node("end", "output", "{}")), null);
        when(repository.findById("wf")).thenReturn(Optional.of(definition));

        WorkflowRun waiting = service.run("wf", new RunWorkflowRequest(null,
                Map.of("approved", true, "question", "尝试从输入直接放行"), null), ADMIN);

        assertThat(waiting.getStatus()).isEqualTo("waiting");
        assertThat(waiting.getWaitingNodeId()).isEqualTo("approval");
        assertThat(waiting.getSteps()).extracting(WorkflowRun.RunStep::getNodeId)
                .containsExactly("start", "approval");
    }

    @Test
    void resumesFromPersistedDefinitionAndJsonToolResultAfterDefinitionWasEdited() throws Exception {
        CustomerServiceDataRepository businessData = mock(CustomerServiceDataRepository.class);
        ScenarioRepository scenarios = mock(ScenarioRepository.class);
        when(scenarios.findByCode("commerce-support")).thenReturn(Optional.of(
                ScenarioDefinition.builder().code("commerce-support")
                        .tools(List.of("订单履约查询")).build()));
        ObjectMapper mapper = new ObjectMapper();
        BusinessAuthorizationService authorization = mock(BusinessAuthorizationService.class);
        when(authorization.requireOrderAccess(ADMIN, "ORD-20260918-001")).thenReturn(
                new CustomerServiceDataRepository.OrderView("ORD-20260918-001", "CUST-10002",
                        "品牌商城", "智能会议终端 Pro", new BigDecimal("3299.00"),
                        "已完成", "已签收", null, null));
        WorkflowService realExecutorService = new WorkflowService(repository, mapper,
                new WorkflowNodeExecutor(mock(KnowledgeCatalogRepository.class), businessData,
                        scenarios, authorization, null, null, mapper));
        WorkflowDefinition original = workflow(List.of(
                        node("start", "input", "{}"),
                        node("query", "tool", "{\"operation\":\"queryOrder\"}"),
                        node("approval", "approval", "{}"),
                        node("condition", "condition", "{\"field\":\"result.orderStatus\",\"operator\":\"equals\",\"value\":\"已完成\"}"),
                        node("yes", "output", "{}"), node("no", "output", "{}")),
                List.of(edge("start", "query", null), edge("query", "approval", null),
                        edge("approval", "condition", null), edge("condition", "yes", "true"),
                        edge("condition", "no", "false")));
        WorkflowDefinition edited = workflow(List.of(node("start", "input", "{}"),
                node("replacement", "output", "{}")), null);
        when(repository.findById("wf")).thenReturn(Optional.of(original), Optional.of(edited));

        WorkflowRun waiting = realExecutorService.run("wf", new RunWorkflowRequest(null,
                Map.of("orderNo", "ORD-20260918-001"), null), ADMIN);
        assertThat(waiting.getStatus()).isEqualTo("waiting");
        assertThat(((Map<?, ?>) ((Map<?, ?>) waiting.getOutput()).get("result")).get("orderStatus"))
                .isEqualTo("已完成");

        // Simulate the JSON shape loaded from ai_workflow_run_state after a restart.
        Map<String, Object> persistedContext = mapper.readValue(mapper.writeValueAsString(Map.of(
                "values", waiting.getOutput(), "knowledgeBaseId", "",
                "workflowSnapshot", original, "definitionVersion", waiting.getDefinitionVersion())), Map.class);
        when(repository.findRun("wf", waiting.getId())).thenReturn(Optional.of(waiting));
        when(repository.findRunContext(waiting.getId())).thenReturn(persistedContext);
        when(repository.claimWaitingRun(any(), any())).thenAnswer(invocation -> {
            WorkflowRun claimed = invocation.getArgument(0);
            claimed.setStatus("running");
            claimed.setCheckpoint("approval-claimed");
            return true;
        });

        WorkflowRun completed = (WorkflowRun) realExecutorService.resumeRun("wf", waiting.getId(), true,
                        ADMIN)
                .collectList().block().stream().filter(event -> "complete".equals(event.name()))
                .reduce((first, second) -> second).orElseThrow().data();

        assertThat(completed.getStatus()).isEqualTo("completed");
        assertThat(completed.getSteps()).extracting(WorkflowRun.RunStep::getNodeId)
                .containsExactly("start", "query", "approval", "condition", "yes");
        assertThat(completed.getSteps().stream().filter(step -> "condition".equals(step.getNodeId()))
                .findFirst().orElseThrow().getOutput()).isEqualTo(Map.of(
                        "matched", true, "field", "result.orderStatus", "actual", "已完成"));
    }

    @Test
    void failedResumeBeforeDecisionCheckpointRestoresWaitingInsteadOfLeavingRunning() {
        WorkflowDefinition definition = workflow(List.of(node("start", "input", "{}"),
                node("approval", "approval", "{}"), node("end", "output", "{}")), null);
        when(repository.findById("wf")).thenReturn(Optional.of(definition));
        WorkflowRun waiting = service.run("wf", new RunWorkflowRequest(null, Map.of("question", "x"), null), ADMIN);
        when(repository.findRun("wf", waiting.getId())).thenReturn(Optional.of(waiting));
        when(repository.findRunContext(waiting.getId())).thenReturn(Map.of(
                "values", Map.of("question", "x"), "knowledgeBaseId", "",
                "workflowSnapshot", definition, "definitionVersion", waiting.getDefinitionVersion()));
        when(repository.claimWaitingRun(any(), any())).thenAnswer(invocation -> {
            WorkflowRun claimed = invocation.getArgument(0);
            claimed.setStatus("running");
            return true;
        });
        when(repository.updateRun(any(), any())).thenThrow(new IllegalStateException("checkpoint unavailable"));
        when(repository.restoreWaitingRun(any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.resumeRun("wf", waiting.getId(), true,
                ADMIN).collectList().block())
                .hasMessageContaining("checkpoint unavailable");
        verify(repository).restoreWaitingRun(any(), any());
    }

    @Test
    void redactsSecretInputBeforeEventsAndPersistence() {
        when(repository.findById("wf")).thenReturn(Optional.of(workflow(
                List.of(node("start", "input", "{}"), node("end", "output", "{}")), null)));
        WorkflowRun run = service.run("wf", new RunWorkflowRequest(null,
                Map.of("question", "hello", "password", "do-not-store"), null), ADMIN);
        assertThat(run.getInput()).containsEntry("password", "[已隐藏]");
        assertThat(run.getSteps().get(0).getInput().toString()).doesNotContain("do-not-store");
    }

    @Test
    void rejectsOversizedInputBeforeSendingAnyRunEvents() {
        when(repository.findById("wf")).thenReturn(Optional.of(workflow(
                List.of(node("start", "input", "{}"), node("end", "output", "{}")), null)));
        assertThatThrownBy(() -> service.run("wf", new RunWorkflowRequest(null,
                Map.of("question", "a".repeat(16_385)), null), ADMIN))
                .hasMessageContaining("16 KB");
    }

    @Test
    void rejectsWorkflowsWithTooManyModelNodes() {
        when(repository.findById("wf")).thenReturn(Optional.of(workflow(
                List.of(node("start", "input", "{}"), node("m1", "model", "{}"),
                        node("m2", "model", "{}"), node("m3", "model", "{}"),
                        node("m4", "model", "{}"), node("end", "output", "{}")), null)));
        assertThatThrownBy(() -> service.run("wf", new RunWorkflowRequest(null,
                Map.of("question", "hello"), null), ADMIN))
                .hasMessageContaining("最多 30 个节点、3 个模型节点");
    }

    @Test
    void legacyRunWithoutOwnerRequiresAdminAtServiceBoundary() {
        WorkflowRun legacy = WorkflowRun.builder().id("legacy-run").workflowId("wf")
                .status("waiting").steps(List.of()).build();
        when(repository.findById("wf")).thenReturn(Optional.of(workflow(
                List.of(node("start", "input", "{}"), node("end", "output", "{}")), null)));
        when(repository.findRun("wf", "legacy-run")).thenReturn(Optional.of(legacy));
        AuthenticatedUser operator = new AuthenticatedUser("operator-1", "operator", "客服", "USER");

        assertThatThrownBy(() -> service.getRun("wf", "legacy-run", operator))
                .hasMessageContaining("无权访问");
        assertThat(service.getRun("wf", "legacy-run", ADMIN)).isSameAs(legacy);
    }

    private WorkflowDefinition workflow(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        return WorkflowDefinition.builder().id("wf").name("测试工作流")
                .scenarioCode("commerce-support").enabled(true).nodes(nodes).edges(edges).build();
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
