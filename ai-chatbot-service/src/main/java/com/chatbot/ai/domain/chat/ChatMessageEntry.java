package com.chatbot.ai.domain.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageEntry {
    /** Stable logical request identifier shared by a user message and its answer. */
    private String requestId;
    private String role;
    private String content;
    private Instant createdAt;
    @Builder.Default
    private List<ChatAttachment> attachments = new ArrayList<>();
    @Builder.Default
    private List<ChatCitation> citations = new ArrayList<>();
    @Builder.Default
    private List<ChatTraceStep> traces = new ArrayList<>();
}
