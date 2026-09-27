package com.chatbot.ai.repository;

import com.chatbot.ai.domain.scenario.ScenarioDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ScenarioRepository {
    private static final List<String> GENERAL_PROCESS = List.of(
            "理解用户目标", "缺少关键信息时先澄清", "根据已知信息生成结果", "必要时说明下一步");
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_scenario (
                    code VARCHAR(80) PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    short_name VARCHAR(40) NOT NULL,
                    summary VARCHAR(500) NOT NULL,
                    knowledge_mode VARCHAR(20) NOT NULL,
                    tools_json LONGTEXT NOT NULL,
                    process_json LONGTEXT NOT NULL,
                    guardrail VARCHAR(1000) NOT NULL,
                    sort_order INT NOT NULL,
                    updated_at TIMESTAMP NOT NULL
                )
                """);
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_scenario", Integer.class);
        if (count != null && count == 0) seedDefaults();
        else {
            upgradeDefaultCommerceTools();
            upgradeDefaultGeneralProcess();
        }
    }

    public List<ScenarioDefinition> findAll() {
        return jdbcTemplate.query("SELECT * FROM ai_scenario ORDER BY sort_order, code", this::mapScenario);
    }

    public Optional<ScenarioDefinition> findByCode(String code) {
        return jdbcTemplate.query("SELECT * FROM ai_scenario WHERE code = ?", this::mapScenario, code)
                .stream().findFirst();
    }

    public ScenarioDefinition save(ScenarioDefinition scenario) {
        int updated = jdbcTemplate.update("""
                UPDATE ai_scenario SET name=?, short_name=?, summary=?, knowledge_mode=?, tools_json=?,
                process_json=?, guardrail=?, sort_order=?, updated_at=? WHERE code=?
                """, scenario.getName(), scenario.getShortName(), scenario.getSummary(), scenario.getKnowledgeMode(),
                writeJson(scenario.getTools()), writeJson(scenario.getProcess()), scenario.getGuardrail(),
                scenario.getSortOrder(), Timestamp.from(scenario.getUpdatedAt()), scenario.getCode());
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO ai_scenario(code, name, short_name, summary, knowledge_mode, tools_json,
                    process_json, guardrail, sort_order, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, scenario.getCode(), scenario.getName(), scenario.getShortName(), scenario.getSummary(),
                    scenario.getKnowledgeMode(), writeJson(scenario.getTools()), writeJson(scenario.getProcess()),
                    scenario.getGuardrail(), scenario.getSortOrder(), Timestamp.from(scenario.getUpdatedAt()));
        }
        return scenario;
    }

    private ScenarioDefinition mapScenario(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return ScenarioDefinition.builder()
                .code(rs.getString("code"))
                .name(rs.getString("name"))
                .shortName(rs.getString("short_name"))
                .summary(rs.getString("summary"))
                .knowledgeMode(rs.getString("knowledge_mode"))
                .tools(readList(rs.getString("tools_json")))
                .process(readList(rs.getString("process_json")))
                .guardrail(rs.getString("guardrail"))
                .sortOrder(rs.getInt("sort_order"))
                .updatedAt(rs.getTimestamp("updated_at").toInstant())
                .build();
    }

    private void seedDefaults() {
        save(defaultScenario(0, "general", "通用智能助手", "通用助手",
                "不依赖知识库的日常分析、写作与信息整理。", "可选", List.of(),
                GENERAL_PROCESS,
                "不虚构企业内部数据，不执行业务写操作。"));
        save(defaultScenario(1, "commerce-support", "电商售后服务", "电商售后",
                "覆盖订单、物流、退换货、退款资格与售后工单。", "推荐",
                List.of("客户权益查询", "客户订单查询", "订单履约查询", "售后资格校验", "创建服务工单"),
                List.of("核验客户与订单", "判断问题类型", "匹配售后政策", "确认处理方案", "准备售后单并等待确认"),
                "退款、退货等写操作必须由用户明确确认。"));
        save(defaultScenario(2, "saas-success", "SaaS 客户成功", "客户成功",
                "处理租户开通、账号权限、订阅账单、用量与故障升级。", "推荐",
                List.of("业务主体查询", "服务订单查询", "工单进度查询", "创建支持工单"),
                List.of("识别租户", "定位产品模块", "知识排障", "检查订阅与用量", "升级支持工单"),
                "不展示其他租户数据，不直接变更权限和订阅。"));
        save(defaultScenario(3, "it-service", "企业 IT 服务台", "IT 服务台",
                "覆盖账号、权限、软件、设备、网络与内部服务请求。", "推荐",
                List.of("业务主体查询", "工单进度查询", "创建 IT 工单"),
                List.of("确认员工身份", "问题分类", "知识自助排障", "检查资产与权限", "派发工单"),
                "高权限申请需要审批，敏感凭据不得进入会话。"));
        save(defaultScenario(4, "merchant-ops", "平台商家运营", "商家运营",
                "处理店铺审核、商品治理、结算、处罚与申诉进度。", "推荐",
                List.of("商家主体查询", "服务工单查询", "创建运营工单"),
                List.of("核验商家", "识别业务域", "匹配平台规则", "查询处理进度", "引导补充材料"),
                "不承诺审核结果，不绕过风控和平台规则。"));
        save(defaultScenario(5, "knowledge-research", "制度与产品知识检索", "知识检索",
                "面向员工和客户检索制度、产品手册、流程与公告。", "推荐", List.of(),
                List.of("选择知识域", "检索相关片段", "核对发布日期", "生成带引用回答"),
                "资料不足时明确说明，不使用过期内容覆盖新政策。"));
    }

    private void upgradeDefaultCommerceTools() {
        findByCode("commerce-support").ifPresent(scenario -> {
            boolean changed = false;
            if (scenario.getTools().equals(List.of("客户权益查询", "订单履约查询", "售后资格校验", "创建服务工单"))) {
                scenario.setTools(List.of("客户权益查询", "客户订单查询", "订单履约查询", "售后资格校验", "创建服务工单"));
                changed = true;
            }
            if (scenario.getProcess().equals(List.of("核验订单", "判断问题类型", "匹配售后政策", "确认处理方案", "创建售后单"))) {
                scenario.setProcess(List.of("核验客户与订单", "判断问题类型", "匹配售后政策", "确认处理方案", "准备售后单并等待确认"));
                changed = true;
            }
            if (changed) {
                scenario.setUpdatedAt(Instant.now());
                save(scenario);
            }
        });
    }

    private void upgradeDefaultGeneralProcess() {
        findByCode("general").ifPresent(scenario -> {
            if (scenario.getProcess().equals(List.of("理解任务", "澄清约束", "生成结果", "确认下一步"))) {
                scenario.setProcess(GENERAL_PROCESS);
                scenario.setUpdatedAt(Instant.now());
                save(scenario);
            }
        });
    }

    private ScenarioDefinition defaultScenario(int sortOrder, String code, String name, String shortName,
                                                String summary, String knowledgeMode, List<String> tools,
                                                List<String> process, String guardrail) {
        return ScenarioDefinition.builder().code(code).name(name).shortName(shortName).summary(summary)
                .knowledgeMode(knowledgeMode).tools(tools).process(process).guardrail(guardrail)
                .sortOrder(sortOrder).updatedAt(Instant.now()).build();
    }

    private String writeJson(List<String> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("场景配置序列化失败", exception);
        }
    }

    private List<String> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("场景配置解析失败", exception);
        }
    }
}
