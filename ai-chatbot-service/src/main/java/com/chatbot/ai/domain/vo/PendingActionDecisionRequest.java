package com.chatbot.ai.domain.vo;

import jakarta.validation.constraints.Min;

public record PendingActionDecisionRequest(
        @Min(value = 1, message = "动作版本必须大于 0") long expectedVersion
) {
}
