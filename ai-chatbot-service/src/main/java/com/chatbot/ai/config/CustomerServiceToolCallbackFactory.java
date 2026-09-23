package com.chatbot.ai.config;

import com.chatbot.ai.tools.CustomerServiceTools;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.util.ToolUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds Spring AI M6 method callbacks without exposing framework-injected
 * {@link ToolContext} parameters to the model's JSON schema.
 */
public final class CustomerServiceToolCallbackFactory {

    private CustomerServiceToolCallbackFactory() {
    }

    public static List<ToolCallback> create(CustomerServiceTools toolObject, ObjectMapper objectMapper) {
        return Arrays.stream(ReflectionUtils.getDeclaredMethods(CustomerServiceTools.class))
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .sorted(Comparator.comparing(Method::getName))
                .map(method -> createCallback(method, toolObject, objectMapper))
                .toList();
    }

    private static ToolCallback createCallback(Method method,
                                               CustomerServiceTools toolObject,
                                               ObjectMapper objectMapper) {
        ToolDefinition generated = ToolDefinition.from(method);
        ToolDefinition sanitized = ToolDefinition.builder()
                .name(generated.name())
                .description(generated.description())
                .inputSchema(removeFrameworkParameters(generated.inputSchema(), method, objectMapper))
                .build();

        return MethodToolCallback.builder()
                .toolDefinition(sanitized)
                .toolMetadata(ToolMetadata.from(method))
                .toolMethod(method)
                .toolObject(toolObject)
                .toolCallResultConverter(ToolUtils.getToolCallResultConverter(method))
                .build();
    }

    private static String removeFrameworkParameters(String inputSchema,
                                                    Method method,
                                                    ObjectMapper objectMapper) {
        Set<String> contextParameterNames = Arrays.stream(method.getParameters())
                .filter(parameter -> parameter.getType() == ToolContext.class)
                .map(Parameter::getName)
                .collect(Collectors.toSet());
        if (contextParameterNames.isEmpty()) {
            return inputSchema;
        }

        try {
            JsonNode parsed = objectMapper.readTree(inputSchema);
            if (!(parsed instanceof ObjectNode root)) {
                throw new IllegalStateException("工具输入 Schema 必须是 JSON 对象: " + method.getName());
            }

            JsonNode properties = root.get("properties");
            if (properties instanceof ObjectNode propertyObject) {
                contextParameterNames.forEach(propertyObject::remove);
            }

            JsonNode required = root.get("required");
            if (required instanceof ArrayNode requiredArray) {
                for (int index = requiredArray.size() - 1; index >= 0; index--) {
                    if (contextParameterNames.contains(requiredArray.get(index).asText())) {
                        requiredArray.remove(index);
                    }
                }
            }
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法清理工具输入 Schema: " + method.getName(), exception);
        }
    }
}
