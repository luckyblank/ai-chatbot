package com.chatbot.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "app.storage.root=target/test-runtime-data",
        "spring.datasource.url=jdbc:h2:mem:customer-service-test;MODE=MySQL"
})
class ApplicationSmokeTest {
    @Test
    void applicationStartsWithoutApiKey() {
    }
}

