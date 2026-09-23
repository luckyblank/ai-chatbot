package com.chatbot.ai.repository;

import com.chatbot.ai.domain.workflow.WorkflowDefinition;
import com.chatbot.ai.domain.workflow.WorkflowEdge;
import com.chatbot.ai.domain.workflow.WorkflowNode;
import com.chatbot.ai.domain.workflow.WorkflowRun;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;

@Repository
@RequiredArgsConstructor
public class WorkflowRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_workflow (
                    id VARCHAR(64) PRIMARY KEY,
                    code VARCHAR(80) NOT NULL UNIQUE,
                    name VARCHAR(100) NOT NULL,
                    scenario_code VARCHAR(80) NOT NULL,
                    description VARCHAR(500),
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    definition_json LONGTEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_workflow_run (
                    id VARCHAR(64) PRIMARY KEY,
                    workflow_id VARCHAR(64) NOT NULL,
                    conversation_id VARCHAR(64),
                    status VARCHAR(30) NOT NULL,
                    steps_json LONGTEXT NOT NULL,
                    started_at TIMESTAMP NOT NULL,
                    completed_at TIMESTAMP NULL,
                    INDEX idx_workflow_run_workflow (workflow_id)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_workflow_run_state (
                    run_id VARCHAR(64) PRIMARY KEY,
                    state_json LONGTEXT NOT NULL
                )
                """);
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_workflow", Integer.class);
        if (count != null && count == 0) seedDefaults();
        seedReliableDemoIfMissing();
        recoverInterruptedRuns();
    }

    public List<WorkflowDefinition> findAll() {
        return jdbcTemplate.query("SELECT * FROM ai_workflow ORDER BY updated_at DESC", this::mapWorkflow);
    }

    public Optional<WorkflowDefinition> findById(String id) {
        return jdbcTemplate.query("SELECT * FROM ai_workflow WHERE id = ?", this::mapWorkflow, id)
                .stream().findFirst();
    }

    public WorkflowDefinition save(WorkflowDefinition workflow) {
        int updated = jdbcTemplate.update("""
                UPDATE ai_workflow SET name=?, scenario_code=?, description=?, enabled=?, definition_json=?, updated_at=?
                WHERE id=?
                """, workflow.getName(), workflow.getScenarioCode(), workflow.getDescription(), workflow.isEnabled(),
                writeJson(definitionData(workflow)), Timestamp.from(workflow.getUpdatedAt()), workflow.getId());
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO ai_workflow(id, code, name, scenario_code, description, enabled, definition_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, workflow.getId(), workflow.getCode(), workflow.getName(), workflow.getScenarioCode(),
                    workflow.getDescription(), workflow.isEnabled(), writeJson(definitionData(workflow)),
                    Timestamp.from(workflow.getCreatedAt()), Timestamp.from(workflow.getUpdatedAt()));
        }
        return workflow;
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM ai_workflow_run_state WHERE run_id IN (SELECT id FROM ai_workflow_run WHERE workflow_id = ?)", id);
        jdbcTemplate.update("DELETE FROM ai_workflow_run WHERE workflow_id = ?", id);
        jdbcTemplate.update("DELETE FROM ai_workflow WHERE id = ?", id);
    }

    @Transactional
    public WorkflowRun saveRun(WorkflowRun run) {
        return saveRun(run, Map.of());
    }

    @Transactional
    public WorkflowRun saveRun(WorkflowRun run, Map<String, Object> context) {
        jdbcTemplate.update("""
                INSERT INTO ai_workflow_run(id, workflow_id, conversation_id, status, steps_json, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, run.getId(), run.getWorkflowId(), run.getConversationId(), run.getStatus(),
                writeJson(run.getSteps()), Timestamp.from(run.getStartedAt()),
                run.getCompletedAt() == null ? null : Timestamp.from(run.getCompletedAt()));
        saveRunState(run, context);
        return run;
    }

    @Transactional
    public WorkflowRun updateRun(WorkflowRun run) {
        return updateRun(run, Map.of());
    }

    @Transactional
    public WorkflowRun updateRun(WorkflowRun run, Map<String, Object> context) {
        int updated = jdbcTemplate.update("""
                UPDATE ai_workflow_run SET status=?, steps_json=?, completed_at=? WHERE id=? AND workflow_id=?
                """, run.getStatus(), writeJson(run.getSteps()),
                run.getCompletedAt() == null ? null : Timestamp.from(run.getCompletedAt()),
                run.getId(), run.getWorkflowId());
        if (updated != 1) throw new IllegalStateException("工作流运行记录不存在或已被移除");
        saveRunState(run, context);
        return run;
    }

    /** Atomically claims a waiting approval and saves the corresponding recovery checkpoint. */
    @Transactional
    public boolean claimWaitingRun(WorkflowRun run, Map<String, Object> context) {
        int updated = jdbcTemplate.update(
                "UPDATE ai_workflow_run SET status='running' WHERE id=? AND workflow_id=? AND status='waiting'",
                run.getId(), run.getWorkflowId());
        if (updated != 1) return false;
        run.setStatus("running");
        run.setCheckpoint("approval-claimed");
        run.setStatusMessage("审批请求已领取，正在保存审批决定。");
        saveRunState(run, context);
        return true;
    }

    /** Restores only a still-claimed run. It never overwrites a terminal result. */
    @Transactional
    public boolean restoreWaitingRun(WorkflowRun waitingRun, Map<String, Object> context) {
        int updated = jdbcTemplate.update("""
                UPDATE ai_workflow_run SET status='waiting', steps_json=?, completed_at=NULL
                WHERE id=? AND workflow_id=? AND status='running'
                """, writeJson(waitingRun.getSteps()), waitingRun.getId(), waitingRun.getWorkflowId());
        if (updated != 1) return false;
        saveRunState(waitingRun, context);
        return true;
    }

    /** Converts a live running row to an explainable interruption without touching a terminal row. */
    @Transactional
    public boolean interruptRunningRun(WorkflowRun interruptedRun, Map<String, Object> context) {
        int updated = jdbcTemplate.update("""
                UPDATE ai_workflow_run SET status='interrupted', steps_json=?, completed_at=?
                WHERE id=? AND workflow_id=? AND status='running'
                """, writeJson(interruptedRun.getSteps()), Timestamp.from(interruptedRun.getCompletedAt()),
                interruptedRun.getId(), interruptedRun.getWorkflowId());
        if (updated != 1) return false;
        saveRunState(interruptedRun, context);
        return true;
    }

    public void saveRunState(WorkflowRun run, Map<String, Object> context) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("mode", run.getMode());
        state.put("workflowName", run.getWorkflowName() == null ? "" : run.getWorkflowName());
        state.put("ownerId", run.getOwnerId() == null ? "" : run.getOwnerId());
        state.put("definitionVersion", run.getDefinitionVersion() == null ? "" : run.getDefinitionVersion());
        state.put("nextNodeId", run.getNextNodeId() == null ? "" : run.getNextNodeId());
        state.put("checkpoint", run.getCheckpoint() == null ? "" : run.getCheckpoint());
        state.put("statusMessage", run.getStatusMessage() == null ? "" : run.getStatusMessage());
        state.put("input", run.getInput() == null ? Map.of() : run.getInput());
        state.put("output", run.getOutput() == null ? Map.of() : run.getOutput());
        state.put("traversedEdges", run.getTraversedEdges() == null ? List.of() : run.getTraversedEdges());
        state.put("waitingNodeId", run.getWaitingNodeId() == null ? "" : run.getWaitingNodeId());
        state.put("context", context == null ? Map.of() : context);
        upsertRunStateJson(run.getId(), writeJson(state));
    }

    public Map<String, Object> findRunContext(String runId) {
        return jdbcTemplate.query("SELECT state_json FROM ai_workflow_run_state WHERE run_id=?",
                (rs, row) -> readRunState(rs.getString(1)).context(), runId)
                .stream().findFirst().orElse(Map.of());
    }

    public Optional<WorkflowRun> findRun(String workflowId, String runId) {
        return jdbcTemplate.query("""
                SELECT r.*, w.name workflow_name, s.state_json FROM ai_workflow_run r
                JOIN ai_workflow w ON w.id=r.workflow_id
                LEFT JOIN ai_workflow_run_state s ON s.run_id=r.id
                WHERE r.workflow_id=? AND r.id=?
                """, this::mapRun, workflowId, runId).stream().findFirst();
    }

    public List<WorkflowRun> findRuns(String workflowId) {
        return jdbcTemplate.query("""
                SELECT r.*, w.name workflow_name, s.state_json FROM ai_workflow_run r
                JOIN ai_workflow w ON w.id=r.workflow_id
                LEFT JOIN ai_workflow_run_state s ON s.run_id=r.id
                WHERE r.workflow_id=? ORDER BY r.started_at DESC LIMIT 20
                """, this::mapRun, workflowId);
    }

    private WorkflowRun mapRun(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        RunState state = readRunState(rs.getString("state_json"));
        return WorkflowRun.builder()
                .id(rs.getString("id")).workflowId(rs.getString("workflow_id"))
                .workflowName(state.workflowName().isBlank() ? rs.getString("workflow_name") : state.workflowName())
                .conversationId(rs.getString("conversation_id"))
                .ownerId(state.ownerId())
                .mode(state.mode()).input(state.input()).output(state.output())
                .traversedEdges(state.traversedEdges()).waitingNodeId(state.waitingNodeId())
                .definitionVersion(state.definitionVersion()).nextNodeId(state.nextNodeId())
                .checkpoint(normalizedCheckpoint(rs.getString("status"), state.checkpoint()))
                .statusMessage(state.statusMessage())
                .status(rs.getString("status")).steps(readRunSteps(rs.getString("steps_json")))
                .startedAt(rs.getTimestamp("started_at").toInstant())
                .completedAt(rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant())
                .build();
    }

    private WorkflowDefinition mapWorkflow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return WorkflowDefinition.builder()
                .id(rs.getString("id")).code(rs.getString("code")).name(rs.getString("name"))
                .scenarioCode(rs.getString("scenario_code")).description(rs.getString("description"))
                .enabled(rs.getBoolean("enabled")).nodes(readDefinition(rs.getString("definition_json")).nodes())
                .edges(readDefinition(rs.getString("definition_json")).edges())
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .updatedAt(rs.getTimestamp("updated_at").toInstant()).build();
    }

    private void seedDefaults() {
        save(defaultWorkflow("commerce-after-sale", "电商售后分流", "commerce-support",
                "从订单核验、问题分类到退款/换货/工单的标准售后闭环。",
                node("identify", "input", "识别订单", "校验订单号、用户与商品"),
                node("classify", "condition", "判断诉求", "区分退款、换货、物流与质量问题"),
                node("policy", "knowledge", "检索售后规则", "从已选知识库获取时效与条件"),
                node("eligibility", "tool", "资格校验", "检查订单状态和可执行动作"),
                node("confirm", "approval", "用户确认", "确认方案后才执行业务动作"),
                node("ticket", "output", "生成服务单", "记录结论、证据与下一步")));
        save(defaultWorkflow("saas-incident", "SaaS 故障升级", "saas-success",
                "面向企业租户的故障定位、临时止损和分级升级流程。",
                node("tenant", "input", "识别租户", "确认租户、版本与影响范围"),
                node("diagnose", "knowledge", "知识诊断", "检索已知问题和处置手册"),
                node("severity", "condition", "判断等级", "依据影响用户数和核心链路定级"),
                node("workaround", "tool", "执行止损", "提供可回退的临时方案"),
                node("escalate", "output", "升级工单", "携带诊断信息进入研发队列")));
        save(defaultWorkflow("it-service-request", "IT 服务请求", "it-service",
                "覆盖账号权限、设备、网络与软件服务的内部请求流程。",
                node("identity", "input", "身份核验", "确认人员、部门与设备"),
                node("category", "condition", "服务分类", "判断账号、权限、设备或网络"),
                node("selfservice", "knowledge", "自助排障", "匹配标准操作与安全要求"),
                node("execute", "approval", "审批执行", "高风险权限需负责人确认"),
                node("close", "output", "结果回访", "记录解决方案并确认恢复")));
        save(defaultWorkflow("merchant-appeal", "商家申诉处理", "merchant-ops",
                "围绕规则命中、证据补充、复核与结果通知的申诉闭环。",
                node("merchant", "input", "识别商家", "确认店铺与处罚单"),
                node("rule", "knowledge", "定位规则", "读取适用条款与申诉窗口"),
                node("materials", "condition", "材料检查", "核对证明材料完整性"),
                node("review", "approval", "人工复核", "复杂争议进入运营审核"),
                node("notify", "output", "结果通知", "同步结论、依据和后续动作")));
    }

    /**
     * Adds a deterministic, model-free demo without updating an existing workflow with the same code.
     * This runs on every startup so installations created before this template still receive it.
     */
    private void seedReliableDemoIfMissing() {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_workflow WHERE code=?", Integer.class,
                "commerce-after-sale-reliable-demo");
        if (existing != null && existing > 0) return;
        Instant now = Instant.now();
        WorkflowDefinition demo = WorkflowDefinition.builder()
                .id(UUID.randomUUID().toString())
                .code("commerce-after-sale-reliable-demo")
                .name("售后订单审批演示")
                .scenarioCode("commerce-support")
                .description("使用种子订单完成只读查询、状态分支、人工审批与结果输出；无需调用外部模型。")
                .enabled(true)
                .nodes(List.of(
                        configuredNode("demo-input", "input", "接收订单号", "输入种子订单号 ORD-20260918-001", "{}"),
                        configuredNode("demo-query", "tool", "查询订单", "只读查询订单事实",
                                "{\"operation\":\"queryOrder\",\"argumentField\":\"orderNo\"}"),
                        configuredNode("demo-condition", "condition", "判断订单状态", "已完成订单进入审批",
                                "{\"field\":\"result.orderStatus\",\"operator\":\"equals\",\"value\":\"已完成\"}"),
                        configuredNode("demo-approval", "approval", "人工确认", "显式确认后继续输出", "{}"),
                        configuredNode("demo-success", "output", "输出审批结果", "返回订单事实与审批结果", "{}"),
                        configuredNode("demo-not-eligible", "output", "输出未进入审批", "返回订单事实与分支结果", "{}")))
                .edges(List.of(
                        edge("demo-input", "demo-query", null),
                        edge("demo-query", "demo-condition", null),
                        edge("demo-condition", "demo-approval", "true"),
                        edge("demo-condition", "demo-not-eligible", "false"),
                        edge("demo-approval", "demo-success", null)))
                .createdAt(now).updatedAt(now).build();
        save(demo);
    }

    /**
     * A process restart cannot leave an old row looking live forever. A claim that never saved a
     * decision is safe to put back into waiting; every other in-flight checkpoint becomes an
     * explicit interrupted terminal state and is never replayed automatically.
     */
    private void recoverInterruptedRuns() {
        List<RunningState> rows = jdbcTemplate.query("""
                SELECT r.id, s.state_json FROM ai_workflow_run r
                LEFT JOIN ai_workflow_run_state s ON s.run_id=r.id
                WHERE r.status='running'
                """, (rs, row) -> new RunningState(rs.getString("id"), rs.getString("state_json")));
        if (rows.isEmpty()) return;
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            for (RunningState row : rows) {
                JsonNode state = readStateTree(row.stateJson());
                String checkpoint = state.path("checkpoint").asText("");
                String waitingNodeId = state.path("waitingNodeId").asText("");
                int transitioned;
                if ("approval-claimed".equals(checkpoint) && !waitingNodeId.isBlank()) {
                    if (state.isObject()) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) state)
                                .put("checkpoint", "waiting-approval")
                                .put("statusMessage", "服务在保存审批决定前中断，已安全恢复为待审批。");
                    }
                    transitioned = jdbcTemplate.update(
                            "UPDATE ai_workflow_run SET status='waiting', completed_at=NULL WHERE id=? AND status='running'",
                            row.runId());
                } else {
                    if (state.isObject()) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) state)
                                .put("checkpoint", "interrupted")
                                .put("statusMessage", "服务在节点执行期间中断；为避免重放外部操作，本次运行不会自动继续。");
                    }
                    transitioned = jdbcTemplate.update(
                            "UPDATE ai_workflow_run SET status='interrupted', completed_at=? WHERE id=? AND status='running'",
                            Timestamp.from(Instant.now()), row.runId());
                }
                if (transitioned == 1 && state.isObject()) {
                    upsertRunStateJson(row.runId(), writeJson(state));
                }
            }
        });
    }

    private void upsertRunStateJson(String runId, String stateJson) {
        int updated = jdbcTemplate.update("UPDATE ai_workflow_run_state SET state_json=? WHERE run_id=?",
                stateJson, runId);
        if (updated == 0) {
            jdbcTemplate.update("INSERT INTO ai_workflow_run_state(run_id,state_json) VALUES (?,?)",
                    runId, stateJson);
        }
    }

    private WorkflowDefinition defaultWorkflow(String code, String name, String scenario, String description,
                                               WorkflowNode... nodes) {
        Instant now = Instant.now();
        return WorkflowDefinition.builder().id(UUID.randomUUID().toString()).code(code).name(name)
                .scenarioCode(scenario).description(description).enabled(true).nodes(List.of(nodes))
                .createdAt(now).updatedAt(now).build();
    }

    private WorkflowNode node(String id, String type, String name, String description) {
        return WorkflowNode.builder().id(id).type(type).name(name).description(description).config("{}").build();
    }

    private WorkflowNode configuredNode(String id, String type, String name, String description, String config) {
        return WorkflowNode.builder().id(id).type(type).name(name).description(description).config(config).build();
    }

    private WorkflowEdge edge(String source, String target, String branch) {
        return new WorkflowEdge("edge-" + source + "-" + target, source, target, branch);
    }

    private Map<String, Object> definitionData(WorkflowDefinition workflow) {
        Map<String, Object> definition = new java.util.LinkedHashMap<>();
        definition.put("nodes", workflow.getNodes());
        if (workflow.getEdges() != null) definition.put("edges", workflow.getEdges());
        return definition;
    }

    private DefinitionData readDefinition(String json) {
        try {
            JsonNode parsed = objectMapper.readTree(json);
            if (parsed.isArray()) {
                return new DefinitionData(objectMapper.convertValue(parsed, new TypeReference<List<WorkflowNode>>() {}), null);
            }
            return new DefinitionData(
                    objectMapper.convertValue(parsed.path("nodes"), new TypeReference<List<WorkflowNode>>() {}),
                    parsed.has("edges") ? objectMapper.convertValue(parsed.path("edges"), new TypeReference<List<WorkflowEdge>>() {}) : null);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工作流定义解析失败", exception);
        }
    }

    private RunState readRunState(String json) {
        if (json == null || json.isBlank()) {
            return new RunState("configuration", "", "", "", null, null, null,
                    Map.of(), null, List.of(), null, Map.of());
        }
        try {
            JsonNode parsed = objectMapper.readTree(json);
            Map<String, Object> input = parsed.has("input")
                    ? objectMapper.convertValue(parsed.path("input"), new TypeReference<Map<String, Object>>() {}) : Map.of();
            Object output = parsed.has("output") ? objectMapper.convertValue(parsed.path("output"), Object.class) : null;
            List<WorkflowEdge> edges = parsed.has("traversedEdges")
                    ? objectMapper.convertValue(parsed.path("traversedEdges"), new TypeReference<List<WorkflowEdge>>() {}) : List.of();
            Map<String, Object> context = parsed.has("context")
                    ? objectMapper.convertValue(parsed.path("context"), new TypeReference<Map<String, Object>>() {}) : Map.of();
            String waitingNodeId = parsed.path("waitingNodeId").asText("");
            String nextNodeId = parsed.path("nextNodeId").asText("");
            return new RunState(parsed.path("mode").asText("execution"),
                    parsed.path("workflowName").asText(""),
                    parsed.path("ownerId").asText(""),
                    parsed.path("definitionVersion").asText(""),
                    nextNodeId.isEmpty() ? null : nextNodeId,
                    parsed.path("checkpoint").asText(""),
                    parsed.path("statusMessage").asText(""),
                    input, output, edges, waitingNodeId.isEmpty() ? null : waitingNodeId, context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工作流运行状态解析失败", exception);
        }
    }

    private record DefinitionData(List<WorkflowNode> nodes, List<WorkflowEdge> edges) { }
    private record RunState(String mode, String workflowName, String ownerId, String definitionVersion,
                            String nextNodeId, String checkpoint, String statusMessage,
                            Map<String, Object> input, Object output,
                            List<WorkflowEdge> traversedEdges, String waitingNodeId,
                            Map<String, Object> context) { }
    private record RunningState(String runId, String stateJson) { }

    private JsonNode readStateTree(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try { return objectMapper.readTree(json); }
        catch (JsonProcessingException exception) { return objectMapper.createObjectNode(); }
    }

    private String normalizedCheckpoint(String status, String checkpoint) {
        if ("interrupted".equals(status)) return "interrupted";
        if ("waiting".equals(status) && "approval-claimed".equals(checkpoint)) return "waiting-approval";
        return checkpoint;
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("工作流定义序列化失败", exception); }
    }

    private List<WorkflowNode> readNodes(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("工作流定义解析失败", exception); }
    }

    private List<WorkflowRun.RunStep> readRunSteps(String json) {
        try { return objectMapper.readValue(json, new TypeReference<>() { }); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("工作流运行记录解析失败", exception); }
    }
}
