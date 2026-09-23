package com.chatbot.ai.domain.vo;

import com.chatbot.ai.domain.chat.ChatCitation;
import com.chatbot.ai.domain.chat.ChatTraceStep;

import java.util.List;

public record ChatAnswer(
        String conversationId,
        String answer,
        List<ChatCitation> citations,
        List<ChatTraceStep> traces
) {
}
