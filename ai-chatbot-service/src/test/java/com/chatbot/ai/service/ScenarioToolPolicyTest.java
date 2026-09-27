package com.chatbot.ai.service;

import com.chatbot.ai.domain.scenario.ScenarioDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScenarioToolPolicyTest {

    @Test
    void generalAndKnowledgeResearchNeverExposeBusinessTools() {
        ScenarioToolPolicy policy = policyWithAllCallbacks();

        assertThat(policy.allowedCallbacks(scenario())).isEmpty();
        assertThat(policy.allowedCallbacks(null)).isEmpty();
    }

    @Test
    void commerceOnlyExposesItsExplicitAllowlist() {
        ScenarioToolPolicy policy = policyWithAllCallbacks();

        assertThat(names(policy.allowedCallbacks(scenario(
                "客户权益查询", "客户订单查询", "订单履约查询", "售后资格校验", "创建服务工单"))))
                .containsExactlyInAnyOrder(
                        "queryCustomerEntitlements", "queryCustomerOrders", "queryOrder",
                        "checkAfterSalesEligibility", "prepareServiceTicket")
                .doesNotContain("queryBusinessSubject", "queryServiceTickets");
    }

    @Test
    void itServiceCannotCallCommerceOrderOrEligibilityTools() {
        ScenarioToolPolicy policy = policyWithAllCallbacks();

        assertThat(names(policy.allowedCallbacks(scenario(
                "业务主体查询", "工单进度查询", "创建 IT 工单"))))
                .containsExactlyInAnyOrder(
                        "queryBusinessSubject", "queryServiceTickets", "prepareServiceTicket")
                .doesNotContain("queryCustomerEntitlements", "queryCustomerOrders", "queryOrder", "checkAfterSalesEligibility");
    }

    @Test
    void editedToolListImmediatelyChangesTheHardAllowlist() {
        ScenarioToolPolicy policy = policyWithAllCallbacks();

        assertThat(names(policy.allowedCallbacks(scenario("订单履约查询"))))
                .containsExactly("queryOrder");
        assertThat(names(policy.allowedCallbacks(scenario("客户订单查询"))))
                .containsExactly("queryCustomerOrders");
        assertThat(policy.allowedCallbacks(scenario("未注册的自定义工具"))).isEmpty();
    }

    @Test
    void stableIdsAndLegacyAliasesResolveToTheSameCallbacks() {
        ScenarioToolPolicy policy = policyWithAllCallbacks();

        assertThat(names(policy.allowedCallbacks(scenario(
                "customer-orders", "order-fulfillment", "prepare-service-ticket"))))
                .containsExactlyInAnyOrder("queryCustomerOrders", "queryOrder", "prepareServiceTicket");
        assertThat(policy.isAllowed(scenario("创建运营工单"), "prepareServiceTicket")).isTrue();
    }

    @SuppressWarnings("unchecked")
    private ScenarioToolPolicy policyWithAllCallbacks() {
        ObjectProvider<ToolCallbackProvider> provider = mock(ObjectProvider.class);
        ToolCallbackProvider callbacks = mock(ToolCallbackProvider.class);
        when(provider.getIfAvailable()).thenReturn(callbacks);
        FunctionCallback[] availableCallbacks = new FunctionCallback[] {
                callback("queryCustomerEntitlements"),
                callback("queryCustomerOrders"),
                callback("queryOrder"),
                callback("queryBusinessSubject"),
                callback("checkAfterSalesEligibility"),
                callback("queryServiceTickets"),
                callback("prepareServiceTicket")
        };
        when(callbacks.getToolCallbacks()).thenReturn(availableCallbacks);
        return new ScenarioToolPolicy(provider);
    }

    private FunctionCallback callback(String name) {
        FunctionCallback callback = mock(FunctionCallback.class);
        when(callback.getName()).thenReturn(name);
        return callback;
    }

    private ScenarioDefinition scenario(String... tools) {
        return ScenarioDefinition.builder().code("test").tools(List.of(tools)).build();
    }

    private List<String> names(List<FunctionCallback> callbacks) {
        return callbacks.stream().map(FunctionCallback::getName).toList();
    }
}
