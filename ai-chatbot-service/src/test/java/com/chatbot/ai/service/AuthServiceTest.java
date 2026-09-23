package com.chatbot.ai.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "app.storage.root=target/auth-service-test-runtime",
        "spring.datasource.url=jdbc:h2:mem:auth-service-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.auth.default-username=security-admin",
        "app.auth.default-password=Security-Test-Password"
})
class AuthServiceTest {
    @Autowired AuthService service;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void activeSessionUsesCurrentRoleAndIsRevokedWhenAccountIsDisabled() {
        AuthService.LoginResult login = service.login("security-admin", "Security-Test-Password");

        jdbcTemplate.update("UPDATE ai_user SET role='USER' WHERE id=?", login.user().id());
        assertThat(service.authenticate(login.token()))
                .get()
                .extracting(user -> user.role())
                .isEqualTo("USER");

        jdbcTemplate.update("UPDATE ai_user SET enabled=FALSE WHERE id=?", login.user().id());
        assertThat(service.authenticate(login.token())).isEmpty();

        jdbcTemplate.update("UPDATE ai_user SET enabled=TRUE WHERE id=?", login.user().id());
        assertThat(service.authenticate(login.token()))
                .as("a revoked session must not become valid again after the account is re-enabled")
                .isEmpty();
    }
}
