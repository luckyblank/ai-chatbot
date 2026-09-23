package com.chatbot.ai.domain.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "请输入账号") @Size(max = 80) String username,
        @NotBlank(message = "请输入密码") @Size(max = 200) String password,
        Boolean rememberMe
) {
}
