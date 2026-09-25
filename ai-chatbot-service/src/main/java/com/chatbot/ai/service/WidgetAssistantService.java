package com.chatbot.ai.service;

import com.chatbot.ai.domain.knowledge.DocumentStatus;
import com.chatbot.ai.domain.knowledge.KnowledgeBase;
import com.chatbot.ai.domain.knowledge.KnowledgeDocument;
import com.chatbot.ai.repository.KnowledgeCatalogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class WidgetAssistantService {
    private static final String SCENARIO_CODE = "knowledge-research";
    private static final String SEED_STORAGE_KEY = "seed/05-制度与产品知识检索手册.md";

    private final KnowledgeCatalogRepository catalog;
    private final String configuredKnowledgeBaseId;
    private final boolean aiEnabled;

    public WidgetAssistantService(KnowledgeCatalogRepository catalog,
                                  @Value("${app.widget.knowledge-base-id:}") String configuredKnowledgeBaseId,
                                  @Value("${app.ai.enabled:false}") boolean aiEnabled) {
        this.catalog = catalog;
        this.configuredKnowledgeBaseId = configuredKnowledgeBaseId == null ? "" : configuredKnowledgeBaseId.trim();
        this.aiEnabled = aiEnabled;
    }

    public Context context() {
        Optional<KnowledgeBase> knowledgeBase = resolveKnowledgeBase();
        if (knowledgeBase.isEmpty()) {
            return new Context(SCENARIO_CODE, null, null, false,
                    "尚未找到产品使用知识库，请在知识中心导入并索引产品手册。");
        }
        KnowledgeBase base = knowledgeBase.get();
        List<KnowledgeDocument> documents = catalog.findDocuments(base.getId());
        boolean indexed = documents.stream().anyMatch(document -> document.getStatus() == DocumentStatus.READY
                && (!configuredKnowledgeBaseId.isBlank() || SEED_STORAGE_KEY.equals(document.getStorageKey())));
        if (!indexed) {
            return new Context(SCENARIO_CODE, base.getId(), base.getName(), false,
                    "产品知识库尚无已完成索引的文档，请在知识中心检查索引状态。");
        }
        if (!aiEnabled) {
            return new Context(SCENARIO_CODE, base.getId(), base.getName(), false,
                    "AI 服务尚未启用，请先配置模型服务。");
        }
        return new Context(SCENARIO_CODE, base.getId(), base.getName(), true, "产品文档已索引");
    }

    private Optional<KnowledgeBase> resolveKnowledgeBase() {
        if (!configuredKnowledgeBaseId.isBlank()) {
            return catalog.findKnowledgeBase(configuredKnowledgeBaseId);
        }
        return catalog.findAllKnowledgeBases().stream()
                .filter(base -> catalog.findDocuments(base.getId()).stream()
                        .anyMatch(document -> SEED_STORAGE_KEY.equals(document.getStorageKey())))
                .findFirst();
    }

    public record Context(String scenarioCode, String knowledgeBaseId, String knowledgeBaseName,
                          boolean available, String message) { }
}
