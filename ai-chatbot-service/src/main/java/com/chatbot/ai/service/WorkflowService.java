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
    public List<WorkflowRun> runs(String id) { get(id); return repository.findRuns(id); }

    /** Compatibility endpoint: callers without an input receive a real failed input-node result. */
    public WorkflowRun run(String id, String conversationId) {
        return run(id, new RunWorkflowRequest(conversationId, Map.of(), null));
    }
    public WorkflowRun run(String id, RunWorkflowRequest request) {
        return executeNew(requireRunnable(id), request, event -> { });
    }
    public Flux<RunEvent> streamRun(String id, String conversationId) {
        return streamRun(id, new RunWorkflowRequest(conversationId, Map.of(), null));
    }
    public Flux<RunEvent> streamRun(String id, RunWorkflowRequest request) {
        WorkflowDefinition workflow = requireRunnable(id);
        return Flux.<RunEvent>create(sink -> {
            try {
                executeNew(workflow, request, sink::next);
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
                                   Consumer<RunEvent> emit) {
        Map<String, Object> input = cleanInput(request == null ? null : request.input());
        WorkflowRun run = WorkflowRun.builder().id(UUID.randomUUID().toString())
                .workflowId(workflow.getId()).workflowName(workflow.getName())
                .conversationId(request == null ? null : request.conversationId())
                .mode("execution").status("running").startedAt(Instant.now())
                .input(input).steps(new ArrayList<>()).traversedEdges(new ArrayList<>()).build();
        String startNodeId = startNode(workflow).getId();
        emitRunStart(run, workflow, false, emit);
        Map<String, Object> values = new LinkedHashMap<>(input);
        return executeFrom(workflow, run, startNodeId, values,
                request == null ? null : request.knowledgeBaseId(), false, emit);
    }

    private WorkflowRun resume(WorkflowDefinition workflow, String runId, boolean approved,
                               AuthenticatedUser actor,
                               Consumer<RunEvent> emit) {
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可处理人工审批");
        }
        WorkflowRun run = repository.findRun(workflow.getId(), runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "运行记录不存在"));
        if (!"waiting".equals(run.getStatus()) || run.getWaitingNodeId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该运行记录不在等待人工确认状态");
        }
        Map<String, Object> context = repository.findRunContext(runId);
        WorkflowDefinition executionDefinition = context.get("workflow") == null
                ? workflow : objectMapper.convertValue(context.get("workflow"), WorkflowDefinition.class);
        if (context.get("values") == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "审批上下文不存在，请重新运行工作流");
        }
        Map<String, Object> values = mapValue(context.get("values"));
        String knowledgeBaseId = context.get("knowledgeBaseId") instanceof String id ? id : null;
        String waitingNodeId = run.getWaitingNodeId();
        WorkflowNode node = nodeById(executionDefinition, waitingNodeId);
        if (node == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "等待节点已从工作流中删除，请重新运行");
        WorkflowRun.RunStep step = run.getSteps().stream()
                .filter(item -> waitingNodeId.equals(item.getNodeId()) && "waiting".equals(item.getStatus()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "审批节点状态不完整"));
        if (!repository.claimWaitingRun(workflow.getId(), runId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该审批已被处理，请刷新运行记录");
        }
        try {
        emitRunStart(run, executionDefinition, true, emit);
        Instant now = Instant.now();
        emit.accept(new RunEvent("node-start", nodePayload(node, executionDefinition, now, values)));
        step.setCompletedAt(now);
        step.setDurationMs(Math.max(0, now.toEpochMilli() - step.getStartedAt().toEpochMilli()));
        step.setStatus(approved ? "completed" : "failed");
        step.setDetail(approved ? "人工确认通过，继续执行后续节点。" : "人工拒绝，流程停止。");
        step.setOutput(Map.of("approved", approved, "approverId", actor.id(),
                "approverName", actor.displayName(), "decidedAt", now));
        step.setError(approved ? null : "人工拒绝");
        emit.accept(new RunEvent("node-complete", stepPayload(step, executionDefinition)));
        run.setWaitingNodeId(null);
        if (!approved) return finish(run, "failed", values, Map.of(), emit, true);
        values.put("approved", true);
        WorkflowEdge edge;
        try {
            edge = nextEdge(executionDefinition, node, Map.of("approved", true));
        } catch (IllegalArgumentException exception) {
            step.setStatus("failed"); step.setDetail(exception.getMessage()); step.setError(exception.getMessage());
            return finish(run, "failed", values, Map.of(), emit, true);
        }
        if (edge == null) return finish(run, "completed", values, Map.of(), emit, true);
        traverse(run, edge, emit);
        return executeFrom(executionDefinition, run, edge.getTarget(), values, knowledgeBaseId, true, emit);
        } catch (RuntimeException exception) {
            // Claim is atomic; restore a recoverable waiting state if processing fails after it.
            run.setStatus("waiting"); run.setCompletedAt(null); run.setWaitingNodeId(waitingNodeId);
            step.setStatus("waiting"); step.setCompletedAt(null); step.setOutput(null); step.setError(null);
            step.setDetail("等待人工确认。请刷新后重试。");
            try {
                repository.updateRun(run, context);
            } catch (RuntimeException restoreFailure) {
                log.error("Could not restore waiting workflow run {}", runId, restoreFailure);
            }
            throw exception;
        }
    }

    private WorkflowRun executeFrom(WorkflowDefinition workflow, WorkflowRun run,
                                    String currentId, Map<String, Object> values,
                                    String knowledgeBaseId, boolean persisted,
                                    Consumer<RunEvent> emit) {
        Set<String> visited = new HashSet<>();
        run.getSteps().forEach(step -> visited.add(step.getNodeId()));
        while (currentId != null) {
            WorkflowNode node = nodeById(workflow, currentId);
            if (node == null) return finish(run, "failed", values, Map.of(), emit, persisted);
            Instant started = Instant.now();
            long startNanos = System.nanoTime();
            emit.accept(new RunEvent("node-start", nodePayload(node, workflow, started, values)));
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
                    output = executor.execute(node, config, values, knowledgeBaseId, workflow.getScenarioCode());
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
                    .startedAt(started).completedAt(completed).build();
            run.getSteps().add(step);
            emit.accept(new RunEvent("node-complete", stepPayload(step, workflow)));
            if ("failed".equals(status)) return finish(run, "failed", values, Map.of(), emit, persisted);
            if ("waiting".equals(status)) {
                run.setWaitingNodeId(node.getId());
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("values", values);
                context.put("knowledgeBaseId", knowledgeBaseId == null ? "" : knowledgeBaseId);
                context.put("workflow", workflow);
                return finish(run, "waiting", values, context, emit, persisted);
            }
            values.putAll(output);
            if ("output".equals(node.getType())) return finish(run, "completed", output, Map.of(), emit, persisted);
            if (next == null) return finish(run, "completed", values, Map.of(), emit, persisted);
            traverse(run, next, emit);
            currentId = next.getTarget();
        }
        return finish(run, "failed", values, Map.of(), emit, persisted);
    }

    private WorkflowRun finish(WorkflowRun run, String status, Object output,
                               Map<String, Object> context, Consumer<RunEvent> emit,
                               boolean persisted) {
        run.setStatus(status);
        run.setOutput(output);
        run.setCompletedAt("waiting".equals(status) ? null : Instant.now());
        if (persisted) repository.updateRun(run, context);
        else repository.saveRun(run, context);
        emit.accept(new RunEvent("complete", run));
        return run;
    }

    private void emitRunStart(WorkflowRun run, WorkflowDefinition workflow, boolean resumed,
                              Consumer<RunEvent> emit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getId()); payload.put("startedAt", run.getStartedAt());
        payload.put("totalNodes", workflow.getNodes().size()); payload.put("mode", "execution");
        payload.put("resumed", resumed);
        emit.accept(new RunEvent("run-start", payload));
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

    private void traverse(WorkflowRun run, WorkflowEdge edge, Consumer<RunEvent> emit) {
        run.getTraversedEdges().add(edge);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("edgeId", edge.getId()); payload.put("source", edge.getSource());
        payload.put("target", edge.getTarget());
        if (edge.getBranch() != null) payload.put("branch", edge.getBranch());
        emit.accept(new RunEvent("edge-traverse", payload));
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
