package com.chatbot.ai.domain.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatCitation {
    private String documentId;
    private String fileName;
    private Integer pageNumber;
    private String excerpt;
}

