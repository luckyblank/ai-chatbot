package com.chatbot.ai.repository;

import com.chatbot.ai.domain.chat.ChatAttachment;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AttachmentRepository {
    private final ObjectMapper objectMapper;
    private final Path storageRoot;
    private final Path dataFile;
    private final Map<String, ChatAttachment> attachments = new LinkedHashMap<>();

    public AttachmentRepository(ObjectMapper objectMapper,
                                @Value("${app.storage.root:./runtime-data}") String storageRoot) {
        this.objectMapper = objectMapper;
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.dataFile = this.storageRoot.resolve("conversation-attachments.json");
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        Files.createDirectories(storageRoot.resolve("conversation-files"));
        if (!Files.exists(dataFile) || Files.size(dataFile) == 0) return;
        List<ChatAttachment> stored = objectMapper.readValue(dataFile.toFile(), new TypeReference<>() { });
        stored.forEach(item -> attachments.put(item.getId(), item));
    }

    public synchronized ChatAttachment save(ChatAttachment attachment) {
        attachments.put(attachment.getId(), attachment); persist(); return attachment;
    }

    public synchronized Optional<ChatAttachment> find(String id) { return Optional.ofNullable(attachments.get(id)); }

    public synchronized List<ChatAttachment> findAll(List<String> ids, String conversationId) {
        if (ids == null) return List.of();
        return ids.stream().map(attachments::get).filter(item -> item != null && conversationId.equals(item.getConversationId())).toList();
    }

    public synchronized void deleteConversation(String conversationId) {
        attachments.values().removeIf(item -> conversationId.equals(item.getConversationId())); persist();
    }

    public Path conversationDirectory(String conversationId) {
        Path path = storageRoot.resolve("conversation-files").resolve(conversationId).normalize(); ensureWithinStorage(path); return path;
    }
    public Path resolve(String storageKey) { Path path = storageRoot.resolve(storageKey).normalize(); ensureWithinStorage(path); return path; }
    public String storageKey(Path path) { Path normalized = path.toAbsolutePath().normalize(); ensureWithinStorage(normalized); return storageRoot.relativize(normalized).toString().replace('\\', '/'); }

    private void ensureWithinStorage(Path path) {
        if (!path.toAbsolutePath().normalize().startsWith(storageRoot)) throw new IllegalArgumentException("非法附件路径");
    }
    private void persist() {
        try {
            Files.createDirectories(storageRoot);
            Path temporary = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), new ArrayList<>(attachments.values()));
            try { Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (IOException ignored) { Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException exception) { throw new IllegalStateException("无法保存附件目录", exception); }
    }
}
