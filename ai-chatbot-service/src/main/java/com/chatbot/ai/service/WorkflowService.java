package com.chatbot.ai.service;

import com.chatbot.ai.domain.vo.RunWorkflowRequest;
import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.domain.vo.SaveWorkflowRequest;
import com.chatbot.ai.domain.workflow.WorkflowDefinition;
import com.chatbot.ai.domain.workflow.WorkflowEdge;
import com.chatbot.ai.domain.workflow.WorkflowNode;
import com.chatbot.ai.domain.workflow.WorkflowRun;
import com.chatbot.ai.repository.WorkflowRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {
    private static final Set<String> NODE_TYPES = Set.of(
            "input", "condition", "knowledge", "tool", "model", "approval", "output");
    private static final int MAX_STEPS = 100;

    private final WorkflowRepository repository;
    private final ObjectMapper objectMapper;
    private final WorkflowNodeExecutor executor;

    public record RunEvent(String name, Object data) { }

    public List<WorkflowDefinition> list() { return repository.findAll(); }
    public WorkflowDefinition get(String id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工作流不存在"));
    }
    public WorkflowDefinition create(SaveWorkflowRequest request) {
        Instant now = Instant.now();
        return repository.save(WorkflowDefinition.builder().id(UUID.randomUUID().toString())
                .code("custom-" + UUID.randomUUID().toString().substring(0, 8))
                .name(request.name().trim()).scenarioCode(request.scenarioCode()).description(request.description())
                .enabled(request.enabled()).nodes(request.nodes())
                .edges(request.edges())
                .createdAt(now).updatedAt(now).build());
    }
    public WorkflowDefinition update(String id, SaveWorkflowRequest request) {
        WorkflowDefinition workflow = get(id);
        workflow.setName(request.name().trim()); workflow.setScenarioCode(request.scenarioCode());
        workflow.setDescription(request.description()); workflow.setEnabled(request.enabled());
        workflow.setNodes(request.nodes());
        workflow.setEdges(request.edges());
        workflow.setUpdatedAt(Instant.now());
        return repository.save(workflow);
    }
    public void delete(String id) { get(id); repository.delete(id); }
    public List<WorkflowRun> runs(String id, AuthenticatedUser actor) {
        get(id);
        requireActor(actor);
        return repository.findRuns(id).stream().filter(run -> canAccessRun(actor, run)).toList();
    }
    public WorkflowRun getRun(String workflowId, String runId, AuthenticatedUser actor) {
        get(workflowId);
        requireActor(actor);
        WorkflowRun run = repository.findRun(workflowId, runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "运行记录不存在"));
        requireRunAccess(actor, run);
        return run;
    }

    /** Compatibility endpoint: callers without an input receive a real failed input-node result. */
    public WorkflowRun run(String id, String conversationId, AuthenticatedUser actor) {
        return run(id, new RunWorkflowRequest(conversationId, Map.of(), null), actor);
    }
    public WorkflowRun run(String id, RunWorkflowRequest request, AuthenticatedUser actor) {
        requireActor(actor);
        return executeNew(requireRunnable(id), request, actor, event -> { });
    }
    public Flux<RunEvent> streamRun(String id, String conversationId, AuthenticatedUser actor) {
        return streamRun(id, new RunWorkflowRequest(conversationId, Map.of(), null), actor);
    }
    public Flux<RunEvent> streamRun(String id, RunWorkflowRequest request, AuthenticatedUser actor) {
        requireActor(actor);
        WorkflowDefinition workflow = requireRunnable(id);
        return Flux.<RunEvent>create(sink -> {
            try {
                executeNew(workflow, request, actor, sink::next);
                sink.complete();
            } catch (RuntimeException exception) {
                sink.error(exception);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<RunEvent> resumeRun(String workflowId, String runId, boolean approved,
                                    AuthenticatedUser actor) {
        WorkflowDefinition workflow = get(workflowId);
        return Flux.<RunEvent>create(sink -> {
            try {
                resume(workflow, runId, approved, actor, sink::next);
                sink.complete();
            } catch (RuntimeException exception) {
                sink.error(exception);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private WorkflowDefinition requireRunnable(String id) {
        WorkflowDefinition workflow = get(id);
        if (!workflow.isEnabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "工作流当前未启用");
        if (workflow.getNodes() == null || workflow.getNodes().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流没有可执行节点");
        }
        if (workflow.getNodes().size() > 30 || workflow.getNodes().stream()
                .filter(node -> node != null && "model".equals(node.getType())).count() > 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单次运行最多 30 个节点、3 个模型节点");
        }
        return workflow;
    }

    private WorkflowRun executeNew(WorkflowDefinition workflow, RunWorkflowRequest request,
                                   AuthenticatedUser actor,
                                   Consumer<RunEvent> emit) {
        Map<String, Object> input = cleanInput(request == null ? null : request.input());
        String startNodeId = startNode(workflow).getId();
        String definitionVersion = definitionVersion(workflow);
        WorkflowRun run = WorkflowRun.builder().id(UUID.randomUUID().toString())
                .workflowId(workflow.getId()).workflowName(workflow.getName())
                .conversationId(request == null ? null : request.conversationId())
                .ownerId(actor.id())
                .mode("execution").status("running").startedAt(Instant.now())
                .definitionVersion(definitionVersion).nextNodeId(startNodeId)
                .checkpoint("run-created").statusMessage("运行记录已创建，准备执行开始节点。")
                .input(input).steps(new ArrayList<>()).traversedEdges(new ArrayList<>()).build();
        Map<String, Object> values = new LinkedHashMap<>(input);
        String knowledgeBaseId = request == null ? null : request.knowledgeBaseId();
        Map<String, Object> context = executionContext(workflow, definitionVersion, values,
                knowledgeBaseId);
        refreshContext(context, values, knowledgeBaseId, startNodeId);
        // The run id exposed by run-start is now guaranteed to be queryable.
        repository.saveRun(run, context);
        emitRunStart(run, workflow, false, emit);
        try {
            return executeFrom(workflow, run, startNodeId, values, knowledgeBaseId, context, actor, emit);
        } catch (RuntimeException exception) {
            log.error("Workflow execution interrupted: workflow={}, run={}", workflow.getId(), run.getId(), exception);
            if (markInterrupted(run, context, values,
                    "服务在节点检查点之间中断；为避免重放外部操作，本次运行不会自动继续。")) {
                emitComplete(run, emit);
                return run;
            }
            throw exception;
        }
    }

    private WorkflowRun resume(WorkflowDefinition workflow, String runId, boolean approved,
                               AuthenticatedUser actor,
                               Consumer<RunEvent> emit) {
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可处理人工审批");
        }
        if (!workflow.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工作流已停用，当前策略不允许继续审批");
        }
        WorkflowRun run = repository.findRun(workflow.getId(), runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "运行记录不存在"));
        requireRunAccess(actor, run);
        if (!"waiting".equals(run.getStatus()) || run.getWaitingNodeId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该运行记录不在等待人工确认状态");
        }
        Map<String, Object> context = new LinkedHashMap<>(repository.findRunContext(runId));
        Object snapshot = context.get("workflowSnapshot") == null
                ? context.get("workflow") : context.get("workflowSnapshot");
        if (snapshot == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该历史运行没有定义快照，不能使用最新定义继续；请重新运行工作流");
        }
        WorkflowDefinition executionDefinition = objectMapper.convertValue(snapshot, WorkflowDefinition.class);
        String snapshotVersion = stringValue(context.get("definitionVersion"));
        if (!snapshotVersion.isBlank() && run.getDefinitionVersion() != null
                && !run.getDefinitionVersion().isBlank()
                && !snapshotVersion.equals(run.getDefinitionVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "运行定义快照版本不一致，请重新运行工作流");
        }
        if (context.get("values") == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "审批上下文不存在，请重新运行工作流");
        }
        Map<String, Object> values = mapValue(context.get("values"));
        String knowledgeBaseId = stringValue(context.get("knowledgeBaseId"));
        if (knowledgeBaseId.isBlank()) knowledgeBaseId = null;
        String waitingNodeId = run.getWaitingNodeId();
        WorkflowNode node = nodeById(executionDefinition, waitingNodeId);
        if (node == null || !"approval".equals(node.getType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "运行快照中的等待审批节点无效，请重新运行");
        }
        WorkflowRun.RunStep step = run.getSteps().stream()
                .filter(item -> waitingNodeId.equals(item.getNodeId()) && "waiting".equals(item.getStatus()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "审批节点状态不完整"));
        WorkflowRun waitingSnapshot = copyRun(run);
        Map<String, Object> waitingContext = copyContext(context);
        if (!repository.claimWaitingRun(run, context)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该审批已被处理，请刷新运行记录");
        }
        boolean decisionDurable = false;
        try {
            emitRunStart(run, executionDefinition, true, emit);
            Instant now = Instant.now();
            safeEmit(emit, new RunEvent("node-start", nodePayload(node, executionDefinition, now, values)));
            step.setCompletedAt(now);
            step.setDurationMs(Math.max(0, now.toEpochMilli() - step.getStartedAt().toEpochMilli()));
            step.setStatus(approved ? "completed" : "failed");
            step.setDetail(approved ? "人工确认通过，继续执行后续节点。" : "人工拒绝，流程停止。");
            step.setOutput(Map.of("approved", approved, "approverId", actor.id(),
                    "approverName", actor.displayName(), "decidedAt", now));
            step.setError(approved ? null : "人工拒绝");
            run.setWaitingNodeId(null);
            if (!approved) {
                finishPersisted(run, "failed", values, context, values,
                        "人工拒绝，流程已停止。");
                decisionDurable = true;
                safeEmit(emit, new RunEvent("node-complete", stepPayload(step, executionDefinition)));
                emitComplete(run, emit);
                return run;
            }

            values.put("approved", true);
            WorkflowEdge edge;
            try {
                edge = nextEdge(executionDefinition, node, Map.of("approved", true));
            } catch (IllegalArgumentException exception) {
                step.setStatus("failed");
                step.setDetail(exception.getMessage());
                step.setError(exception.getMessage());
                finishPersisted(run, "failed", values, context, values, exception.getMessage());
                decisionDurable = true;
                safeEmit(emit, new RunEvent("node-complete", stepPayload(step, executionDefinition)));
                emitComplete(run, emit);
                return run;
            }
            if (edge == null) {
                finishPersisted(run, "completed", values, context, values, "审批完成，工作流已结束。");
                decisionDurable = true;
                safeEmit(emit, new RunEvent("node-complete", stepPayload(step, executionDefinition)));
                emitComplete(run, emit);
                return run;
            }

            run.setStatus("running");
            run.setNextNodeId(edge.getTarget());
            run.setCheckpoint("approval-decided");
            run.setStatusMessage("审批决定已保存，准备执行后续节点。");
            refreshContext(context, values, knowledgeBaseId, run.getNextNodeId());
            repository.updateRun(run, context);
            decisionDurable = true;
            safeEmit(emit, new RunEvent("node-complete", stepPayload(step, executionDefinition)));

            traverse(run, edge);
            run.setCheckpoint("edge-traversed");
            refreshContext(context, values, knowledgeBaseId, edge.getTarget());
            repository.updateRun(run, context);
            emitTraversal(run, edge, emit);
            return executeFrom(executionDefinition, run, edge.getTarget(), values,
                    knowledgeBaseId, context, actor, emit);
        } catch (RuntimeException exception) {
            if (!decisionDurable) restoreWaitingAfterClaim(waitingSnapshot, waitingContext, runId);
            else markInterrupted(run, context, values,
                    "审批决定已经保存，但后续执行中断；为避免重复执行，本次运行不会自动重放。");
            throw exception;
        }
    }

    private WorkflowRun executeFrom(WorkflowDefinition workflow, WorkflowRun run,
                                    String currentId, Map<String, Object> values,
                                    String knowledgeBaseId, Map<String, Object> context,
                                    AuthenticatedUser actor,
                                    Consumer<RunEvent> emit) {
        Set<String> visited = new HashSet<>();
        run.getSteps().forEach(step -> visited.add(step.getNodeId()));
        while (currentId != null) {
            WorkflowNode node = nodeById(workflow, currentId);
            if (node == null) {
                finishPersisted(run, "failed", values, context, values,
                        "运行快照中的下一节点不存在：" + currentId);
                emitComplete(run, emit);
                return run;
            }
            run.setStatus("running");
            run.setCompletedAt(null);
            run.setNextNodeId(currentId);
            run.setCheckpoint("before-node");
            run.setStatusMessage("准备执行节点：" + node.getName());
            refreshContext(context, values, knowledgeBaseId, currentId);
            repository.updateRun(run, context);
            Instant started = Instant.now();
            long startNanos = System.nanoTime();
            safeEmit(emit, new RunEvent("node-start", nodePayload(node, workflow, started, values)));
            String status = "completed";
            String detail = "节点已实际执行。";
            String error = null;
            Map<String, Object> output = Map.of();
            WorkflowEdge next = null;
            try {
                if (run.getSteps().size() >= MAX_STEPS || !visited.add(node.getId())) {
                    throw new IllegalArgumentException("检测到重复节点或执行步数超过上限，已停止以防止循环。");
                }
                JsonNode config = validatedConfig(node);
                if ("approval".equals(node.getType())) {
                    status = "waiting";
                    detail = "等待人工确认。请在运行详情中选择通过或拒绝；当前不会自动执行后续节点。";
                } else {
                    if ("input".equals(node.getType()) && values.isEmpty()) {
                        throw new IllegalArgumentException("请先填写测试输入，再运行工作流。");
                    }
                    output = executor.execute(node, config, values, knowledgeBaseId,
                            workflow.getScenarioCode(), actor);
                    if (output == null) output = Map.of();
                    if (!"output".equals(node.getType())) next = nextEdge(workflow, node, output);
                    detail = describe(node, output);
                }
            } catch (IllegalArgumentException exception) {
                status = "failed";
                error = exception.getMessage();
                detail = error;
            } catch (RuntimeException exception) {
                log.error("Workflow node failed: workflow={}, run={}, node={}",
                        workflow.getId(), run.getId(), node.getId(), exception);
                status = "failed";
                error = "节点调用失败，请检查服务端日志或外部服务配置。";
                detail = error;
            }
            Instant completed = Instant.now();
            WorkflowRun.RunStep step = WorkflowRun.RunStep.builder()
                    .nodeId(node.getId()).nodeName(node.getName()).nodeType(node.getType())
                    .status(status).detail(detail).error(error).input(new LinkedHashMap<>(values))
                    .output(output).durationMs(Math.max(0, (System.nanoTime() - startNanos) / 1_000_000))
                    .startedAt(started).completedAt("waiting".equals(status) ? null : completed).build();
            run.getSteps().add(step);
            if ("failed".equals(status)) {
                finishPersisted(run, "failed", values, context, values, detail);
                safeEmit(emit, new RunEvent("node-complete", stepPayload(step, workflow)));
                emitComplete(run, emit);
                return run;
            }
            if ("waiting".equals(status)) {
                run.setWaitingNodeId(node.getId());
                run.setNextNodeId(null);
                finishPersisted(run, "waiting", values, context, values,
                        "等待人工确认；刷新或重启后仍可继续同一次运行。");
                safeEmit(emit, new RunEvent("node-complete", stepPayload(step, workflow)));
                emitComplete(run, emit);
                return run;
            }
            values.putAll(output);
            if ("output".equals(node.getType())) {
                finishPersisted(run, "completed", output, context, values, "工作流执行完成。");
                safeEmit(emit, new RunEvent("node-complete", stepPayload(step, workflow)));
                emitComplete(run, emit);
                return run;
            }
            if (next == null) {
                finishPersisted(run, "completed", values, context, values, "工作流执行完成。");
                safeEmit(emit, new RunEvent("node-complete", stepPayload(step, workflow)));
                emitComplete(run, emit);
                return run;
            }

            run.setNextNodeId(next.getTarget());
            run.setCheckpoint("after-node");
            run.setStatusMessage("节点已完成，下一节点为：" + next.getTarget());
            refreshContext(context, values, knowledgeBaseId, next.getTarget());
            repository.updateRun(run, context);
            safeEmit(emit, new RunEvent("node-complete", stepPayload(step, workflow)));

            traverse(run, next);
            run.setCheckpoint("edge-traversed");
            repository.updateRun(run, context);
            emitTraversal(run, next, emit);
            currentId = next.getTarget();
        }
        finishPersisted(run, "failed", values, context, values, "工作流没有可执行的下一节点。");
        emitComplete(run, emit);
        return run;
    }

    private WorkflowRun finishPersisted(WorkflowRun run, String status, Object output,
                                        Map<String, Object> context, Map<String, Object> values,
                                        String statusMessage) {
        run.setStatus(status);
        run.setOutput(output);
        run.setCompletedAt("waiting".equals(status) ? null : Instant.now());
        run.setNextNodeId(null);
        run.setCheckpoint("waiting".equals(status) ? "waiting-approval" : "terminal-" + status);
        run.setStatusMessage(statusMessage);
        refreshContext(context, values, stringValue(context.get("knowledgeBaseId")), null);
        repository.updateRun(run, context);
        return run;
    }

    private Map<String, Object> executionContext(WorkflowDefinition workflow, String definitionVersion,
                                                 Map<String, Object> values, String knowledgeBaseId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("workflowSnapshot", workflow);
        context.put("definitionVersion", definitionVersion);
        context.put("values", new LinkedHashMap<>(values));
        context.put("knowledgeBaseId", knowledgeBaseId == null ? "" : knowledgeBaseId);
        context.put("nextNodeId", "");
        return context;
    }

    private void refreshContext(Map<String, Object> context, Map<String, Object> values,
                                String knowledgeBaseId, String nextNodeId) {
        context.put("values", new LinkedHashMap<>(values));
        context.put("knowledgeBaseId", knowledgeBaseId == null ? "" : knowledgeBaseId);
        context.put("nextNodeId", nextNodeId == null ? "" : nextNodeId);
    }

    private boolean markInterrupted(WorkflowRun run, Map<String, Object> context,
                                    Map<String, Object> values, String message) {
        run.setStatus("interrupted");
        run.setCompletedAt(Instant.now());
        run.setCheckpoint("interrupted");
        run.setStatusMessage(message);
        refreshContext(context, values, stringValue(context.get("knowledgeBaseId")), run.getNextNodeId());
        try {
            return repository.interruptRunningRun(run, context);
        } catch (RuntimeException persistenceFailure) {
            log.error("Could not persist interrupted workflow run {}", run.getId(), persistenceFailure);
            return false;
        }
    }

    private void restoreWaitingAfterClaim(WorkflowRun waitingRun, Map<String, Object> context, String runId) {
        waitingRun.setStatus("waiting");
        waitingRun.setCompletedAt(null);
        waitingRun.setCheckpoint("waiting-approval");
        waitingRun.setStatusMessage("审批处理未保存，已安全恢复为待审批；刷新后可以重试。");
        try {
            if (!repository.restoreWaitingRun(waitingRun, context)) {
                log.warn("Workflow run {} was no longer claimable while restoring approval", runId);
            }
        } catch (RuntimeException restoreFailure) {
            log.error("Could not restore waiting workflow run {}; startup recovery will close it", runId,
                    restoreFailure);
        }
    }

    private WorkflowRun copyRun(WorkflowRun run) {
        List<WorkflowRun.RunStep> steps = run.getSteps() == null ? new ArrayList<>()
                : run.getSteps().stream().map(this::copyStep).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<WorkflowEdge> edges = run.getTraversedEdges() == null ? new ArrayList<>()
                : run.getTraversedEdges().stream()
                .map(edge -> new WorkflowEdge(edge.getId(), edge.getSource(), edge.getTarget(), edge.getBranch()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        return WorkflowRun.builder()
                .id(run.getId()).workflowId(run.getWorkflowId()).workflowName(run.getWorkflowName())
                .conversationId(run.getConversationId()).ownerId(run.getOwnerId())
                .mode(run.getMode()).status(run.getStatus())
                .definitionVersion(run.getDefinitionVersion()).nextNodeId(run.getNextNodeId())
                .checkpoint(run.getCheckpoint()).statusMessage(run.getStatusMessage())
                .input(run.getInput()).output(run.getOutput()).traversedEdges(edges)
                .waitingNodeId(run.getWaitingNodeId()).startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt()).steps(steps).build();
    }

    private WorkflowRun.RunStep copyStep(WorkflowRun.RunStep step) {
        return WorkflowRun.RunStep.builder()
                .nodeId(step.getNodeId()).nodeName(step.getNodeName()).nodeType(step.getNodeType())
                .status(step.getStatus()).detail(step.getDetail()).input(step.getInput()).output(step.getOutput())
                .error(step.getError()).durationMs(step.getDurationMs())
                .startedAt(step.getStartedAt()).completedAt(step.getCompletedAt()).build();
    }

    private Map<String, Object> copyContext(Map<String, Object> context) {
        Map<String, Object> copy = new LinkedHashMap<>(context);
        copy.put("values", new LinkedHashMap<>(mapValue(context.get("values"))));
        return copy;
    }

    private void emitComplete(WorkflowRun run, Consumer<RunEvent> emit) {
        safeEmit(emit, new RunEvent("complete", run));
    }

    private void safeEmit(Consumer<RunEvent> emit, RunEvent event) {
        try {
            emit.accept(event);
        } catch (RuntimeException emissionFailure) {
            // Transport failure must not roll a durable workflow checkpoint backwards.
            log.debug("Workflow event delivery stopped: event={}, run={}", event.name(),
                    event.data() instanceof WorkflowRun run ? run.getId() : "n/a", emissionFailure);
        }
    }

    private String definitionVersion(WorkflowDefinition workflow) {
        return workflow.getId() + ":" + (workflow.getUpdatedAt() == null ? "unknown" : workflow.getUpdatedAt());
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void requireActor(AuthenticatedUser actor) {
        if (actor == null || actor.id() == null || actor.id().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录状态无效");
        }
    }

    private boolean canAccessRun(AuthenticatedUser actor, WorkflowRun run) {
        // Keep the legacy-owner migration rule inside the service boundary as well
        // as the current admin-only controller. New rows are creator-bound.
        if (run.getOwnerId() == null || run.getOwnerId().isBlank()) {
            return "ADMIN".equals(actor.role());
        }
        return run.getOwnerId().equals(actor.id());
    }

    private void requireRunAccess(AuthenticatedUser actor, WorkflowRun run) {
        if (!canAccessRun(actor, run)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问其他操作员的工作流运行记录");
        }
    }

    private void emitRunStart(WorkflowRun run, WorkflowDefinition workflow, boolean resumed,
                              Consumer<RunEvent> emit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getId()); payload.put("startedAt", run.getStartedAt());
        payload.put("totalNodes", workflow.getNodes().size()); payload.put("mode", "execution");
        payload.put("resumed", resumed);
        payload.put("definitionVersion", run.getDefinitionVersion());
        payload.put("checkpoint", run.getCheckpoint());
        safeEmit(emit, new RunEvent("run-start", payload));
    }

    private Map<String, Object> nodePayload(WorkflowNode node, WorkflowDefinition workflow,
                                            Instant started, Map<String, Object> input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", node.getId()); payload.put("nodeName", node.getName());
        payload.put("nodeType", node.getType()); payload.put("index", workflow.getNodes().indexOf(node));
        payload.put("startedAt", started); payload.put("input", new LinkedHashMap<>(input));
        return payload;
    }

    private Map<String, Object> stepPayload(WorkflowRun.RunStep step, WorkflowDefinition workflow) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", step.getNodeId()); payload.put("nodeName", step.getNodeName());
        payload.put("nodeType", step.getNodeType());
        WorkflowNode node = nodeById(workflow, step.getNodeId());
        payload.put("index", node == null ? -1 : workflow.getNodes().indexOf(node));
        payload.put("status", step.getStatus()); payload.put("detail", step.getDetail());
        payload.put("durationMs", step.getDurationMs());
        payload.put("startedAt", step.getStartedAt()); payload.put("completedAt", step.getCompletedAt());
        payload.put("input", step.getInput()); payload.put("output", step.getOutput());
        if (step.getError() != null) payload.put("error", step.getError());
        return payload;
    }

    private void traverse(WorkflowRun run, WorkflowEdge edge) {
        run.getTraversedEdges().add(edge);
    }

    private void emitTraversal(WorkflowRun run, WorkflowEdge edge, Consumer<RunEvent> emit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("edgeId", edge.getId()); payload.put("source", edge.getSource());
        payload.put("target", edge.getTarget());
        if (edge.getBranch() != null) payload.put("branch", edge.getBranch());
        payload.put("checkpoint", run.getCheckpoint());
        safeEmit(emit, new RunEvent("edge-traverse", payload));
    }

    private WorkflowEdge nextEdge(WorkflowDefinition workflow, WorkflowNode node, Map<String, Object> output) {
        List<WorkflowEdge> outgoing = effectiveEdges(workflow).stream()
                .filter(edge -> node.getId().equals(edge.getSource())).toList();
        if (outgoing.isEmpty()) throw new IllegalArgumentException("节点没有后续连线，请连接到下一节点或使用结束节点。");
        if (!"condition".equals(node.getType())) {
            if (outgoing.size() != 1) throw new IllegalArgumentException("非条件节点只能连接一个后续节点。");
            return requireTarget(workflow, outgoing.get(0));
        }
        String branch = Boolean.TRUE.equals(output.get("matched")) ? "true" : "false";
        return outgoing.stream().filter(edge -> branch.equalsIgnoreCase(edge.getBranch()))
                .findFirst().map(edge -> requireTarget(workflow, edge))
                .orElseThrow(() -> new IllegalArgumentException("条件结果为 " + branch + "，但没有对应的分支连线。"));
    }

    private WorkflowEdge requireTarget(WorkflowDefinition workflow, WorkflowEdge edge) {
        if (nodeById(workflow, edge.getTarget()) == null) {
            throw new IllegalArgumentException("连线目标节点不存在：" + edge.getTarget());
        }
        return edge;
    }

    private List<WorkflowEdge> effectiveEdges(WorkflowDefinition workflow) {
        if (workflow.getEdges() != null) return workflow.getEdges();
        List<WorkflowEdge> edges = new ArrayList<>();
        for (int index = 0; index < workflow.getNodes().size() - 1; index++) {
            WorkflowNode source = workflow.getNodes().get(index);
            WorkflowNode target = workflow.getNodes().get(index + 1);
            edges.add(new WorkflowEdge("edge-" + source.getId() + "-" + target.getId(),
                    source.getId(), target.getId(), null));
        }
        return edges;
    }

    private WorkflowNode startNode(WorkflowDefinition workflow) {
        List<WorkflowNode> starts = workflow.getNodes().stream()
                .filter(node -> node != null && "input".equals(node.getType())).toList();
        if (starts.size() != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工作流必须有且仅有一个开始节点");
        return starts.get(0);
    }

    private WorkflowNode nodeById(WorkflowDefinition workflow, String id) {
        return workflow.getNodes().stream().filter(node -> node != null && id.equals(node.getId()))
                .findFirst().orElse(null);
    }

    private JsonNode validatedConfig(WorkflowNode node) {
        if (node == null || node.getId() == null || node.getId().isBlank()) {
            throw new IllegalArgumentException("节点缺少 ID。");
        }
        if (node.getName() == null || node.getName().isBlank()) {
            throw new IllegalArgumentException("节点缺少名称。");
        }
        if (node.getType() == null || !NODE_TYPES.contains(node.getType())) {
            throw new IllegalArgumentException("不支持的节点类型：" + node.getType());
        }
        String config = node.getConfig();
        if (config == null || config.isBlank()) config = "{}";
        try {
            JsonNode parsed = objectMapper.readerFor(JsonNode.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(config);
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException("节点配置必须是 JSON 对象。");
            return parsed;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("节点配置不是有效的 JSON。");
        }
    }

    private String describe(WorkflowNode node, Map<String, Object> output) {
        return switch (node.getType()) {
            case "input" -> "已接收本次运行输入。";
            case "condition" -> "已判断条件，结果：" + (Boolean.TRUE.equals(output.get("matched")) ? "true" : "false") + "。";
            case "knowledge" -> "已实际检索知识库，命中 " + output.getOrDefault("matchCount", 0) + " 个片段。";
            case "tool" -> "已调用只读业务查询：" + output.get("operation") + "。";
            case "model" -> "模型已返回实际生成内容。";
            case "output" -> "已返回前序节点的实际结果。";
            default -> "节点已执行。";
        };
    }

    private Map<String, Object> cleanInput(Map<String, Object> input) {
        if (input == null) return Map.of();
        if (input.size() > 30) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "运行输入最多包含 30 个字段");
        try {
            if (objectMapper.writeValueAsBytes(input).length > 16_384) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "运行输入最多 16 KB");
            }
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "运行输入无法解析");
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key == null || key.length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "运行输入字段名无效");
            clean.put(key, cleanValue(key, value, 0));
        });
        return clean;
    }

    private Object cleanValue(String key, Object value, int depth) {
        if (key.matches("(?i).*(password|secret|token|api.?key|credential|验证码|密码|密钥).*")) return "[已隐藏]";
        if (value == null || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof String string) {
            if (string.length() > 4000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "运行输入单字段最多 4000 字符");
            return string;
        }
        if (value instanceof Map<?, ?> map && depth < 2 && map.size() <= 30) {
            Map<String, Object> clean = new LinkedHashMap<>();
            map.forEach((nestedKey, nestedValue) -> {
                String name = String.valueOf(nestedKey);
                clean.put(name, cleanValue(name, nestedValue, depth + 1));
            });
            return clean;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "运行输入只能使用文本、数字、布尔值或浅层对象");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) return new LinkedHashMap<>((Map<String, Object>) map);
        return new LinkedHashMap<>();
    }
}
