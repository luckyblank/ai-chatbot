package com.chatbot.ai.controller;

import com.chatbot.ai.domain.knowledge.KnowledgeBase;
import com.chatbot.ai.domain.knowledge.KnowledgeDocument;
import com.chatbot.ai.domain.knowledge.KnowledgeChunk;
import com.chatbot.ai.repository.KnowledgeCatalogRepository;
import com.chatbot.ai.repository.KnowledgeChunkRepository;
import com.chatbot.ai.domain.vo.CreateKnowledgeBaseRequest;
import com.chatbot.ai.domain.vo.UpdateKnowledgeBaseRequest;
import com.chatbot.ai.service.KnowledgeBaseService;
import com.chatbot.ai.service.KnowledgeIngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.core.io.FileSystemResource;
import java.nio.charset.StandardCharsets;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeCatalogRepository catalogRepository;
    private final KnowledgeChunkRepository chunkRepository;

    @GetMapping
    public List<KnowledgeBase> list() {
        return knowledgeBaseService.listKnowledgeBases();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBase create(@Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return knowledgeBaseService.createKnowledgeBase(request.name(), request.description());
    }

    @GetMapping("/{knowledgeBaseId}")
    public KnowledgeBase get(@PathVariable String knowledgeBaseId) {
        return knowledgeBaseService.getKnowledgeBase(knowledgeBaseId);
    }

    @PatchMapping("/{knowledgeBaseId}")
    public KnowledgeBase update(@PathVariable String knowledgeBaseId,
                                @Valid @RequestBody UpdateKnowledgeBaseRequest request) {
        return knowledgeBaseService.updateKnowledgeBase(
                knowledgeBaseId, request.name(), request.description());
    }

    @DeleteMapping("/{knowledgeBaseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String knowledgeBaseId) {
        for (KnowledgeDocument document : knowledgeBaseService.listDocuments(knowledgeBaseId)) {
            ingestionService.deleteVectors(document);
        }
        knowledgeBaseService.deleteKnowledgeBase(knowledgeBaseId);
    }

    @GetMapping("/{knowledgeBaseId}/documents")
    public List<KnowledgeDocument> listDocuments(@PathVariable String knowledgeBaseId) {
        return knowledgeBaseService.listDocuments(knowledgeBaseId);
    }

    @PostMapping("/{knowledgeBaseId}/documents")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeDocument upload(@PathVariable String knowledgeBaseId,
                                    @RequestParam("file") MultipartFile file) {
        KnowledgeDocument document = knowledgeBaseService.storeDocument(knowledgeBaseId, file);
        ingestionService.indexAsync(document.getId());
        return document;
    }

    @PostMapping("/{knowledgeBaseId}/documents/{documentId}/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeDocument reindex(@PathVariable String knowledgeBaseId,
                                     @PathVariable String documentId) {
        KnowledgeDocument document = requireOwnedDocument(knowledgeBaseId, documentId);
        ingestionService.indexAsync(document.getId());
        return document;
    }

    @DeleteMapping("/{knowledgeBaseId}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable String knowledgeBaseId,
                               @PathVariable String documentId) {
        KnowledgeDocument document = requireOwnedDocument(knowledgeBaseId, documentId);
        ingestionService.deleteVectors(document);
        knowledgeBaseService.deleteDocumentFile(document);
    }

    @GetMapping("/{knowledgeBaseId}/documents/{documentId}/chunks")
    public List<KnowledgeChunk> chunks(@PathVariable String knowledgeBaseId,
                                      @PathVariable String documentId) {
        requireOwnedDocument(knowledgeBaseId, documentId);
        return chunkRepository.findByDocument(documentId);
    }

    @GetMapping("/{knowledgeBaseId}/documents/{documentId}/preview")
    public ResponseEntity<FileSystemResource> preview(@PathVariable String knowledgeBaseId,
                                                       @PathVariable String documentId) {
        KnowledgeDocument document = requireOwnedDocument(knowledgeBaseId, documentId);
        FileSystemResource resource = new FileSystemResource(catalogRepository.resolveStorageKey(document.getStorageKey()));
        String contentType = document.getContentType();
        if (contentType == null || contentType.isBlank()) contentType = "application/octet-stream";
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.inline()
                        .filename(document.getFileName(), StandardCharsets.UTF_8).build().toString())
                .body(resource);
    }

    private KnowledgeDocument requireOwnedDocument(String knowledgeBaseId, String documentId) {
        KnowledgeDocument document = knowledgeBaseService.getDocument(documentId);
        if (!knowledgeBaseId.equals(document.getKnowledgeBaseId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.NOT_FOUND, "文档不存在");
        }
        return document;
    }
}
