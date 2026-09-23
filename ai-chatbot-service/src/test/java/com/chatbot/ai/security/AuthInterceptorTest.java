package com.chatbot.ai.security;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthInterceptorTest {
    private static final AuthenticatedUser USER =
            new AuthenticatedUser("operator-1", "operator", "客服", "USER");

    private AuthService authService;
    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        interceptor = new AuthInterceptor(authService, new ObjectMapper());
        when(authService.authenticate("session-token")).thenReturn(Optional.of(USER));
    }

    @Test
    void safeRequestNeedsSessionButNotCsrfHeader() throws Exception {
        MockHttpServletRequest request = request("GET");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute(AuthInterceptor.USER_ATTRIBUTE)).isEqualTo(USER);
    }

    @Test
    void stateChangingRequestRejectsMissingOrForgedCsrfToken() throws Exception {
        for (String token : new String[]{null, "forged-token"}) {
            MockHttpServletRequest request = request("POST");
            if (token != null) request.addHeader(AuthService.CSRF_HEADER, token);
            MockHttpServletResponse response = new MockHttpServletResponse();

            assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(response.getContentAsString()).contains("CSRF");
        }
    }

    @Test
    void stateChangingRequestAcceptsTokenBoundToAuthenticatedSession() throws Exception {
        when(authService.validateCsrf("session-token", "csrf-token")).thenReturn(true);
        MockHttpServletRequest request = request("POST");
        request.addHeader(AuthService.CSRF_HEADER, "csrf-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        assertThat(request.getAttribute(AuthInterceptor.USER_ATTRIBUTE)).isEqualTo(USER);
    }

    private MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/pending-actions/action-1/confirm");
        request.setCookies(new Cookie(AuthService.SESSION_COOKIE, "session-token"));
        return request;
    }
}
