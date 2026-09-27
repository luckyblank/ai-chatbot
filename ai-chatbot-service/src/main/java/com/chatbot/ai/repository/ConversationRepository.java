package com.chatbot.ai.repository;

import com.chatbot.ai.domain.chat.ChatMessageEntry;
import com.chatbot.ai.domain.chat.ConversationSession;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ConversationRepository {
    private final ObjectMapper objectMapper;
    private final Path dataFile;
    private final Map<String, ConversationSession> sessions = new LinkedHashMap<>();

    public ConversationRepository(ObjectMapper objectMapper,
                                  @Value("${app.storage.root:./runtime-data}") String storageRoot) {
        this.objectMapper = objectMapper;
        this.dataFile = Path.of(storageRoot).toAbsolutePath().normalize().resolve("conversations.json");
    }

    @PostConstruct
    public synchronized void initialize() throws IOException {
        Files.createDirectories(dataFile.getParent());
        if (!Files.exists(dataFile) || Files.size(dataFile) == 0) {
            return;
        }
        List<ConversationSession> stored = objectMapper.readValue(
                dataFile.toFile(), new TypeReference<List<ConversationSession>>() { });
        stored.forEach(session -> sessions.put(session.getId(), session));
    }

    public synchronized ConversationSession save(ConversationSession session) {
        sessions.put(session.getId(), session);
        persist();
        return session;
    }

    public synchronized Optional<ConversationSession> findById(String id) {
        return Optional.ofNullable(sessions.get(id));
    }

    public synchronized List<ConversationSession> findByKnowledgeBaseId(String knowledgeBaseId) {
        return sessions.values().stream()
                .filter(session -> knowledgeBaseId == null
                        || session.selectedKnowledgeBaseIds().contains(knowledgeBaseId))
                .sorted(Comparator.comparing(ConversationSession::getUpdatedAt).reversed())
                .toList();
    }

    public synchronized ConversationSession append(String id, ChatMessageEntry message) {
        ConversationSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        session.getMessages().add(message);
        session.setUpdatedAt(Instant.now());
        persist();
        return session;
    }

    /**
     * Atomically stores a complete successful turn and claims first-question title generation.
     * Only the request that appends the first user message receives {@code true}.
     */
    public synchronized AppendExchangeResult appendExchange(String id,
                                                             ChatMessageEntry userMessage,
                                                             ChatMessageEntry assistantMessage) {
        ConversationSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (session.getMessages() == null) {
            session.setMessages(new ArrayList<>());
        }
        boolean firstUserMessage = session.getMessages().stream()
                .noneMatch(message -> "user".equalsIgnoreCase(message.getRole()));
        boolean shouldGenerateTitle = firstUserMessage
                && !session.isTitleCustomized()
                && isDefaultTitle(session.getTitle());
        session.getMessages().add(userMessage);
        session.getMessages().add(assistantMessage);
        session.setUpdatedAt(Instant.now());
        persist();
        return new AppendExchangeResult(session, shouldGenerateTitle);
    }

    public synchronized ConversationSession renameDefaultTitle(String id, String title) {
        ConversationSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        // A rename performed by the user while the title model was running always wins.
        if (session.isTitleCustomized() || !isDefaultTitle(session.getTitle())) {
            return session;
        }
        session.setTitle(title);
        session.setUpdatedAt(Instant.now());
        persist();
        return session;
    }

    public synchronized ConversationSession rename(String id, String title) {
        ConversationSession session = sessions.get(id);
        if (session == null) throw new IllegalArgumentException("会话不存在");
        session.setTitle(title);
        session.setTitleCustomized(true);
        session.setUpdatedAt(Instant.now());
        persist();
        return session;
    }

    public synchronized ConversationSession replaceExchange(String id,
                                                            int messageIndex,
                                                            ChatMessageEntry userMessage,
                                                            ChatMessageEntry assistantMessage) {
        ConversationSession session = sessions.get(id);
        if (session == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        if (session.getMessages() == null || messageIndex < 0 || messageIndex >= session.getMessages().size()) {
            throw new IllegalArgumentException("消息不存在");
        }
        ChatMessageEntry original = session.getMessages().get(messageIndex);
        if (!"user".equalsIgnoreCase(original.getRole())) {
            throw new IllegalArgumentException("仅支持编辑用户发送的消息");
        }
        session.getMessages().subList(messageIndex, session.getMessages().size()).clear();
        session.getMessages().add(userMessage);
        session.getMessages().add(assistantMessage);
        session.setUpdatedAt(Instant.now());
        persist();
        return session;
    }

    public synchronized boolean delete(String id) {
        ConversationSession removed = sessions.remove(id);
        if (removed != null) persist();
        return removed != null;
    }

    public static boolean isDefaultTitle(String title) {
        if (title == null || title.isBlank()) {
            return true;
        }
        String normalized = title.trim();
        return "新对话".equals(normalized)
                || normalized.matches(".+\\s*[·・|｜/\\-]\\s*新对话");
    }

    public record AppendExchangeResult(ConversationSession session, boolean shouldGenerateTitle) {
    }

    private void persist() {
        try {
            Path temporary = dataFile.resolveSibling(dataFile.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    temporary.toFile(), new ArrayList<>(sessions.values()));
            try {
                Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存会话记录", exception);
        }
    }
}
