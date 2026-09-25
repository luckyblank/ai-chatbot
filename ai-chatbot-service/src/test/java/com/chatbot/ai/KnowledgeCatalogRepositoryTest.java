package com.chatbot.ai;

import com.chatbot.ai.domain.knowledge.KnowledgeBase;
import com.chatbot.ai.domain.knowledge.KnowledgeDocument;
import com.chatbot.ai.domain.knowledge.DocumentStatus;
import com.chatbot.ai.repository.KnowledgeCatalogRepository;
import com.chatbot.ai.service.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

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

    @Test
    void upgradesLegacyCatalogWithNewSeedsWithoutResettingExistingDataOrRestoringDeletedSeeds() throws Exception {
        Path seedRoot = temporaryDirectory.resolve("seeds");
        Path storageRoot = temporaryDirectory.resolve("storage");
        Files.createDirectories(seedRoot);
        Files.writeString(seedRoot.resolve("01-电商售后与消费者权益处理手册.md"), "# 售后规则");
        Files.writeString(seedRoot.resolve("04-平台商家治理与申诉处理手册.md"), "# 商家规则");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        KnowledgeCatalogRepository original = new KnowledgeCatalogRepository(mapper, storageRoot.toString(), seedRoot.toString());
        original.initialize();
        KnowledgeBase base = original.findAllKnowledgeBases().stream()
                .filter(item -> item.getName().startsWith("电商")).findFirst().orElseThrow();
        original.updateKnowledgeBase(base.getId(), "用户自定义售后库", "保留此描述", Instant.now());
        KnowledgeDocument document = original.findDocuments(base.getId()).get(0);
        document.setStatus(DocumentStatus.READY);
        document.setChunkIds(List.of("existing-chunk"));
        document.setIndexedAt(Instant.parse("2026-09-22T08:00:00Z"));
        original.saveDocument(document);
        String deletedId = original.findAllKnowledgeBases().stream()
                .filter(item -> item.getName().startsWith("平台")).findFirst().orElseThrow().getId();
        original.deleteKnowledgeBase(deletedId);
        KnowledgeBase custom = new KnowledgeBaseService(original, 1024).createKnowledgeBase("企业自建库", "保留用户数据");

        // Reproduce the on-disk format from before incremental seed registration.
        Path catalogFile = storageRoot.resolve("knowledge-catalog.json");
        ObjectNode legacySnapshot = (ObjectNode) mapper.readTree(catalogFile.toFile());
        legacySnapshot.remove("registeredSeedFiles");
        mapper.writeValue(catalogFile.toFile(), legacySnapshot);
        Files.writeString(seedRoot.resolve("05-制度与产品知识检索手册.md"), "# 制度与产品\n知识检索说明");

        KnowledgeCatalogRepository upgraded = new KnowledgeCatalogRepository(mapper, storageRoot.toString(), seedRoot.toString());
        upgraded.initialize();
        assertThat(upgraded.findAllKnowledgeBases()).extracting(KnowledgeBase::getName)
                .containsExactlyInAnyOrder("用户自定义售后库", "企业自建库", "制度与产品知识检索手册");
        assertThat(upgraded.findKnowledgeBase(base.getId()).orElseThrow().getDescription()).isEqualTo("保留此描述");
        assertThat(upgraded.findKnowledgeBase(custom.getId())).isPresent();
        assertThat(upgraded.findKnowledgeBase(deletedId)).isEmpty();
        KnowledgeDocument preserved = upgraded.findDocument(document.getId()).orElseThrow();
        assertThat(preserved.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThat(preserved.getChunkIds()).containsExactly("existing-chunk");
        assertThat(preserved.getIndexedAt()).isEqualTo(document.getIndexedAt());
        KnowledgeBase added = upgraded.findAllKnowledgeBases().stream()
                .filter(item -> item.getName().equals("制度与产品知识检索手册")).findFirst().orElseThrow();
        assertThat(upgraded.findDocuments(added.getId())).singleElement()
                .satisfies(item -> assertThat(item.getStatus()).isEqualTo(DocumentStatus.UPLOADED));

        KnowledgeCatalogRepository restarted = new KnowledgeCatalogRepository(mapper, storageRoot.toString(), seedRoot.toString());
        restarted.initialize();
        assertThat(restarted.findAllKnowledgeBases()).hasSize(3);
        assertThat(restarted.findDocuments(added.getId())).hasSize(1);
        assertThat(restarted.findKnowledgeBase(deletedId)).isEmpty();
    }

    @Test
    void addsLaterSeedFilesButDoesNotRestoreIndividuallyDeletedDocuments() throws Exception {
        Path seedRoot = temporaryDirectory.resolve("seeds");
        Path storageRoot = temporaryDirectory.resolve("storage");
        Files.createDirectories(seedRoot);
        Files.writeString(seedRoot.resolve("05-制度与产品知识检索手册.md"), "# 知识检索");
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        KnowledgeCatalogRepository original = new KnowledgeCatalogRepository(mapper, storageRoot.toString(), seedRoot.toString());
        original.initialize();
        String baseId = original.findAllKnowledgeBases().get(0).getId();
        original.deleteDocument(original.findDocuments(baseId).get(0).getId());
        Files.writeString(seedRoot.resolve("06-新增产品手册.md"), "# 新产品");

        KnowledgeCatalogRepository restarted = new KnowledgeCatalogRepository(mapper, storageRoot.toString(), seedRoot.toString());
        restarted.initialize();
        assertThat(restarted.findDocuments(baseId)).isEmpty();
        assertThat(restarted.findAllKnowledgeBases()).extracting(KnowledgeBase::getName)
                .containsExactlyInAnyOrder("制度与产品知识检索手册", "新增产品手册");
    }

    @Test
    void registersTheKnowledgeResearchHandbookFromTheProjectSeedDirectory() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        KnowledgeCatalogRepository repository = new KnowledgeCatalogRepository(
                mapper, temporaryDirectory.toString(), "../knowledge-seed");
        repository.initialize();
        KnowledgeBase base = repository.findAllKnowledgeBases().stream()
                .filter(item -> item.getName().equals("制度与产品知识检索手册")).findFirst().orElseThrow();
        assertThat(repository.findAllKnowledgeBases()).hasSize(5);
        KnowledgeDocument document = repository.findDocuments(base.getId()).get(0);
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(Files.readString(repository.resolveStorageKey(document.getStorageKey())))
                .contains("knowledge-research", "PENDING_AI", "生效日期", "PDF、TXT、Markdown");
    }
}
