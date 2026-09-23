package com.chatbot.ai.domain.vo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RegenerateMessageRequest(
        @NotBlank(message = "消息不能为空")
        @Size(max = 4000, message = "消息不能超过 4000 个字符")
        String content,
        @Size(max = 10, message = "单次最多上传 10 个附件")
        List<String> attachmentIds
) {
}
