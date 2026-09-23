package com.chatbot.ai.service;

import com.chatbot.ai.domain.knowledge.KnowledgeBase;
import com.chatbot.ai.repository.KnowledgeCatalogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBaseServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void updatesAndPersistsKnowledgeBaseMetadataWithoutChangingCreationTime() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        KnowledgeCatalogRepository repository = new KnowledgeCatalogRepository(
                objectMapper, temporaryDirectory.toString());
        repository.initialize();
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        repository.saveKnowledgeBase(KnowledgeBase.builder()
                .id("kb-1")
                .name("旧名称")
                .description("旧描述")
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .build());

        KnowledgeBaseService service = new KnowledgeBaseService(repository, 1024);
        KnowledgeBase updated = service.updateKnowledgeBase("kb-1", "  售后政策库  ", "  退款与退货规则  ");

        assertThat(updated.getName()).isEqualTo("售后政策库");
        assertThat(updated.getDescription()).isEqualTo("退款与退货规则");
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfter(createdAt);

        KnowledgeCatalogRepository reloaded = new KnowledgeCatalogRepository(
                objectMapper, temporaryDirectory.toString());
        reloaded.initialize();
        assertThat(reloaded.findKnowledgeBase("kb-1")).get()
                .extracting(KnowledgeBase::getName, KnowledgeBase::getDescription)
                .containsExactly("售后政策库", "退款与退货规则");
    }

    @Test
    void rejectsUpdateForMissingKnowledgeBase() throws Exception {
        KnowledgeCatalogRepository repository = new KnowledgeCatalogRepository(
                new ObjectMapper().registerModule(new JavaTimeModule()), temporaryDirectory.toString());
        repository.initialize();
        KnowledgeBaseService service = new KnowledgeBaseService(repository, 1024);

        assertThatThrownBy(() -> service.updateKnowledgeBase("missing", "名称", "描述"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
