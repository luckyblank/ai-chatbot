package com.chatbot.ai.domain.action;

import com.chatbot.ai.repository.CustomerServiceDataRepository;

import java.time.Instant;

public record PendingActionView(
        String actionId,
        String conversationId,
        String runId,
        String actionType,
        PendingActionParameters parameters,
        String parameterFingerprint,
        long version,
        PendingActionStatus status,
        Instant expiresAt,
        CustomerServiceDataRepository.TicketView result,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant confirmedAt,
        Instant cancelledAt
) {
}
