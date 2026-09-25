package com.chatbot.ai.service;

import com.chatbot.ai.domain.workflow.WorkflowEdge;
import com.chatbot.ai.domain.workflow.WorkflowNode;
import java.util.List;
import java.util.Map;

/** Executable starting points and their reproducible, read-only sample inputs. */
public final class WorkflowTemplateCatalog {
    private WorkflowTemplateCatalog() { }
    public record TestCase(String id, String name, String description, Map<String, Object> input,
                           String expectedStatus, String expectedBranch) { }
    public record Template(String code, String name, String scenarioCode, String description,
                           List<WorkflowNode> nodes, List<WorkflowEdge> edges, List<TestCase> testCases) { }

    public static List<Template> templates() {
        return List.of(
            business("commerce-after-sale", "电商售后分流", "commerce-support", "queryOrder", "orderNo",
                "查询订单", "result.orderStatus", "已完成", "核验真实订单，已完成订单进入人工复核，其他状态直接返回查询结果。",
                sample("approve", "已完成订单 · 人工确认", "查询种子订单，等待确认；同意后完成，拒绝后结束。", "orderNo", "ORD-20260918-001", null, "waiting", "true"),
                sample("alternate", "服务中订单 · 直接返回", "订单状态不满足售后复核条件，走否分支。", "orderNo", "ORD-20260920-008", null, "completed", "false")),
            business("saas-incident", "SaaS 故障升级", "saas-success", "queryBusinessSubject", "subjectNo",
                "核验租户", "severity", "高", "核验租户资料；高等级故障进入人工升级确认，普通问题直接返回诊断输入与主体信息。",
                sample("approve", "高等级故障 · 升级确认", "真实查询企业租户，严重程度为高时进入复核。", "subjectNo", "TENANT-2001", Map.of("severity", "高"), "waiting", "true"),
                sample("alternate", "普通故障 · 直接返回", "普通问题走否分支，无需人工复核。", "subjectNo", "TENANT-2001", Map.of("severity", "普通"), "completed", "false")),
            business("it-service-request", "IT 服务请求", "it-service", "queryBusinessSubject", "subjectNo",
                "核验员工", "category", "权限", "核验员工资料；权限请求需要人工审批，设备与网络问题直接返回请求信息。",
                sample("approve", "权限申请 · 审批确认", "查询种子员工，权限申请等待管理员决定。", "subjectNo", "EMP-3108", Map.of("category", "权限"), "waiting", "true"),
                sample("alternate", "网络问题 · 直接返回", "网络问题走否分支。", "subjectNo", "EMP-3108", Map.of("category", "网络"), "completed", "false")),
            business("merchant-appeal", "商家申诉处理", "merchant-ops", "queryBusinessSubject", "subjectNo",
                "核验商家", "materialsComplete", "true", "核验商家资料；材料齐全进入人工复核，缺少材料时返回补充要求。",
                sample("approve", "材料齐全 · 人工复核", "查询种子商家，齐全的材料进入复核。", "subjectNo", "MER-8802", Map.of("materialsComplete", true), "waiting", "true"),
                sample("alternate", "材料不足 · 补充材料", "材料不全走否分支。", "subjectNo", "MER-8802", Map.of("materialsComplete", false), "completed", "false")),
            new Template("knowledge-research", "知识检索验证", "knowledge-research", "检索所选知识库的真实文档并输出证据；需要已索引文档和可用的 AI 检索服务。",
                List.of(node("start", "input", "接收问题", "输入需要查找的制度或产品问题", "{}", 0, 0),
                    node("retrieve", "knowledge", "检索文档", "从所选知识库召回证据", "{\"questionField\":\"question\",\"topK\":3}", 1, 0),
                    node("done", "output", "返回证据", "输出命中文档、片段与数量", "{}", 2, 0)),
                List.of(edge("start", "retrieve", null), edge("retrieve", "done", null)),
                List.of(new TestCase("retrieve", "检索售后政策", "选择包含售后规则的知识库；无命中时如实返回空证据，不编造答案。",
                    Map.of("question", "退款申请有哪些条件？"), "completed", null))),
            new Template("general-assistant", "通用问题处理", "general", "将输入交给已配置的模型处理，并返回真实响应。",
                List.of(node("start", "input", "接收问题", "填写需要处理的问题", "{}", 0, 0),
                    node("model", "model", "整理回复", "根据用户输入生成简洁的回答", "{\"prompt\":\"请准确、简洁地回答输入中的 question，不确定时明确说明。\"}", 1, 0),
                    node("done", "output", "返回回复", "返回模型实际生成的内容", "{}", 2, 0)),
                List.of(edge("start", "model", null), edge("model", "done", null)),
                List.of(new TestCase("answer", "整理网络故障处理建议", "需要可用的 AI 模型服务，执行时会真实调用模型。",
                    Map.of("question", "员工办公网络无法连接，请用三句话说明排查步骤。"), "completed", null)))
        );
    }

    private static Template business(String code, String name, String scenario, String operation, String argument,
                                     String queryName, String field, String value, String description, TestCase... cases) {
        return new Template(code, name, scenario, description,
            List.of(node("start", "input", "接收请求", "填写业务编号与测试参数", "{}", 0, 0),
                node("query", "tool", queryName, "只读查询业务系统中的真实记录",
                    "{\"operation\":\"" + operation + "\",\"argumentField\":\"" + argument + "\"}", 1, 0),
                node("check", "condition", "判断处理路径", field + " = " + value,
                    "{\"field\":\"" + field + "\",\"operator\":\"equals\",\"value\":\"" + value + "\"}", 2, 0),
                node("review", "approval", "人工确认", "同意后继续，拒绝后结束本次运行", "{}", 2, 1),
                node("done", "output", "返回确认结果", "返回业务事实与审批决定", "{}", 1, 1),
                node("alternate", "output", "返回分流结果", "返回当前状态与请求信息", "{}", 3, 0)),
            List.of(edge("start", "query", null), edge("query", "check", null),
                edge("check", "review", "true"), edge("check", "alternate", "false"), edge("review", "done", null)),
            List.of(cases));
    }

    private static TestCase sample(String id, String name, String description, String field, String identifier,
                                   Map<String, Object> extra, String status, String branch) {
        var input = new java.util.LinkedHashMap<String, Object>();
        input.put("question", name); input.put(field, identifier);
        if (extra != null) input.putAll(extra);
        return new TestCase(id, name, description, input, status, branch);
    }
    private static WorkflowNode node(String id, String type, String name, String description, String config, int column, int row) {
        return WorkflowNode.builder().id(id).type(type).name(name).description(description).config(config)
            .positionX(60 + column * 290).positionY(110 + row * 190).build();
    }
    private static WorkflowEdge edge(String source, String target, String branch) {
        return new WorkflowEdge("edge-" + source + "-" + target, source, target, branch);
    }
}
