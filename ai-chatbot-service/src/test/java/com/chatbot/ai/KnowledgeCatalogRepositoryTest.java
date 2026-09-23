package com.chatbot.ai;

import com.chatbot.ai.domain.knowledge.KnowledgeBase;
import com.chatbot.ai.domain.knowledge.KnowledgeDocument;
import com.chatbot.ai.repository.KnowledgeCatalogRepository;
import com.chatbot.ai.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeCatalogRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsKnowledgeBasesAndFilesAcrossRepositoryInstances() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        KnowledgeCatalogRepository repository = new KnowledgeCatalogRepository(objectMapper, temporaryDirectory.toString());
        repository.initialize();
        repository.saveKnowledgeBase(KnowledgeBase.builder()
                .id("kb-1")
                .name("产品文档")
                .description("测试知识库")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build());
        Path storedFile = repository.storeFile("kb-1", "doc-1",
                new MockMultipartFile("file", "guide.txt", "text/plain", "hello".getBytes()));
        repository.saveDocument(KnowledgeDocument.builder()
                .id("doc-1")
                .knowledgeBaseId("kb-1")
                .fileName("guide.txt")
                .storageKey(repository.toStorageKey(storedFile))
                .createdAt(Instant.now())
                .build());

        KnowledgeCatalogRepository reloaded = new KnowledgeCatalogRepository(objectMapper, temporaryDirectory.toString());
        reloaded.initialize();

        assertThat(reloaded.findKnowledgeBase("kb-1")).isPresent();
        assertThat(reloaded.findDocuments("kb-1")).extracting(KnowledgeDocument::getFileName)
                .containsExactly("guide.txt");
        assertThat(reloaded.resolveStorageKey(reloaded.findDocument("doc-1").orElseThrow().getStorageKey()))
                .exists();
    }

    @Test
    void mapsProjectSeedFileWithoutCopyingOrDeletingItsSource() throws Exception {
        Path seedRoot = temporaryDirectory.resolve("knowledge-seed");
        Path storageRoot = temporaryDirectory.resolve("runtime-data");
        Files.createDirectories(seedRoot);
        Path source = seedRoot.resolve("01-售后手册.md");
        Files.writeString(source, "# 售后规则\n七日内按政策处理。", StandardCharsets.UTF_8);
        Files.writeString(seedRoot.resolve("README.md"), "目录说明", StandardCharsets.UTF_8);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        KnowledgeCatalogRepository repository = new KnowledgeCatalogRepository(
                objectMapper, storageRoot.toString(), seedRoot.toString());
        repository.initialize();

        assertThat(repository.findAllKnowledgeBases()).hasSize(1);
        KnowledgeBase base = repository.findAllKnowledgeBases().get(0);
        KnowledgeDocument document = repository.findDocuments(base.getId()).get(0);
        assertThat(base.getName()).isEqualTo("售后手册");
        assertThat(repository.resolveStorageKey(document.getStorageKey())).isEqualTo(source);
        assertThat(Files.readString(source)).contains("七日内按政策处理");

        new KnowledgeBaseService(repository, 1024).deleteKnowledgeBase(base.getId());
        assertThat(source).exists();
        KnowledgeCatalogRepository reloaded = new KnowledgeCatalogRepository(
                objectMapper, storageRoot.toString(), seedRoot.toString());
        reloaded.initialize();
        assertThat(reloaded.findAllKnowledgeBases()).isEmpty();
    }
}
