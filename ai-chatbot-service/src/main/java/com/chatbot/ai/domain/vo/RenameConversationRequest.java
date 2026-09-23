package com.chatbot.ai.domain.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameConversationRequest(
        @NotBlank(message = "会话名称不能为空") @Size(max = 120, message = "会话名称不能超过 120 个字符") String title
) {
}
