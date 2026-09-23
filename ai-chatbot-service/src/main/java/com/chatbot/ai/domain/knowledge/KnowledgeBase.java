package com.chatbot.ai.domain.knowledge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBase {
    private String id;
    private String name;
    private String description;
    private Instant createdAt;
    private Instant updatedAt;
}

