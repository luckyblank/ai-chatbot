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
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.ArrayList;

@Repository
@RequiredArgsConstructor
public class WorkflowRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

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
        jdbcTemplate.update("""
                UPDATE ai_workflow_run SET status=?, steps_json=?, completed_at=? WHERE id=? AND workflow_id=?
                """, run.getStatus(), writeJson(run.getSteps()),
                run.getCompletedAt() == null ? null : Timestamp.from(run.getCompletedAt()),
                run.getId(), run.getWorkflowId());
        saveRunState(run, context);
        return run;
    }

    public boolean claimWaitingRun(String workflowId, String runId) {
        return jdbcTemplate.update("UPDATE ai_workflow_run SET status='running' WHERE id=? AND workflow_id=? AND status='waiting'",
                runId, workflowId) == 1;
    }

    public void saveRunState(WorkflowRun run, Map<String, Object> context) {
        Map<String, Object> state = Map.of(
                "mode", run.getMode(),
                "input", run.getInput() == null ? Map.of() : run.getInput(),
                "output", run.getOutput() == null ? Map.of() : run.getOutput(),
                "traversedEdges", run.getTraversedEdges() == null ? List.of() : run.getTraversedEdges(),
                "waitingNodeId", run.getWaitingNodeId() == null ? "" : run.getWaitingNodeId(),
                "context", context == null ? Map.of() : context);
        int updated = jdbcTemplate.update("UPDATE ai_workflow_run_state SET state_json=? WHERE run_id=?",
                writeJson(state), run.getId());
        if (updated == 0) jdbcTemplate.update("INSERT INTO ai_workflow_run_state(run_id,state_json) VALUES (?,?)",
                run.getId(), writeJson(state));
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
                .workflowName(rs.getString("workflow_name")).conversationId(rs.getString("conversation_id"))
                .mode(state.mode()).input(state.input()).output(state.output())
                .traversedEdges(state.traversedEdges()).waitingNodeId(state.waitingNodeId())
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
        if (json == null || json.isBlank()) return new RunState("configuration", Map.of(), null, List.of(), null, Map.of());
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
            return new RunState(parsed.path("mode").asText("execution"), input, output, edges,
                    waitingNodeId.isEmpty() ? null : waitingNodeId, context);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工作流运行状态解析失败", exception);
        }
    }

    private record DefinitionData(List<WorkflowNode> nodes, List<WorkflowEdge> edges) { }
    private record RunState(String mode, Map<String, Object> input, Object output,
                            List<WorkflowEdge> traversedEdges, String waitingNodeId,
                            Map<String, Object> context) { }

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
