package com.chatbot.ai.repository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
@RequiredArgsConstructor
public class BusinessScopeRepository {
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_operator_business_scope (
                    operator_user_id VARCHAR(64) NOT NULL,
                    business_subject_no VARCHAR(40) NOT NULL,
                    granted_at TIMESTAMP NOT NULL,
                    PRIMARY KEY (operator_user_id, business_subject_no),
                    INDEX idx_operator_scope_subject (business_subject_no)
                )
                """);
    }

    public boolean hasScope(String operatorUserId, String businessSubjectNo) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ai_operator_business_scope
                WHERE operator_user_id=? AND business_subject_no=?
                """, Integer.class, operatorUserId, businessSubjectNo);
        return count != null && count > 0;
    }

    /** Intended for explicit administration and isolated tests, never model input. */
    public void grant(String operatorUserId, String businessSubjectNo) {
        int updated = jdbcTemplate.update("""
                UPDATE ai_operator_business_scope SET granted_at=?
                WHERE operator_user_id=? AND business_subject_no=?
                """, Timestamp.from(Instant.now()), operatorUserId, businessSubjectNo);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO ai_operator_business_scope(operator_user_id,business_subject_no,granted_at)
                    VALUES(?,?,?)
                    """, operatorUserId, businessSubjectNo, Timestamp.from(Instant.now()));
        }
    }
}
