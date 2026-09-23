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
public class ConversationSession {
    private String id;
    private String knowledgeBaseId;
    private String scenarioCode;
    private String title;
    /** True only after an explicit rename, so delayed AI naming can never overwrite the user. */
    private boolean titleCustomized;
    private Instant createdAt;
    private Instant updatedAt;
    @Builder.Default
    private List<ChatMessageEntry> messages = new ArrayList<>();
}
