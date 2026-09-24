package com.chatbot.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "app.storage.root=target/test-runtime-data",
        "spring.datasource.url=jdbc:h2:mem:customer-service-test;MODE=MySQL"
})
class ApplicationSmokeTest {
    @Autowired
    private Environment environment;

    @Test
    void applicationStartsWithoutApiKey() {
        assertThat(environment.getDefaultProfiles()).contains("dev");
        assertThat(environment.getProperty("app.ai.enabled", Boolean.class)).isFalse();
    }
}
