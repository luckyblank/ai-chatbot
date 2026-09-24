package com.chatbot.ai;

import com.chatbot.ai.controller.SystemStatusController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("docker")
@SpringBootTest(properties = {
        "DB_URL=jdbc:h2:mem:docker-profile-test;MODE=MySQL",
        "DB_USERNAME=sa",
        "DB_PASSWORD=",
        "DEFAULT_ADMIN_PASSWORD=test-admin-password",
        "CORS_ALLOWED_ORIGINS=http://127.0.0.1:15175",
        "AI_ENABLED=false",
        "AI_DASHSCOPE_API_KEY=disabled-when-ai-is-off",
        "APP_STORAGE_ROOT=target/test-docker-profile-data"
})
class DockerProfileSmokeTest {
    @Autowired
    private Environment environment;
    @Autowired
    private SystemStatusController systemStatusController;

    @Test
    void dockerProfileStartsOfflineWithoutDevelopmentDefaults() {
        assertThat(environment.getActiveProfiles()).contains("docker");
        assertThat(environment.getProperty("app.storage.root"))
                .isEqualTo("target/test-docker-profile-data");
        assertThat(systemStatusController.aiStatus().enabled()).isFalse();
        assertThat(systemStatusController.aiStatus().configured()).isFalse();
    }
}
