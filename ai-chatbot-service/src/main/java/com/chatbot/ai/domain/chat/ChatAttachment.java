package com.chatbot.ai.domain.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatAttachment {
    private String id;
    private String conversationId;
    private String name;
    private String contentType;
    private long size;
    private boolean image;
    private String storageKey;
    private String contentUrl;
    private Instant createdAt;
}
