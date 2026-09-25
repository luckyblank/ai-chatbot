package com.chatbot.ai.repository;

import com.chatbot.ai.domain.knowledge.KnowledgeBase;
import com.chatbot.ai.domain.knowledge.KnowledgeDocument;
import com.chatbot.ai.domain.knowledge.DocumentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Repository
public class KnowledgeCatalogRepository {
    // Catalogs written before incremental seeding already offered these files.
    // Missing entries in those catalogs may represent intentional user deletions.
    private static final Set<String> LEGACY_SEED_FILES = Set.of(
            "01-电商售后与消费者权益处理手册.md",
            "02-SaaS故障响应与客户沟通手册.md",
            "03-企业IT服务台与账号安全处理手册.md",
            "04-平台商家治理与申诉处理手册.md");
    private final ObjectMapper objectMapper;
    private final Path storageRoot;
    private final Path seedRoot;
    private final Path catalogFile;
    private final Map<String, KnowledgeBase> knowledgeBases = new LinkedHashMap<>();
    private final Map<String, KnowledgeDocument> documents = new LinkedHashMap<>();
    private final Set<String> registeredSeedFiles = new LinkedHashSet<>();

    public KnowledgeCatalogRepository(ObjectMapper objectMapper, String storageRoot) {
        this(objectMapper, storageRoot, null);
    }

    @Autowired
    public KnowledgeCatalogRepository(ObjectMapper objectMapper,
                                      @Value("${app.storage.root:./runtime-data}") String storageRoot,
                                      @Value("${app.knowledge.seed-root:../knowledge-seed}") String seedRoot) {
        this.objectMapper = objectMapper;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.seedRoot = seedRoot == null ? null : Path.of(seedRoot).toAbsolutePath().normalize();
        this.catalogFile = this.storageRoot.resolve("knowledge-catalog.json");
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        Files.createDirectories(storageRoot.resolve("files"));
        boolean migrated = false;
        if (Files.exists(catalogFile) && Files.size(catalogFile) > 0) {
            CatalogSnapshot snapshot = objectMapper.readValue(catalogFile.toFile(), CatalogSnapshot.class);
            snapshot.knowledgeBases.forEach(item -> knowledgeBases.put(item.getId(), item));
            snapshot.documents.forEach(item -> documents.put(item.getId(), item));
            if (snapshot.registeredSeedFiles == null) {
                registeredSeedFiles.addAll(LEGACY_SEED_FILES.stream().sorted().toList());
                documents.values().stream().filter(this::isSeedDocument)
                        .map(document -> document.getStorageKey().substring("seed/".length()))
                        .forEach(registeredSeedFiles::add);
                migrated = true;
            } else {
                registeredSeedFiles.addAll(snapshot.registeredSeedFiles);
            }
        }
        boolean addedSeeds = registerSeedDocuments();
        if (migrated || addedSeeds) {
            persist();
        }
    }

    public synchronized List<KnowledgeBase> findAllKnowledgeBases() {
        return knowledgeBases.values().stream()
                .sorted(Comparator.comparing(KnowledgeBase::getUpdatedAt).reversed())
                .toList();
    }

    public synchronized Optional<KnowledgeBase> findKnowledgeBase(String id) {
        return Optional.ofNullable(knowledgeBases.get(id));
    }

    public synchronized KnowledgeBase saveKnowledgeBase(KnowledgeBase knowledgeBase) {
        knowledgeBases.put(knowledgeBase.getId(), knowledgeBase);
        persist();
        return knowledgeBase;
    }

    public synchronized Optional<KnowledgeBase> updateKnowledgeBase(String id,
                                                                     String name,
                                                                     String description,
                                                                     Instant updatedAt) {
        KnowledgeBase knowledgeBase = knowledgeBases.get(id);
        if (knowledgeBase == null) {
            return Optional.empty();
        }
        knowledgeBase.setName(name);
        knowledgeBase.setDescription(description);
        knowledgeBase.setUpdatedAt(updatedAt);
        persist();
        return Optional.of(knowledgeBase);
    }

    public synchronized void deleteKnowledgeBase(String id) {
        knowledgeBases.remove(id);
        documents.values().removeIf(document -> id.equals(document.getKnowledgeBaseId()));
        persist();
    }

    public synchronized List<KnowledgeDocument> findDocuments(String knowledgeBaseId) {
        return documents.values().stream()
                .filter(document -> knowledgeBaseId.equals(document.getKnowledgeBaseId()))
                .sorted(Comparator.comparing(KnowledgeDocument::getCreatedAt).reversed())
                .toList();
    }

    public synchronized Optional<KnowledgeDocument> findDocument(String id) {
        return Optional.ofNullable(documents.get(id));
    }

    public synchronized KnowledgeDocument saveDocument(KnowledgeDocument document) {
        documents.put(document.getId(), document);
        persist();
        return document;
    }

    public synchronized void deleteDocument(String id) {
        documents.remove(id);
        persist();
    }

    public Path storeFile(String knowledgeBaseId, String documentId, MultipartFile file) throws IOException {
        String extension = extensionOf(file.getOriginalFilename());
        Path directory = storageRoot.resolve("files").resolve(knowledgeBaseId).normalize();
        ensureWithinStorage(directory);
        Files.createDirectories(directory);
        Path target = directory.resolve(documentId + extension).normalize();
        ensureWithinStorage(target);
        try (var input = file.getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    public Path resolveStorageKey(String storageKey) {
        if (storageKey.startsWith("seed/")) {
            if (seedRoot == null) {
                throw new IllegalArgumentException("未配置内置知识库目录");
            }
            Path resolved = seedRoot.resolve(storageKey.substring("seed/".length())).normalize();
            if (!resolved.startsWith(seedRoot)) {
                throw new IllegalArgumentException("非法存储路径");
            }
            return resolved;
        }
        Path resolved = storageRoot.resolve(storageKey).normalize();
        ensureWithinStorage(resolved);
        return resolved;
    }

    public String toStorageKey(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        ensureWithinStorage(normalized);
        return storageRoot.relativize(normalized).toString().replace('\\', '/');
    }

    public boolean isSeedDocument(KnowledgeDocument document) {
        return document.getStorageKey() != null && document.getStorageKey().startsWith("seed/");
    }

    private boolean registerSeedDocuments() throws IOException {
        if (seedRoot == null || !Files.isDirectory(seedRoot)) {
            return false;
        }
        boolean changed = false;
        try (Stream<Path> files = Files.list(seedRoot)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("\\d{2}-.+\\.md"))
                    .sorted().toList()) {
                String fileName = file.getFileName().toString();
                if (registeredSeedFiles.contains(fileName)) {
                    continue;
                }
                String title = fileName.replaceFirst("^\\d+-", "").replaceFirst("\\.md$", "");
                Instant modifiedAt = Files.getLastModifiedTime(file).toInstant();
                String baseId = UUID.nameUUIDFromBytes(("seed-base:" + fileName)
                        .getBytes(StandardCharsets.UTF_8)).toString();
                String documentId = UUID.nameUUIDFromBytes(("seed-document:" + fileName)
                        .getBytes(StandardCharsets.UTF_8)).toString();
                knowledgeBases.putIfAbsent(baseId, KnowledgeBase.builder()
                        .id(baseId).name(title).description("项目内置知识手册")
                        .createdAt(modifiedAt).updatedAt(modifiedAt).build());
                documents.putIfAbsent(documentId, KnowledgeDocument.builder()
                        .id(documentId).knowledgeBaseId(baseId).fileName(fileName)
                        .contentType("text/markdown; charset=UTF-8").size(Files.size(file))
                        .storageKey("seed/" + fileName).status(DocumentStatus.UPLOADED)
                        .createdAt(modifiedAt).build());
                registeredSeedFiles.add(fileName);
                changed = true;
            }
        }
        return changed;
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int index = fileName.lastIndexOf('.');
        return index >= 0 ? fileName.substring(index).toLowerCase() : "";
    }

    private void ensureWithinStorage(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(storageRoot)) {
            throw new IllegalArgumentException("非法存储路径");
        }
    }

    private void persist() {
        try {
            Files.createDirectories(storageRoot);
            Path temporary = storageRoot.resolve("knowledge-catalog.json.tmp");
            CatalogSnapshot snapshot = new CatalogSnapshot(
                    new ArrayList<>(knowledgeBases.values()),
                    new ArrayList<>(documents.values()),
                    new ArrayList<>(registeredSeedFiles)
            );
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), snapshot);
            try {
                Files.move(temporary, catalogFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, catalogFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存知识库目录", exception);
        }
    }

    public static class CatalogSnapshot {
        public List<KnowledgeBase> knowledgeBases = new ArrayList<>();
        public List<KnowledgeDocument> documents = new ArrayList<>();
        // Null identifies catalogs written before import history was tracked.
        public List<String> registeredSeedFiles;

        public CatalogSnapshot() {
        }

        public CatalogSnapshot(List<KnowledgeBase> knowledgeBases, List<KnowledgeDocument> documents,
                               List<String> registeredSeedFiles) {
            this.knowledgeBases = knowledgeBases;
            this.documents = documents;
            this.registeredSeedFiles = registeredSeedFiles;
        }
    }
}
