package com.chatbot.ai.controller;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.domain.vo.LoginRequest;
import com.chatbot.ai.security.AuthInterceptor;
import com.chatbot.ai.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @PostMapping("/login")
    public AuthenticatedUser login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        AuthService.LoginResult result = authService.login(request.username(), request.password());
        var cookieBuilder = ResponseCookie.from(AuthService.SESSION_COOKIE, result.token())
                .httpOnly(true).secure(false).sameSite("Lax").path("/");
        if (!Boolean.FALSE.equals(request.rememberMe())) {
            cookieBuilder.maxAge(Duration.ofSeconds(authService.sessionSeconds()));
        }
        ResponseCookie cookie = cookieBuilder.build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return result.user();
    }

    @GetMapping("/me")
    public AuthenticatedUser me(HttpServletRequest request) {
        return (AuthenticatedUser) request.getAttribute(AuthInterceptor.USER_ATTRIBUTE);
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(AuthInterceptor.cookieValue(request, AuthService.SESSION_COOKIE));
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(AuthService.SESSION_COOKIE, "")
                .httpOnly(true).secure(false).sameSite("Lax").path("/").maxAge(Duration.ZERO).build().toString());
    }
}
