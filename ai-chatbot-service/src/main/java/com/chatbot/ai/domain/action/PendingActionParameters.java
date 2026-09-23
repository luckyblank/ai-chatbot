package com.chatbot.ai.domain.action;

public record PendingActionParameters(
        String customerNo,
        String orderNo,
        String category,
        String priority,
        String summary
) {
}
