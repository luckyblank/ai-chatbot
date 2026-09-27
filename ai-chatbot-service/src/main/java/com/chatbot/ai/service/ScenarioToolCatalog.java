package com.chatbot.ai.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The public scenario tool choices and their mapping to executable callbacks.
 * IDs remain stable even if a user-facing label or callback implementation changes.
 */
public final class ScenarioToolCatalog {
    private ScenarioToolCatalog() { }

    public record Entry(String id, String label, String description, List<String> requiredInputs,
                        String category, boolean confirmation, List<String> aliases) { }

    private record Tool(Entry entry, String callbackName) { }

    private static final List<Tool> TOOLS = List.of(
            tool("customer-entitlements", "客户权益查询", "查询已脱敏的客户资料、账号状态与服务权益。",
                    List.of("客户编号"), "查询", false, "queryCustomerEntitlements", "客户权益查询"),
            tool("customer-orders", "客户订单查询", "查询客户最近最多 20 笔订单；更多订单需补充线索。",
                    List.of("客户编号"), "查询", false, "queryCustomerOrders", "客户订单查询"),
            tool("order-fulfillment", "订单履约查询", "查询指定订单的状态、商品或服务及履约进度。",
                    List.of("订单号"), "查询", false, "queryOrder", "订单履约查询", "服务订单查询"),
            tool("after-sales-eligibility", "售后人工复核提示",
                    "仅在所选知识库有已索引文档且本轮检索命中相关片段时开放；核对订单与诉求后提示人工复核，当前不会自动判断售后资格通过。",
                    List.of("订单号", "售后诉求"), "查询", false, "checkAfterSalesEligibility", "售后资格校验"),
            tool("business-subject", "业务主体查询", "查询已脱敏的租户、员工或商家服务档案。",
                    List.of("业务主体编号"), "查询", false, "queryBusinessSubject", "业务主体查询", "商家主体查询"),
            tool("service-tickets", "工单进度查询", "查询已有服务工单的状态和负责团队。",
                    List.of("客户或业务主体编号", "订单号（可选）"), "查询", false, "queryServiceTickets",
                    "工单进度查询", "服务工单查询"),
            tool("prepare-service-ticket", "准备服务工单草案", "准备待确认的工单草案；用户确认后才会正式创建工单。",
                    List.of("客户或业务主体编号", "问题分类", "优先级", "问题摘要", "订单号（可选）"),
                    "处理", true, "prepareServiceTicket", "创建服务工单", "创建支持工单", "创建 IT 工单", "创建运营工单")
    );

    private static final Map<String, Tool> BY_INPUT = indexInputs();

    public static List<Entry> entries() {
        return TOOLS.stream().map(Tool::entry).toList();
    }

    public static Optional<String> idFor(String value) {
        Tool tool = BY_INPUT.get(value);
        return tool == null ? Optional.empty() : Optional.of(tool.entry().id());
    }

    public static Set<String> callbackNamesFor(List<String> selections) {
        if (selections == null) return Set.of();
        return selections.stream().map(BY_INPUT::get).filter(tool -> tool != null)
                .map(Tool::callbackName).collect(Collectors.toUnmodifiableSet());
    }

    public static List<String> displayNamesFor(List<String> selections) {
        if (selections == null) return List.of();
        return selections.stream().map(BY_INPUT::get).filter(tool -> tool != null)
                .map(tool -> tool.entry().label()).distinct().toList();
    }

    private static Tool tool(String id, String label, String description, List<String> requiredInputs,
                             String category, boolean confirmation, String callbackName, String... aliases) {
        return new Tool(new Entry(id, label, description, requiredInputs, category, confirmation,
                List.of(aliases)), callbackName);
    }

    private static Map<String, Tool> indexInputs() {
        Map<String, Tool> byInput = new LinkedHashMap<>();
        for (Tool tool : TOOLS) {
            byInput.put(tool.entry().id(), tool);
            byInput.put(tool.entry().label(), tool);
            for (String alias : tool.entry().aliases()) byInput.put(alias, tool);
        }
        return Map.copyOf(byInput);
    }
}
