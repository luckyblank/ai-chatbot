package com.chatbot.ai.service;

import com.chatbot.ai.domain.chat.ChatAttachment;
import com.chatbot.ai.repository.AttachmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AttachmentService {
    private static final long MAX_SIZE = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED = Set.of("png", "jpg", "jpeg", "webp", "gif", "pdf", "txt", "md", "markdown");
    private final AttachmentRepository repository;

    public ChatAttachment store(String conversationId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择附件");
        if (file.getSize() > MAX_SIZE) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "单个附件不能超过 20 MB");
        String name = safeName(file.getOriginalFilename());
        String extension = extension(name);
        if (!ALLOWED.contains(extension)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持该附件类型");
        String id = UUID.randomUUID().toString();
        try {
            Path directory = repository.conversationDirectory(conversationId); Files.createDirectories(directory);
            Path target = directory.resolve(id + "." + extension).normalize();
            try (var input = file.getInputStream()) { Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING); }
            String contentType = file.getContentType();
            if (contentType == null || contentType.isBlank() || "application/octet-stream".equals(contentType)) {
                String detected = Files.probeContentType(target);
                if (detected != null && !detected.isBlank()) contentType = detected;
            }
            boolean image = Set.of("png", "jpg", "jpeg", "webp", "gif").contains(extension)
                    || (contentType != null && contentType.startsWith("image/"));
            return repository.save(ChatAttachment.builder().id(id).conversationId(conversationId).name(name)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .size(file.getSize()).image(image).storageKey(repository.storageKey(target))
                    .contentUrl("/api/v1/conversations/" + conversationId + "/attachments/" + id + "/content")
                    .createdAt(Instant.now()).build());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "附件保存失败", exception);
        }
    }

    public ChatAttachment getOwned(String conversationId, String attachmentId) {
        ChatAttachment item = repository.find(attachmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在"));
        if (!conversationId.equals(item.getConversationId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        return item;
    }
    public List<ChatAttachment> findOwned(String conversationId, List<String> ids) { return repository.findAll(ids, conversationId); }
    public Path resolve(ChatAttachment item) { return repository.resolve(item.getStorageKey()); }
    public void deleteConversation(String conversationId) {
        Path directory = repository.conversationDirectory(conversationId);
        if (Files.exists(directory)) {
            try (var files = Files.walk(directory)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
            } catch (IOException ignored) { }
        }
        repository.deleteConversation(conversationId);
    }
    private String safeName(String original) {
        if (original == null || original.isBlank()) return "attachment";
        String normalized = original.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\r\\n]", "");
    }
    private String extension(String name) {
        int index = name.lastIndexOf('.'); return index < 0 ? "" : name.substring(index + 1).toLowerCase(Locale.ROOT);
    }
}
