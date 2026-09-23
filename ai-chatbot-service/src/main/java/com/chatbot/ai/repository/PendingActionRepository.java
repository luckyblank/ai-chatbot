package com.chatbot.ai.repository;

import com.chatbot.ai.domain.action.PendingAction;
import com.chatbot.ai.domain.action.PendingActionParameters;
import com.chatbot.ai.domain.action.PendingActionStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PendingActionRepository {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_pending_action (
                    action_id VARCHAR(64) PRIMARY KEY,
                    stable_request_key CHAR(64) NOT NULL UNIQUE,
                    actor_user_id VARCHAR(64) NOT NULL,
                    conversation_id VARCHAR(64),
                    run_id VARCHAR(64),
                    scenario_code VARCHAR(80) NOT NULL,
                    action_type VARCHAR(64) NOT NULL,
                    parameters_json LONGTEXT NOT NULL,
                    parameter_fingerprint CHAR(64) NOT NULL,
                    action_version BIGINT NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    expires_at TIMESTAMP(6) NOT NULL,
                    ticket_id VARCHAR(64),
                    ticket_no VARCHAR(50),
                    result_json LONGTEXT,
                    last_error VARCHAR(500),
                    created_at TIMESTAMP(6) NOT NULL,
                    updated_at TIMESTAMP(6) NOT NULL,
                    confirmed_at TIMESTAMP(6),
                    cancelled_at TIMESTAMP(6),
                    INDEX idx_pending_action_actor_conversation (actor_user_id, conversation_id, updated_at),
                    INDEX idx_pending_action_status_expiry (status, expires_at)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_pending_action_audit (
                    id VARCHAR(64) PRIMARY KEY,
                    action_id VARCHAR(64) NOT NULL,
                    event_type VARCHAR(40) NOT NULL,
                    actor_user_id VARCHAR(64) NOT NULL,
                    action_version BIGINT NOT NULL,
                    from_status VARCHAR(24),
                    to_status VARCHAR(24),
                    detail_json LONGTEXT,
                    created_at TIMESTAMP(6) NOT NULL,
                    INDEX idx_action_audit_action (action_id, created_at)
                )
                """);
    }

    /**
     * MySQL's unique stable_request_key serializes the initial creation race. The
     * no-op duplicate branch deliberately never changes a previously displayed
     * draft; callers lock and reconcile it afterwards.
     */
    public void insertIfAbsent(PendingAction action) {
        jdbcTemplate.update("""
                INSERT INTO ai_pending_action(
                    action_id,stable_request_key,actor_user_id,conversation_id,run_id,scenario_code,
                    action_type,parameters_json,parameter_fingerprint,action_version,status,expires_at,
                    ticket_id,ticket_no,result_json,last_error,created_at,updated_at,confirmed_at,cancelled_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE action_id=action_id
                """, action.getActionId(), action.getStableRequestKey(), action.getActorUserId(),
                action.getConversationId(), action.getRunId(), action.getScenarioCode(), action.getActionType(),
                writeParameters(action.getParameters()), action.getParameterFingerprint(), action.getVersion(),
                action.getStatus().name(), timestamp(action.getExpiresAt()), action.getTicketId(),
                action.getTicketNo(), action.getResultJson(), action.getLastError(),
                timestamp(action.getCreatedAt()), timestamp(action.getUpdatedAt()),
                timestamp(action.getConfirmedAt()), timestamp(action.getCancelledAt()));
    }

    public Optional<PendingAction> findById(String actionId) {
        return jdbcTemplate.query("SELECT * FROM ai_pending_action WHERE action_id=?",
                this::mapAction, actionId).stream().findFirst();
    }

    public Optional<PendingAction> findByStableRequestKeyForUpdate(String stableRequestKey) {
        return jdbcTemplate.query("SELECT * FROM ai_pending_action WHERE stable_request_key=? FOR UPDATE",
                this::mapAction, stableRequestKey).stream().findFirst();
    }

    public Optional<PendingAction> findByIdForUpdate(String actionId) {
        return jdbcTemplate.query("SELECT * FROM ai_pending_action WHERE action_id=? FOR UPDATE",
                this::mapAction, actionId).stream().findFirst();
    }

    public List<PendingAction> findByActorAndConversation(String actorUserId, String conversationId) {
        return jdbcTemplate.query("""
                SELECT * FROM ai_pending_action
                WHERE actor_user_id=? AND conversation_id=?
                ORDER BY updated_at DESC, action_id DESC
                """, this::mapAction, actorUserId, conversationId);
    }

    public int updateDraft(String actionId, long expectedVersion,
                           PendingActionParameters parameters, String fingerprint,
                           long newVersion, Instant expiresAt, String scenarioCode, Instant updatedAt) {
        return jdbcTemplate.update("""
                UPDATE ai_pending_action
                SET parameters_json=?, parameter_fingerprint=?, action_version=?, expires_at=?,
                    scenario_code=?, updated_at=?, last_error=NULL
                WHERE action_id=? AND action_version=? AND status='PENDING'
                """, writeParameters(parameters), fingerprint, newVersion, timestamp(expiresAt),
                scenarioCode, timestamp(updatedAt), actionId, expectedVersion);
    }

    public int markSucceeded(String actionId, long expectedVersion,
                             String ticketId, String ticketNo, String resultJson, Instant now) {
        return jdbcTemplate.update("""
                UPDATE ai_pending_action
                SET status='SUCCEEDED', ticket_id=?, ticket_no=?, result_json=?, last_error=NULL,
                    confirmed_at=?, updated_at=?
                WHERE action_id=? AND action_version=? AND status='PENDING'
                """, ticketId, ticketNo, resultJson, timestamp(now), timestamp(now),
                actionId, expectedVersion);
    }

    public int markCancelled(String actionId, long expectedVersion, Instant now) {
        return jdbcTemplate.update("""
                UPDATE ai_pending_action
                SET status='CANCELLED', cancelled_at=?, updated_at=?, last_error=NULL
                WHERE action_id=? AND action_version=? AND status='PENDING'
                """, timestamp(now), timestamp(now), actionId, expectedVersion);
    }

    public int markExpired(String actionId, long expectedVersion, Instant now) {
        return jdbcTemplate.update("""
                UPDATE ai_pending_action
                SET status='EXPIRED', updated_at=?, last_error=NULL
                WHERE action_id=? AND action_version=? AND status='PENDING'
                """, timestamp(now), actionId, expectedVersion);
    }

    public int markRetryableFailure(String actionId, long expectedVersion,
                                    String safeError, Instant now) {
        return jdbcTemplate.update("""
                UPDATE ai_pending_action
                SET last_error=?, updated_at=?
                WHERE action_id=? AND action_version=? AND status='PENDING'
                """, safeError, timestamp(now), actionId, expectedVersion);
    }

    public void appendAudit(String actionId, String eventType, String actorUserId,
                            long version, PendingActionStatus fromStatus,
                            PendingActionStatus toStatus, String detailJson, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO ai_pending_action_audit(
                    id,action_id,event_type,actor_user_id,action_version,
                    from_status,to_status,detail_json,created_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID().toString(), actionId, eventType, actorUserId, version,
                fromStatus == null ? null : fromStatus.name(), toStatus == null ? null : toStatus.name(),
                detailJson, timestamp(now));
    }

    public long countAudits(String actionId, String eventType) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ai_pending_action_audit WHERE action_id=? AND event_type=?
                """, Long.class, actionId, eventType);
        return count == null ? 0 : count;
    }

    private PendingAction mapAction(ResultSet rs, int rowNum) throws SQLException {
        return PendingAction.builder()
                .actionId(rs.getString("action_id"))
                .stableRequestKey(rs.getString("stable_request_key"))
                .actorUserId(rs.getString("actor_user_id"))
                .conversationId(rs.getString("conversation_id"))
                .runId(rs.getString("run_id"))
                .scenarioCode(rs.getString("scenario_code"))
                .actionType(rs.getString("action_type"))
                .parameters(readParameters(rs.getString("parameters_json")))
                .parameterFingerprint(rs.getString("parameter_fingerprint"))
                .version(rs.getLong("action_version"))
                .status(PendingActionStatus.valueOf(rs.getString("status")))
                .expiresAt(instant(rs.getTimestamp("expires_at")))
                .ticketId(rs.getString("ticket_id"))
                .ticketNo(rs.getString("ticket_no"))
                .resultJson(rs.getString("result_json"))
                .lastError(rs.getString("last_error"))
                .createdAt(instant(rs.getTimestamp("created_at")))
                .updatedAt(instant(rs.getTimestamp("updated_at")))
                .confirmedAt(instant(rs.getTimestamp("confirmed_at")))
                .cancelledAt(instant(rs.getTimestamp("cancelled_at")))
                .build();
    }

    private String writeParameters(PendingActionParameters parameters) {
        try {
            return objectMapper.writeValueAsString(parameters);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("工单草案参数无法序列化", exception);
        }
    }

    private PendingActionParameters readParameters(String json) {
        try {
            return objectMapper.readValue(json, PendingActionParameters.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的工单草案参数无法解析", exception);
        }
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
