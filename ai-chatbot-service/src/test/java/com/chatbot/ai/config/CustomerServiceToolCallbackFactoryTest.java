package com.chatbot.ai.config;

import com.chatbot.ai.repository.CustomerServiceDataRepository;
import com.chatbot.ai.domain.action.PendingActionParameters;
import com.chatbot.ai.domain.action.PendingActionStatus;
import com.chatbot.ai.domain.action.PendingActionView;
import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.service.BusinessAuthorizationService;
import com.chatbot.ai.service.PendingActionService;
import com.chatbot.ai.service.ToolTraceRecorder;
import com.chatbot.ai.service.TrustedToolContext;
import com.chatbot.ai.tools.CustomerServiceTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CustomerServiceToolCallbackFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void schemasContainAllBusinessParametersButNeverExposeToolContext() throws Exception {
        CustomerServiceTools tools = new CustomerServiceTools(
                mock(CustomerServiceDataRepository.class), new ToolTraceRecorder(),
                mock(BusinessAuthorizationService.class), mock(PendingActionService.class));
        Map<String, ToolCallback> callbacks = CustomerServiceToolCallbackFactory.create(tools, objectMapper)
                .stream()
                .collect(Collectors.toMap(callback -> callback.getToolDefinition().name(), Function.identity()));

        List<Method> toolMethods = Arrays.stream(CustomerServiceTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .toList();
        assertThat(callbacks).hasSize(toolMethods.size());

        for (Method method : toolMethods) {
            JsonNode schema = objectMapper.readTree(callbacks.get(method.getName())
                    .getToolDefinition().inputSchema());
            Set<String> expectedProperties = Arrays.stream(method.getParameters())
                    .filter(parameter -> parameter.getType() != ToolContext.class)
                    .map(Parameter::getName)
                    .collect(Collectors.toSet());
            Set<String> expectedRequired = Arrays.stream(method.getParameters())
                    .filter(parameter -> parameter.getType() != ToolContext.class)
                    .filter(CustomerServiceToolCallbackFactoryTest::isRequired)
                    .map(Parameter::getName)
                    .collect(Collectors.toSet());

            assertThat(fieldNames(schema.path("properties")))
                    .as("业务参数 properties: %s", method.getName())
                    .containsExactlyInAnyOrderElementsOf(expectedProperties);
            assertThat(textValues(schema.path("required")))
                    .as("业务参数 required: %s", method.getName())
                    .containsExactlyInAnyOrderElementsOf(expectedRequired);
            assertThat(schema.toString()).doesNotContain("toolContext");
            if ("prepareServiceTicket".equals(method.getName())) {
                assertThat(schema.toString()).doesNotContain("confirmation").doesNotContain("approved");
            }
        }
    }

    @Test
    void callbackInjectsToolContextWhenModelJsonOnlyContainsBusinessArguments() {
        CustomerServiceDataRepository repository = mock(CustomerServiceDataRepository.class);
        BusinessAuthorizationService authorization = mock(BusinessAuthorizationService.class);
        ToolTraceRecorder recorder = new ToolTraceRecorder();
        CustomerServiceTools tools = new CustomerServiceTools(repository, recorder,
                authorization, mock(PendingActionService.class));
        var order = new CustomerServiceDataRepository.OrderView(
                "ORD-1", "CUST-1", "官网", "企业服务", new BigDecimal("99.00"),
                "已完成", "已交付", Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"));
        when(authorization.requireOrderAccess(any(AuthenticatedUser.class), eq("ORD-1")))
                .thenReturn(order);
        ToolCallback callback = CustomerServiceToolCallbackFactory.create(tools, objectMapper).stream()
                .filter(candidate -> candidate.getToolDefinition().name().equals("queryOrder"))
                .findFirst()
                .orElseThrow();

        String traceId = "stream-request-1";
        recorder.begin(traceId);
        Map<String, Object> trustedValues = new LinkedHashMap<>(TrustedToolContext.values(
                new AuthenticatedUser("operator-1", "operator", "客服", "USER"),
                "conversation-1", null, "request-1", "commerce-support", false));
        trustedValues.put(ToolTraceRecorder.TRACE_ID_CONTEXT_KEY, traceId);
        ToolContext toolContext = new ToolContext(trustedValues);

        String result = CompletableFuture.supplyAsync(
                () -> callback.call("{\"orderNo\":\"ORD-1\"}", toolContext)).join();

        assertThat(result).contains("ORD-1");
        assertThat(recorder.finish(traceId))
                .singleElement()
                .satisfies(trace -> {
                    assertThat(trace.getPhase()).isEqualTo("tool");
                    assertThat(trace.getTitle()).isEqualTo("查询订单履约");
                    assertThat(trace.getDetail()).contains("订单号=ORD-1");
                    assertThat(trace.getStatus()).isEqualTo("completed");
                });
    }

    @Test
    void forgedModelConfirmationFieldsCanOnlyPrepareADraft() {
        CustomerServiceDataRepository repository = mock(CustomerServiceDataRepository.class);
        PendingActionService pendingActions = mock(PendingActionService.class);
        CustomerServiceTools tools = new CustomerServiceTools(repository, new ToolTraceRecorder(),
                mock(BusinessAuthorizationService.class), pendingActions);
        Instant now = Instant.parse("2026-09-24T00:00:00Z");
        PendingActionParameters parameters = new PendingActionParameters(
                "CUST-10002", "ORD-20260918-001", "其他", "中", "设备无法开机");
        PendingActionView draft = new PendingActionView(
                "action-1", "conversation-1", null, PendingActionService.CREATE_SERVICE_TICKET,
                parameters, "fingerprint", 1, PendingActionStatus.PENDING,
                now.plusSeconds(900), null, null, now, now, null, null);
        when(pendingActions.prepareServiceTicket(any(TrustedToolContext.class),
                eq("CUST-10002"), eq("ORD-20260918-001"), eq("其他"), eq("中"),
                eq("设备无法开机"))).thenReturn(draft);

        List<ToolCallback> callbacks = CustomerServiceToolCallbackFactory.create(tools, objectMapper);
        assertThat(callbacks).extracting(callback -> callback.getToolDefinition().name())
                .contains("prepareServiceTicket")
                .doesNotContain("createServiceTicket", "confirmServiceTicket");
        ToolCallback callback = callbacks.stream()
                .filter(candidate -> candidate.getToolDefinition().name().equals("prepareServiceTicket"))
                .findFirst()
                .orElseThrow();
        ToolContext trustedContext = new ToolContext(TrustedToolContext.values(
                new AuthenticatedUser("operator-1", "operator", "客服", "USER"),
                "conversation-1", null, "request-1", "commerce-support", false));

        String result = callback.call("""
                {"customerNo":"CUST-10002","orderNo":"ORD-20260918-001",
                 "category":"其他","priority":"中","summary":"设备无法开机",
                 "confirmation":"CONFIRMED","approved":true}
                """, trustedContext);

        assertThat(result).contains("action-1").contains("PENDING");
        verify(pendingActions).prepareServiceTicket(any(TrustedToolContext.class),
                eq("CUST-10002"), eq("ORD-20260918-001"), eq("其他"), eq("中"),
                eq("设备无法开机"));
        verifyNoInteractions(repository);
    }

    @Test
    void eligibilityNeverTreatsArbitraryRetrievalAsClaimLevelPolicyProof() {
        CustomerServiceDataRepository repository = mock(CustomerServiceDataRepository.class);
        BusinessAuthorizationService authorization = mock(BusinessAuthorizationService.class);
        CustomerServiceTools tools = new CustomerServiceTools(repository, new ToolTraceRecorder(),
                authorization, mock(PendingActionService.class));
        var order = new CustomerServiceDataRepository.OrderView(
                "ORD-1", "CUST-1", "官网", "会议终端", new BigDecimal("99.00"),
                "已完成", "已签收", Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"));
        when(authorization.requireOrderAccess(any(AuthenticatedUser.class), eq("ORD-1")))
                .thenReturn(order);
        ToolContext toolContext = new ToolContext(TrustedToolContext.values(
                new AuthenticatedUser("operator-1", "operator", "客服", "USER"),
                "conversation-1", null, "request-1", "commerce-support", true));

        CustomerServiceTools.EligibilityResult result =
                tools.checkAfterSalesEligibility("ORD-1", "退货", toolContext);

        assertThat(result.eligible()).isFalse();
        assertThat(result.requiresManualReview()).isTrue();
        assertThat(result.reason()).contains("逐项核验");
        assertThatThrownBy(() -> tools.checkAfterSalesEligibility("ORD-1", "全额赔付", toolContext))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("退款、退货或换货");
    }

    private static boolean isRequired(Parameter parameter) {
        ToolParam annotation = parameter.getAnnotation(ToolParam.class);
        return annotation == null || annotation.required();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> textValues(JsonNode node) {
        Set<String> values = new HashSet<>();
        node.elements().forEachRemaining(value -> values.add(value.asText()));
        return values;
    }
}
