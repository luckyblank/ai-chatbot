package com.chatbot.ai.domain.auth;

public record AuthenticatedUser(String id, String username, String displayName, String role) {
}
