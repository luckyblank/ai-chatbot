package com.chatbot.ai.domain.vo;

public record CreateConversationRequest(
        String knowledgeBaseId,
        String title,
        String scenarioCode
) {
}
