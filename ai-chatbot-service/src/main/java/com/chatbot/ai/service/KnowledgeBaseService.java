package com.chatbot.ai.service;

import com.chatbot.ai.domain.knowledge.DocumentStatus;
import com.chatbot.ai.domain.knowledge.KnowledgeBase;
import com.chatbot.ai.domain.knowledge.KnowledgeDocument;
import com.chatbot.ai.repository.KnowledgeCatalogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeBaseService {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "txt", "md", "markdown");

    private final KnowledgeCatalogRepository repository;
    private final long maxUploadBytes;

    public KnowledgeBaseService(KnowledgeCatalogRepository repository,
                                @Value("${app.upload.max-bytes:20971520}") long maxUploadBytes) {
        this.repository = repository;
        this.maxUploadBytes = maxUploadBytes;
    }

    public List<KnowledgeBase> listKnowledgeBases() {
        return repository.findAllKnowledgeBases();
    }

    public KnowledgeBase getKnowledgeBase(String id) {
        return repository.findKnowledgeBase(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在"));
    }

    public KnowledgeBase createKnowledgeBase(String name, String description) {
        Instant now = Instant.now();
        KnowledgeBase knowledgeBase = KnowledgeBase.builder()
                .id(UUID.randomUUID().toString())
                .name(name.trim())
                .description(description == null ? "" : description.trim())
                .createdAt(now)
                .updatedAt(now)
                .build();
        return repository.saveKnowledgeBase(knowledgeBase);
    }

    public KnowledgeBase updateKnowledgeBase(String id, String name, String description) {
        getKnowledgeBase(id);
        return repository.updateKnowledgeBase(
                id,
                name.trim(),
                description == null ? "" : description.trim(),
                Instant.now()
        ).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在"));
    }

    public void deleteKnowledgeBase(String id) {
        getKnowledgeBase(id);
        for (KnowledgeDocument document : repository.findDocuments(id)) {
            deleteStoredFile(document);
        }
        repository.deleteKnowledgeBase(id);
    }

    public List<KnowledgeDocument> listDocuments(String knowledgeBaseId) {
        getKnowledgeBase(knowledgeBaseId);
        return repository.findDocuments(knowledgeBaseId);
    }

    public KnowledgeDocument getDocument(String id) {
        return repository.findDocument(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
    }

    public KnowledgeDocument storeDocument(String knowledgeBaseId, MultipartFile file) {
        KnowledgeBase knowledgeBase = getKnowledgeBase(knowledgeBaseId);
        validateFile(file);
        String documentId = UUID.randomUUID().toString();
        try {
            var storedPath = repository.storeFile(knowledgeBaseId, documentId, file);
            KnowledgeDocument document = KnowledgeDocument.builder()
                    .id(documentId)
                    .knowledgeBaseId(knowledgeBaseId)
                    .fileName(safeDisplayName(file.getOriginalFilename()))
                    .contentType(file.getContentType())
                    .size(file.getSize())
                    .storageKey(repository.toStorageKey(storedPath))
                    .status(DocumentStatus.UPLOADED)
                    .createdAt(Instant.now())
                    .build();
            knowledgeBase.setUpdatedAt(Instant.now());
            repository.saveKnowledgeBase(knowledgeBase);
            return repository.saveDocument(document);
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文件保存失败", exception);
        }
    }

    public void deleteDocumentFile(KnowledgeDocument document) {
        deleteStoredFile(document);
        repository.deleteDocument(document.getId());
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要上传的文件");
        }
        if (file.getSize() > maxUploadBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件大小超过限制");
        }
        String name = safeDisplayName(file.getOriginalFilename());
        int extensionIndex = name.lastIndexOf('.');
        String extension = extensionIndex >= 0
                ? name.substring(extensionIndex + 1).toLowerCase(Locale.ROOT)
                : "";
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅支持 PDF、TXT 和 Markdown 文件");
        }
        validateFileSignature(file, extension);
    }

    private void validateFileSignature(MultipartFile file, String extension) {
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(8);
            if ("pdf".equals(extension)) {
                String signature = new String(header, java.nio.charset.StandardCharsets.US_ASCII);
                if (!signature.startsWith("%PDF-")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF 文件内容无效");
                }
            } else {
                for (byte value : header) {
                    if (value == 0) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文本文件内容无效");
                    }
                }
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法读取上传文件", exception);
        }
    }

    private String safeDisplayName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "document";
        }
        String name = originalName.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        return (lastSlash >= 0 ? name.substring(lastSlash + 1) : name).replaceAll("[\\r\\n]", "");
    }

    private void deleteStoredFile(KnowledgeDocument document) {
        if (repository.isSeedDocument(document)) {
            return;
        }
        try {
            Files.deleteIfExists(repository.resolveStorageKey(document.getStorageKey()));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文件删除失败", exception);
        }
    }
}
