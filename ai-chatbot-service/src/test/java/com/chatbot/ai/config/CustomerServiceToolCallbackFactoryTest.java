package com.chatbot.ai.config;

import com.chatbot.ai.repository.CustomerServiceDataRepository;
import com.chatbot.ai.service.ToolTraceRecorder;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomerServiceToolCallbackFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void schemasContainAllBusinessParametersButNeverExposeToolContext() throws Exception {
        CustomerServiceTools tools = new CustomerServiceTools(
                mock(CustomerServiceDataRepository.class), new ToolTraceRecorder());
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
        }
    }

    @Test
    void callbackInjectsToolContextWhenModelJsonOnlyContainsBusinessArguments() {
        CustomerServiceDataRepository repository = mock(CustomerServiceDataRepository.class);
        ToolTraceRecorder recorder = new ToolTraceRecorder();
        CustomerServiceTools tools = new CustomerServiceTools(repository, recorder);
        var order = new CustomerServiceDataRepository.OrderView(
                "ORD-1", "CUST-1", "官网", "企业服务", new BigDecimal("99.00"),
                "已完成", "已交付", Instant.parse("2026-09-20T00:00:00Z"),
                Instant.parse("2026-09-21T00:00:00Z"));
        when(repository.findOrder("ORD-1")).thenReturn(Optional.of(order));
        ToolCallback callback = CustomerServiceToolCallbackFactory.create(tools, objectMapper).stream()
                .filter(candidate -> candidate.getToolDefinition().name().equals("queryOrder"))
                .findFirst()
                .orElseThrow();

        String traceId = "stream-request-1";
        recorder.begin(traceId);
        ToolContext toolContext = new ToolContext(Map.of(ToolTraceRecorder.TRACE_ID_CONTEXT_KEY, traceId));

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
