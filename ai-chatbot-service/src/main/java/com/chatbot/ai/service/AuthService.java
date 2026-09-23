package com.chatbot.ai.service;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class AuthService {
    public static final String SESSION_COOKIE = "AI_SERVICE_SESSION";

    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();

    @Value("${app.auth.default-username:admin}")
    private String defaultUsername;
    @Value("${app.auth.default-password:Admin@123456}")
    private String defaultPassword;
    @Value("${app.auth.default-display-name:系统管理员}")
    private String defaultDisplayName;
    @Value("${app.auth.session-hours:24}")
    private long sessionHours;

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_user (
                    id VARCHAR(64) PRIMARY KEY,
                    username VARCHAR(80) NOT NULL UNIQUE,
                    password_hash VARCHAR(100) NOT NULL,
                    display_name VARCHAR(100) NOT NULL,
                    role VARCHAR(30) NOT NULL,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                )
                """);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_user WHERE username = ?", Integer.class, defaultUsername);
        if (count != null && count == 0) {
            jdbcTemplate.update("""
                    INSERT INTO ai_user(id, username, password_hash, display_name, role, enabled)
                    VALUES (?, ?, ?, ?, 'ADMIN', TRUE)
                    """, UUID.randomUUID().toString(), defaultUsername,
                    passwordEncoder.encode(defaultPassword), defaultDisplayName);
        }
    }

    public LoginResult login(String username, String password) {
        var users = jdbcTemplate.query("""
                        SELECT id, username, password_hash, display_name, role, enabled
                        FROM ai_user WHERE username = ?
                        """,
                (rs, rowNum) -> new UserRow(
                        rs.getString("id"), rs.getString("username"), rs.getString("password_hash"),
                        rs.getString("display_name"), rs.getString("role"), rs.getBoolean("enabled")),
                username.trim());
        if (users.isEmpty() || !users.get(0).enabled()
                || !passwordEncoder.matches(password, users.get(0).passwordHash())) {
            throw new IllegalArgumentException("账号或密码错误");
        }
        UserRow row = users.get(0);
        AuthenticatedUser user = new AuthenticatedUser(row.id(), row.username(), row.displayName(), row.role());
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(Duration.ofHours(Math.max(1, sessionHours)));
        sessions.put(token, new SessionEntry(user, expiresAt));
        return new LoginResult(token, user, expiresAt);
    }

    public Optional<AuthenticatedUser> authenticate(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        SessionEntry session = sessions.get(token);
        if (session == null) return Optional.empty();
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session.user());
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    public long sessionSeconds() {
        return Duration.ofHours(Math.max(1, sessionHours)).toSeconds();
    }

    public record LoginResult(String token, AuthenticatedUser user, Instant expiresAt) { }
    private record SessionEntry(AuthenticatedUser user, Instant expiresAt) { }
    private record UserRow(String id, String username, String passwordHash, String displayName,
                           String role, boolean enabled) { }
}
