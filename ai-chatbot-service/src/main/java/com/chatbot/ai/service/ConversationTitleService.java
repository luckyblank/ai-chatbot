package com.chatbot.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class ConversationTitleService {
    private static final Logger log = LoggerFactory.getLogger(ConversationTitleService.class);
    private static final int MAX_TITLE_CODE_POINTS = 20;
    private static final Pattern TITLE_PREFIX = Pattern.compile("^(?:会话)?标题\\s*[:：]\\s*");
    private static final Pattern EDGE_QUOTES = Pattern.compile("^[\\s`'\"“”‘’《》【】]+|[\\s`'\"“”‘’《》【】]+$");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[。！？!?；;，,、：:…]+$");

    private final ObjectProvider<ChatClient> titleChatClientProvider;

    public ConversationTitleService(
            @Qualifier("titleChatClient") ObjectProvider<ChatClient> titleChatClientProvider) {
        this.titleChatClientProvider = titleChatClientProvider;
    }

    public String generate(String firstQuestion) {
        String fallback = fallbackTitle(firstQuestion);
        ChatClient chatClient = titleChatClientProvider.getIfAvailable();
        if (chatClient == null) {
            return fallback;
        }
        try {
            String generated = chatClient.prompt()
                    .user("请为下面的首次提问生成会话标题：\n<首次提问>\n"
                            + firstQuestion + "\n</首次提问>")
                    .call()
                    .content();
            String normalized = normalizeTitle(generated);
            return normalized.isBlank() ? fallback : normalized;
        } catch (RuntimeException exception) {
            // 命名属于增强能力，不能因为模型暂时不可用而让已经成功的主回答失败。
            log.warn("会话标题生成失败，已使用本地回退：{}", exception.getClass().getSimpleName());
            return fallback;
        }
    }

    String fallbackTitle(String question) {
        String normalized = normalizeTitle(question);
        return normalized.isBlank() ? "客户服务咨询" : normalized;
    }

    String normalizeTitle(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value
                .replaceAll("[\\p{Cntrl}\\s]+", " ")
                .trim();
        normalized = TITLE_PREFIX.matcher(normalized).replaceFirst("");
        normalized = EDGE_QUOTES.matcher(normalized).replaceAll("").trim();
        normalized = TRAILING_PUNCTUATION.matcher(normalized).replaceAll("").trim();
        normalized = truncateCodePoints(normalized, MAX_TITLE_CODE_POINTS);
        normalized = EDGE_QUOTES.matcher(normalized).replaceAll("").trim();
        return TRAILING_PUNCTUATION.matcher(normalized).replaceAll("").trim();
    }

    private String truncateCodePoints(String value, int maxCodePoints) {
        int codePointCount = value.codePointCount(0, value.length());
        if (codePointCount <= maxCodePoints) {
            return value;
        }
        int endIndex = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, endIndex).trim();
    }
}
