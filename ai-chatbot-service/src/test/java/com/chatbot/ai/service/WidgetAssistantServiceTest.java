package com.chatbot.ai.service;

import com.chatbot.ai.domain.knowledge.DocumentStatus;
import com.chatbot.ai.domain.knowledge.KnowledgeBase;
import com.chatbot.ai.domain.knowledge.KnowledgeDocument;
import com.chatbot.ai.repository.KnowledgeCatalogRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WidgetAssistantServiceTest {
    private static final String SEED = "seed/05-制度与产品知识检索手册.md";

    @Test
    void builtInProductManualMustBeIndexedBeforeWidgetIsAvailable() {
        KnowledgeCatalogRepository catalog = mock(KnowledgeCatalogRepository.class);
        KnowledgeBase base = KnowledgeBase.builder().id("product-base").name("产品使用手册").build();
        when(catalog.findAllKnowledgeBases()).thenReturn(List.of(base));
        when(catalog.findDocuments("product-base")).thenReturn(List.of(document(SEED, DocumentStatus.UPLOADED)));
        WidgetAssistantService service = new WidgetAssistantService(catalog, "", true);

        assertThat(service.context().available()).isFalse();
        assertThat(service.context().knowledgeBaseId()).isEqualTo("product-base");

        when(catalog.findDocuments("product-base")).thenReturn(List.of(document(SEED, DocumentStatus.READY)));
        assertThat(service.context().available()).isTrue();
        assertThat(service.context().scenarioCode()).isEqualTo("knowledge-research");
    }

    @Test
    void configuredBaseCanReplaceBuiltInManualButStillRequiresAi() {
        KnowledgeCatalogRepository catalog = mock(KnowledgeCatalogRepository.class);
        KnowledgeBase base = KnowledgeBase.builder().id("maintained-base").name("正式产品知识").build();
        when(catalog.findKnowledgeBase("maintained-base")).thenReturn(Optional.of(base));
        when(catalog.findDocuments("maintained-base")).thenReturn(List.of(document("files/manual.md", DocumentStatus.READY)));

        WidgetAssistantService service = new WidgetAssistantService(catalog, "maintained-base", true);
        assertThat(service.context().available()).isTrue();
        assertThat(service.context().knowledgeBaseName()).isEqualTo("正式产品知识");
        assertThat(new WidgetAssistantService(catalog, "maintained-base", false).context().available()).isFalse();
    }

    private KnowledgeDocument document(String storageKey, DocumentStatus status) {
        return KnowledgeDocument.builder().knowledgeBaseId("product-base")
                .storageKey(storageKey).status(status).build();
    }
}
