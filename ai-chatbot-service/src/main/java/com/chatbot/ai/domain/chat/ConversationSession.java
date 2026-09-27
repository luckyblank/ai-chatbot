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
    /**
     * Authenticated operator that created the conversation. Legacy rows/files may
     * be null and are deliberately visible only to administrators.
     */
    private String ownerId;
    private String knowledgeBaseId;
    /** Null in legacy persisted conversations; then knowledgeBaseId is used. */
    private List<String> knowledgeBaseIds;

    public List<String> getKnowledgeBaseIds() {
        return selectedKnowledgeBaseIds();
    }

    public List<String> selectedKnowledgeBaseIds() {
        if (knowledgeBaseIds != null) return knowledgeBaseIds;
        return knowledgeBaseId == null || knowledgeBaseId.isBlank()
                ? List.of() : List.of(knowledgeBaseId);
    }
    private String scenarioCode;
    private String title;
    /** True only after an explicit rename, so delayed AI naming can never overwrite the user. */
    private boolean titleCustomized;
    private Instant createdAt;
    private Instant updatedAt;
    @Builder.Default
    private List<ChatMessageEntry> messages = new ArrayList<>();
}
