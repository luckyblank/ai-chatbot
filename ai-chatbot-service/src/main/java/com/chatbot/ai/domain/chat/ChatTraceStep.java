package com.chatbot.ai.domain.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatTraceStep {
    private String phase;
    private String title;
    private String detail;
    private String status;
    private Long durationMs;
}
