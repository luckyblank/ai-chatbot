package com.chatbot.ai.service;

import com.chatbot.ai.domain.scenario.ScenarioDefinition;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces the server-side tool allowlist for every supported scenario.
 * Front-end labels and prompt instructions improve usability, but they are not
 * security boundaries; only callbacks returned here are exposed to the model.
 */
@Component
public class ScenarioToolPolicy {
    private static final Map<String, Set<String>> TOOL_NAMES_BY_LABEL = Map.ofEntries(
            Map.entry("客户权益查询", Set.of("queryCustomerEntitlements")),
            Map.entry("订单履约查询", Set.of("queryOrder")),
            Map.entry("服务订单查询", Set.of("queryOrder")),
            Map.entry("售后资格校验", Set.of("checkAfterSalesEligibility")),
            Map.entry("业务主体查询", Set.of("queryBusinessSubject")),
            Map.entry("商家主体查询", Set.of("queryBusinessSubject")),
            Map.entry("工单进度查询", Set.of("queryServiceTickets")),
            Map.entry("服务工单查询", Set.of("queryServiceTickets")),
            Map.entry("创建服务工单", Set.of("prepareServiceTicket")),
            Map.entry("创建支持工单", Set.of("prepareServiceTicket")),
            Map.entry("创建 IT 工单", Set.of("prepareServiceTicket")),
            Map.entry("创建运营工单", Set.of("prepareServiceTicket"))
    );

    private final ObjectProvider<ToolCallbackProvider> callbackProvider;

    public ScenarioToolPolicy(ObjectProvider<ToolCallbackProvider> callbackProvider) {
        this.callbackProvider = callbackProvider;
    }

    public List<FunctionCallback> allowedCallbacks(ScenarioDefinition scenario) {
        Set<String> allowedNames = allowedToolNames(scenario);
        if (allowedNames.isEmpty()) {
            return List.of();
        }
        ToolCallbackProvider provider = callbackProvider.getIfAvailable();
        if (provider == null) {
            return List.of();
        }
        return Arrays.stream(provider.getToolCallbacks())
                .filter(callback -> allowedNames.contains(callback.getName()))
                .map(callback -> (FunctionCallback) callback)
                .toList();
    }

    public boolean isAllowed(ScenarioDefinition scenario, String callbackName) {
        return callbackName != null && allowedToolNames(scenario).contains(callbackName);
    }

    public Set<String> allowedToolNames(ScenarioDefinition scenario) {
        if (scenario == null || scenario.getTools() == null) return Set.of();
        return scenario.getTools().stream()
                .flatMap(label -> TOOL_NAMES_BY_LABEL.getOrDefault(label, Set.of()).stream())
                .collect(Collectors.toUnmodifiableSet());
    }
}
