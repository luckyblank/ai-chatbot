package com.chatbot.ai.service;

import com.chatbot.ai.domain.action.PendingActionStatus;
import com.chatbot.ai.repository.PendingActionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Persists a safe, retryable failure only after the execution transaction rolled back. */
@Service
@RequiredArgsConstructor
public class PendingActionFailureRecorder {
    static final String SAFE_RETRYABLE_ERROR = "工单创建失败，正式工单未写入；请刷新状态后安全重试";

    private final PendingActionRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String actionId, String actorUserId, long version) {
        var action = repository.findByIdForUpdate(actionId).orElse(null);
        if (action == null || action.getStatus() != PendingActionStatus.PENDING
                || action.getVersion() != version) {
            return;
        }
        Instant now = Instant.now();
        if (repository.markRetryableFailure(actionId, version, SAFE_RETRYABLE_ERROR, now) == 1) {
            repository.appendAudit(actionId, "EXECUTION_FAILED", actorUserId, version,
                    PendingActionStatus.PENDING, PendingActionStatus.PENDING,
                    "{\"retryable\":true}", now);
        }
    }
}
