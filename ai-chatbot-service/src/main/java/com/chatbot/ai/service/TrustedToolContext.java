package com.chatbot.ai.service;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-authenticated values passed to Spring AI tools outside the model-visible
 * JSON schema. Never populate these keys from prompt text or workflow input.
 */
public record TrustedToolContext(
        AuthenticatedUser actor,
        String conversationId,
        String runId,
        String requestId,
        String scenarioCode,
        boolean policyEvidenceAvailable
) {
    public static final String ACTOR_ID = "customerServiceActorId";
    public static final String ACTOR_ROLE = "customerServiceActorRole";
    public static final String ACTOR_USERNAME = "customerServiceActorUsername";
    public static final String ACTOR_DISPLAY_NAME = "customerServiceActorDisplayName";
    public static final String CONVERSATION_ID = "customerServiceConversationId";
    public static final String RUN_ID = "customerServiceRunId";
    public static final String REQUEST_ID = "customerServiceRequestId";
    public static final String SCENARIO_CODE = "customerServiceScenarioCode";
    public static final String POLICY_EVIDENCE_AVAILABLE = "customerServicePolicyEvidenceAvailable";

    public static Map<String, Object> values(AuthenticatedUser actor,
                                             String conversationId,
                                             String runId,
                                             String requestId,
                                             String scenarioCode,
                                             boolean policyEvidenceAvailable) {
        if (actor == null || actor.id() == null || actor.id().isBlank()) {
            throw new IllegalArgumentException("可信工具上下文缺少登录主体");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(ACTOR_ID, actor.id());
        values.put(ACTOR_ROLE, blank(actor.role()));
        values.put(ACTOR_USERNAME, blank(actor.username()));
        values.put(ACTOR_DISPLAY_NAME, blank(actor.displayName()));
        putIfPresent(values, CONVERSATION_ID, conversationId);
        putIfPresent(values, RUN_ID, runId);
        putIfPresent(values, REQUEST_ID, requestId);
        putIfPresent(values, SCENARIO_CODE, scenarioCode);
        values.put(POLICY_EVIDENCE_AVAILABLE, policyEvidenceAvailable);
        return Map.copyOf(values);
    }

    public static TrustedToolContext require(ToolContext toolContext) {
        Map<String, Object> values = toolContext == null ? null : toolContext.getContext();
        String actorId = string(values, ACTOR_ID);
        if (actorId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "业务工具缺少可信登录主体");
        }
        AuthenticatedUser actor = new AuthenticatedUser(actorId,
                defaultString(string(values, ACTOR_USERNAME), "authenticated-user"),
                defaultString(string(values, ACTOR_DISPLAY_NAME), "已认证操作员"),
                defaultString(string(values, ACTOR_ROLE), "USER"));
        return new TrustedToolContext(actor, string(values, CONVERSATION_ID),
                string(values, RUN_ID), string(values, REQUEST_ID),
                string(values, SCENARIO_CODE), booleanValue(values, POLICY_EVIDENCE_AVAILABLE));
    }

    public void requireActionRequest() {
        if ((conversationId == null && runId == null) || requestId == null || scenarioCode == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "准备业务动作需要可信的会话或运行、请求和场景上下文");
        }
    }

    private static void putIfPresent(Map<String, Object> values, String key, String value) {
        if (value != null && !value.isBlank()) values.put(key, value.trim());
    }

    private static String string(Map<String, Object> values, String key) {
        if (values == null) return null;
        Object value = values.get(key);
        if (value == null || value.toString().isBlank()) return null;
        return value.toString().trim();
    }

    private static boolean booleanValue(Map<String, Object> values, String key) {
        if (values == null) return false;
        Object value = values.get(key);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private static String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
