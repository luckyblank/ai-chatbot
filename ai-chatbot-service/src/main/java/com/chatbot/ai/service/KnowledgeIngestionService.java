package com.chatbot.ai.service;

import com.chatbot.ai.domain.knowledge.DocumentStatus;
import com.chatbot.ai.domain.knowledge.KnowledgeDocument;
import com.chatbot.ai.domain.knowledge.KnowledgeChunk;
import com.chatbot.ai.config.PersistentVectorStoreLifecycle;
import com.chatbot.ai.repository.KnowledgeCatalogRepository;
import com.chatbot.ai.repository.KnowledgeChunkRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
public class KnowledgeIngestionService {
    private final KnowledgeCatalogRepository repository;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final KnowledgeChunkRepository chunkRepository;
    private final ObjectProvider<PersistentVectorStoreLifecycle> vectorStoreLifecycleProvider;
    private final boolean aiEnabled;

    public KnowledgeIngestionService(KnowledgeCatalogRepository repository,
                                     ObjectProvider<VectorStore> vectorStoreProvider,
                                     KnowledgeChunkRepository chunkRepository,
                                     ObjectProvider<PersistentVectorStoreLifecycle> vectorStoreLifecycleProvider,
                                     @Value("${app.ai.enabled:false}") boolean aiEnabled) {
        this.repository = repository;
        this.vectorStoreProvider = vectorStoreProvider;
        this.chunkRepository = chunkRepository;
        this.vectorStoreLifecycleProvider = vectorStoreLifecycleProvider;
        this.aiEnabled = aiEnabled;
    }

    @Async
    public void indexAsync(String documentId) {
        index(documentId);
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void indexSeedDocumentsOnStartup() {
        repository.findAllKnowledgeBases().forEach(base ->
                repository.findDocuments(base.getId()).stream()
                        .filter(repository::isSeedDocument)
                        .filter(document -> document.getStatus() != DocumentStatus.READY)
                        .forEach(document -> index(document.getId())));
    }

    public KnowledgeDocument index(String documentId) {
        KnowledgeDocument document = repository.findDocument(documentId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (!aiEnabled || vectorStore == null) {
            document.setStatus(DocumentStatus.PENDING_AI);
            document.setErrorMessage("AI 服务尚未启用；请在当前运行环境启用 AI 并配置模型密钥后重新索引");
            return repository.saveDocument(document);
        }

        document.setStatus(DocumentStatus.INDEXING);
        document.setErrorMessage(null);
        repository.saveDocument(document);
        try {
            if (document.getChunkIds() != null && !document.getChunkIds().isEmpty()) {
                vectorStore.delete(document.getChunkIds());
            }
            List<Document> sourceDocuments = readDocument(document);
            List<Document> enriched = sourceDocuments.stream().map(source -> {
                var metadata = new HashMap<String, Object>(source.getMetadata());
                metadata.put("knowledge_base_id", document.getKnowledgeBaseId());
                metadata.put("document_id", document.getId());
                metadata.put("file_name", document.getFileName());
                return Document.builder().text(source.getText()).metadata(metadata).build();
            }).toList();

            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(800)
                    .withMinChunkSizeChars(250)
                    .withMinChunkLengthToEmbed(20)
                    .withMaxNumChunks(5000)
                    .withKeepSeparator(true)
                    .build();
            List<Document> splitDocuments = splitter.apply(enriched);
            List<String> chunkIds = new ArrayList<>();
            List<Document> chunks = new ArrayList<>();
            List<KnowledgeChunk> previewChunks = new ArrayList<>();
            for (int index = 0; index < splitDocuments.size(); index++) {
                Document split = splitDocuments.get(index);
                String chunkId = UUID.randomUUID().toString();
                var metadata = new HashMap<String, Object>(split.getMetadata());
                metadata.put("chunk_id", chunkId);
                chunks.add(Document.builder()
                        .id(chunkId)
                        .text(split.getText())
                        .metadata(metadata)
                        .build());
                chunkIds.add(chunkId);
                previewChunks.add(KnowledgeChunk.builder()
                        .id(chunkId).knowledgeBaseId(document.getKnowledgeBaseId()).documentId(document.getId())
                        .sequence(index + 1).pageNumber(pageNumber(split.getMetadata()))
                        .characterCount(split.getText() == null ? 0 : split.getText().length())
                        .content(split.getText()).build());
            }
            if (chunks.isEmpty()) {
                throw new IllegalStateException("文档中没有可索引的文本");
            }
            vectorStore.add(chunks);
            persistVectors();
            chunkRepository.replaceDocument(document.getId(), previewChunks);
            document.setChunkIds(chunkIds);
            document.setStatus(DocumentStatus.READY);
            document.setIndexedAt(Instant.now());
            document.setErrorMessage(null);
        } catch (Exception exception) {
            log.error("Failed to index document {}", documentId, exception);
            document.setStatus(DocumentStatus.FAILED);
            document.setErrorMessage(safeError(exception));
        }
        return repository.saveDocument(document);
    }

    public void deleteVectors(KnowledgeDocument document) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore != null && document.getChunkIds() != null && !document.getChunkIds().isEmpty()) {
            vectorStore.delete(document.getChunkIds());
            persistVectors();
        }
        chunkRepository.deleteDocument(document.getId());
    }

    private void persistVectors() {
        PersistentVectorStoreLifecycle lifecycle = vectorStoreLifecycleProvider.getIfAvailable();
        if (lifecycle != null) {
            lifecycle.persist();
        }
    }

    private Integer pageNumber(java.util.Map<String, Object> metadata) {
        Object value = metadata.get("page_number");
        if (value == null) value = metadata.get("page");
        if (value instanceof Number number) return number.intValue();
        if (value != null) try { return Integer.parseInt(value.toString()); } catch (NumberFormatException ignored) { }
        return null;
    }

    private List<Document> readDocument(KnowledgeDocument document) throws Exception {
        Path path = repository.resolveStorageKey(document.getStorageKey());
        String fileName = document.getFileName().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".pdf")) {
            PagePdfDocumentReader reader = new PagePdfDocumentReader(
                    new FileSystemResource(path),
                    PdfDocumentReaderConfig.builder()
                            .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
                            .withPagesPerDocument(1)
                            .build()
            );
            return reader.read();
        }
        String content = Files.readString(path, StandardCharsets.UTF_8);
        return List.of(new Document(content));
    }

    private String safeError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "文档索引失败";
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }
}
