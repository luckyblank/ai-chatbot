package com.chatbot.ai.controller;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.security.AuthInterceptor;
import com.chatbot.ai.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SystemStatusControllerTest {
    private static final String PATH = "/api/v1/system/ai-status";

    @Test
    void requiresAuthenticatedSession() throws Exception {
        MockMvc mvc = mvc(new MockEnvironment().withProperty("app.ai.enabled", "false"));

        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void disabledAiIsNotReportedAsConfiguredEvenWhenAHostKeyExists() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("app.ai.enabled", "false")
                .withProperty("spring.ai.openai.api-key", "real-key-must-not-be-returned")
                .withProperty("spring.ai.openai.chat.options.model", "qwen3.8-max");

        mvc(environment).perform(get(PATH).cookie(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.model").value("qwen3.8-max"));
        assertThat(new SystemStatusController(environment).aiStatus().toString())
                .doesNotContain("real-key-must-not-be-returned");
    }

    @Test
    void enabledAiNeedsANonPlaceholderKey() throws Exception {
        for (String key : new String[]{"", "   ", "disabled-when-ai-is-off", "replace-only-when-ai-enabled"}) {
            MockEnvironment environment = new MockEnvironment()
                    .withProperty("app.ai.enabled", "true")
                    .withProperty("spring.ai.openai.api-key", key);
            mvc(environment).perform(get(PATH).cookie(session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.configured").value(false));
        }

        MockEnvironment configured = new MockEnvironment()
                .withProperty("app.ai.enabled", "true")
                .withProperty("spring.ai.openai.api-key", "real-key-must-not-be-returned");
        mvc(configured).perform(get(PATH).cookie(session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.provider").value("DashScope"));
    }

    private MockMvc mvc(MockEnvironment environment) {
        AuthService authService = mock(AuthService.class);
        when(authService.authenticate("valid-session"))
                .thenReturn(Optional.of(new AuthenticatedUser("user-1", "user", "客服", "USER")));
        AuthInterceptor interceptor = new AuthInterceptor(authService, new ObjectMapper());
        return MockMvcBuilders.standaloneSetup(new SystemStatusController(environment))
                .addInterceptors(interceptor)
                .build();
    }

    private Cookie session() {
        return new Cookie(AuthService.SESSION_COOKIE, "valid-session");
    }
}
