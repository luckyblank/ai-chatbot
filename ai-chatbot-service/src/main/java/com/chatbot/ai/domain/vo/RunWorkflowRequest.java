package com.chatbot.ai.domain.vo;

import java.util.Map;

public record RunWorkflowRequest(String conversationId, Map<String, Object> input,
                                 String knowledgeBaseId) {
}
