package com.chatbot.ai.controller;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class AuthControllerTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthService authService = mock(AuthService.class);
        AuthenticatedUser user = new AuthenticatedUser("1", "admin", "系统管理员", "ADMIN");
        when(authService.login("admin", "password"))
                .thenReturn(new AuthService.LoginResult("test-token", user, Instant.now().plusSeconds(86400)));
        when(authService.sessionSeconds()).thenReturn(86400L);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService)).build();
    }

    @Test
    void omittedOrTrueRememberMeKeepsPersistentCookie() throws Exception {
        for (String body : new String[] {
                "{\"username\":\"admin\",\"password\":\"password\"}",
                "{\"username\":\"admin\",\"password\":\"password\",\"rememberMe\":true}"
        }) {
            String cookie = loginCookie(body);
            assertThat(cookie).contains("AI_SERVICE_SESSION=test-token", "Max-Age=86400");
        }
    }

    @Test
    void falseRememberMeUsesBrowserSessionCookie() throws Exception {
        String cookie = loginCookie("{\"username\":\"admin\",\"password\":\"password\",\"rememberMe\":false}");

        assertThat(cookie).contains("AI_SERVICE_SESSION=test-token", "HttpOnly", "SameSite=Lax");
        assertThat(cookie).doesNotContain("Max-Age", "Expires");
    }

    private String loginCookie(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getHeader("Set-Cookie");
    }
}
