package com.chatbot.ai.domain.action;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingAction {
    private String actionId;
    private String stableRequestKey;
    private String actorUserId;
    private String conversationId;
    private String runId;
    private String scenarioCode;
    private String actionType;
    private PendingActionParameters parameters;
    private String parameterFingerprint;
    private long version;
    private PendingActionStatus status;
    private Instant expiresAt;
    private String ticketId;
    private String ticketNo;
    private String resultJson;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant confirmedAt;
    private Instant cancelledAt;
}
