package com.chatbot.ai.repository;

import com.chatbot.ai.domain.chat.ChatMessageEntry;
import com.chatbot.ai.domain.chat.ConversationSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsLegacySingleBaseAndFiltersNewMultiBaseByAnySelectedId() throws Exception {
        Files.writeString(temporaryDirectory.resolve("conversations.json"), """
                [{"id":"legacy","knowledgeBaseId":"kb-old","scenarioCode":"general",
                  "title":"旧会话","createdAt":"2026-01-01T00:00:00Z",
                  "updatedAt":"2026-01-01T00:00:00Z","messages":[]}]
                """);
        ConversationRepository repository = repository();
        assertThat(repository.findById("legacy").orElseThrow().getKnowledgeBaseIds())
                .containsExactly("kb-old");

        ConversationSession multi = newSession("multi", "新会话");
        multi.setKnowledgeBaseId("kb-a");
        multi.setKnowledgeBaseIds(java.util.List.of("kb-a", "kb-b"));
        repository.save(multi);
        assertThat(repository.findByKnowledgeBaseId("kb-b"))
                .extracting(ConversationSession::getId).containsExactly("multi");
        assertThat(repository.findByKnowledgeBaseId("kb-old"))
                .extracting(ConversationSession::getId).containsExactly("legacy");
    }

    @Test
    void claimsAutoTitleOnlyForFirstUserQuestionOfDefaultConversation() throws Exception {
        ConversationRepository repository = repository();
        repository.save(newSession("conversation-1", "电商售后 · 新对话"));

        ConversationRepository.AppendExchangeResult first = repository.appendExchange(
                "conversation-1", message("user", "第一次提问"), message("assistant", "第一次回答"));
        repository.renameDefaultTitle("conversation-1", "七日无理由退货咨询");
        ConversationRepository.AppendExchangeResult second = repository.appendExchange(
                "conversation-1", message("user", "第二次提问"), message("assistant", "第二次回答"));

        assertThat(first.shouldGenerateTitle()).isTrue();
        assertThat(second.shouldGenerateTitle()).isFalse();
        assertThat(repository.findById("conversation-1").orElseThrow().getTitle())
                .isEqualTo("七日无理由退货咨询");
    }

    @Test
    void neverOverwritesManualRenameWhileTitleModelIsRunning() throws Exception {
        ConversationRepository repository = repository();
        repository.save(newSession("conversation-2", "新对话"));
        ConversationRepository.AppendExchangeResult first = repository.appendExchange(
                "conversation-2", message("user", "查询订单"), message("assistant", "正在查询"));

        // Even renaming back to a value that looks like a default is an explicit user choice.
        repository.rename("conversation-2", "新对话");
        repository.renameDefaultTitle("conversation-2", "模型生成的名称");

        assertThat(first.shouldGenerateTitle()).isTrue();
        assertThat(repository.findById("conversation-2").orElseThrow().getTitle())
                .isEqualTo("新对话");
    }

    @Test
    void replacesEditedUserMessageAndAllFollowingMessages() throws Exception {
        ConversationRepository repository = repository();
        repository.save(newSession("conversation-3", "测试会话"));
        repository.appendExchange("conversation-3", message("user", "第一次提问"), message("assistant", "第一次回答"));
        repository.appendExchange("conversation-3", message("user", "原始问题"), message("assistant", "原始回答"));

        repository.replaceExchange("conversation-3", 2,
                message("user", "修改后的问题"), message("assistant", "重新生成的回答"));

        assertThat(repository.findById("conversation-3").orElseThrow().getMessages())
                .extracting(ChatMessageEntry::getContent)
                .containsExactly("第一次提问", "第一次回答", "修改后的问题", "重新生成的回答");
        assertThatThrownBy(() -> repository.replaceExchange("conversation-3", 1,
                        message("user", "不能修改回答"), message("assistant", "回答")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("仅支持编辑用户发送的消息");
    }

    private ConversationRepository repository() throws Exception {
        ConversationRepository repository = new ConversationRepository(
                new ObjectMapper().registerModule(new JavaTimeModule()), temporaryDirectory.toString());
        repository.initialize();
        return repository;
    }

    private ConversationSession newSession(String id, String title) {
        Instant now = Instant.now();
        return ConversationSession.builder()
                .id(id)
                .title(title)
                .scenarioCode("general")
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private ChatMessageEntry message(String role, String content) {
        return ChatMessageEntry.builder()
                .role(role)
                .content(content)
                .createdAt(Instant.now())
                .build();
    }
}
