package com.chatbot.ai.domain.vo;

public record CreateConversationRequest(
        String knowledgeBaseId,
        String title,
        String scenarioCode,
        java.util.List<String> knowledgeBaseIds
) {
    public CreateConversationRequest(String knowledgeBaseId, String title, String scenarioCode) {
        this(knowledgeBaseId, title, scenarioCode, null);
    }
}
