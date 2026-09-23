package com.chatbot.ai.repository;

import com.chatbot.ai.domain.knowledge.KnowledgeChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class KnowledgeChunkRepository {
    private final ObjectMapper objectMapper;
    private final Path dataFile;
    private final Map<String, KnowledgeChunk> chunks = new LinkedHashMap<>();

    public KnowledgeChunkRepository(ObjectMapper objectMapper,
                                    @Value("${app.storage.root:./runtime-data}") String storageRoot) {
        this.objectMapper = objectMapper;
        this.dataFile = Path.of(storageRoot).toAbsolutePath().normalize().resolve("knowledge-chunks.json");
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        Files.createDirectories(dataFile.getParent());
        if (!Files.exists(dataFile) || Files.size(dataFile) == 0) return;
        List<KnowledgeChunk> stored = objectMapper.readValue(dataFile.toFile(), new TypeReference<>() { });
        stored.forEach(item -> chunks.put(item.getId(), item));
    }

    public synchronized void replaceDocument(String documentId, List<KnowledgeChunk> replacements) {
        chunks.values().removeIf(chunk -> documentId.equals(chunk.getDocumentId()));
        replacements.forEach(chunk -> chunks.put(chunk.getId(), chunk));
        persist();
    }
    public synchronized List<KnowledgeChunk> findByDocument(String documentId) {
        return chunks.values().stream().filter(chunk -> documentId.equals(chunk.getDocumentId()))
                .sorted(Comparator.comparingInt(KnowledgeChunk::getSequence)).toList();
    }
    public synchronized void deleteDocument(String documentId) {
        chunks.values().removeIf(chunk -> documentId.equals(chunk.getDocumentId())); persist();
    }

    private void persist() {
        try {
            Path temp = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), new ArrayList<>(chunks.values()));
            try { Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException ignored) { Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException exception) { throw new IllegalStateException("无法保存知识分片", exception); }
    }
}
