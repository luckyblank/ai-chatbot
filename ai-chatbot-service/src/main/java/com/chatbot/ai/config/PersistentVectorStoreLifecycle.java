package com.chatbot.ai.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
public class PersistentVectorStoreLifecycle {
    private final SimpleVectorStore vectorStore;
    private final Path storageFile;

    public PersistentVectorStoreLifecycle(VectorStore vectorStore,
                                          @Value("${app.storage.root:./runtime-data}") String storageRoot) {
        if (!(vectorStore instanceof SimpleVectorStore simpleVectorStore)) {
            throw new IllegalStateException("Local vector persistence requires SimpleVectorStore");
        }
        this.vectorStore = simpleVectorStore;
        this.storageFile = Path.of(storageRoot).toAbsolutePath().normalize().resolve("vector-store.json");
    }

    @PostConstruct
    public void load() {
        if (!Files.exists(storageFile)) {
            return;
        }
        try {
            vectorStore.load(storageFile.toFile());
        } catch (Exception exception) {
            log.error("Failed to load the local vector store", exception);
            throw new IllegalStateException("无法加载本地向量数据", exception);
        }
    }

    @PreDestroy
    public void save() {
        try {
            persist();
        } catch (Exception exception) {
            log.error("Failed to save the local vector store", exception);
        }
    }

    public synchronized void persist() {
        try {
            Files.createDirectories(storageFile.getParent());
            vectorStore.save(storageFile.toFile());
        } catch (Exception exception) {
            throw new IllegalStateException("无法保存本地向量数据", exception);
        }
    }
}
