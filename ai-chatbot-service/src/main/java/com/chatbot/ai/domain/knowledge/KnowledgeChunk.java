package com.chatbot.ai.domain.knowledge;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunk {
    private String id;
    private String knowledgeBaseId;
    private String documentId;
    private int sequence;
    private Integer pageNumber;
    private int characterCount;
    private String content;
}
