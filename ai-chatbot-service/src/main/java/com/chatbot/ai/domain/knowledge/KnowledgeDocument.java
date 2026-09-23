package com.chatbot.ai.domain.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
public class KnowledgeDocument {
    private String id;
    private String knowledgeBaseId;
    private String fileName;
    private String contentType;
    private long size;
    private String storageKey;
    private DocumentStatus status;
    private String errorMessage;
    private Instant createdAt;
    private Instant indexedAt;
    @Builder.Default
    private List<String> chunkIds = new ArrayList<>();

    @JsonIgnore
    public boolean isIndexed() {
        return status == DocumentStatus.READY;
    }
}

