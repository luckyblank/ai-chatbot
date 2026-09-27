package com.chatbot.ai.service;

import com.chatbot.ai.domain.knowledge.DocumentStatus;
import com.chatbot.ai.domain.knowledge.KnowledgeBase;
import com.chatbot.ai.domain.knowledge.KnowledgeDocument;
import com.chatbot.ai.domain.scenario.ScenarioDefinition;
import com.chatbot.ai.repository.KnowledgeCatalogRepository;
import com.chatbot.ai.repository.ScenarioRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChatServiceMultiBaseTest {
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void searchesReadyBasesSeparatelyAndKeepsCitationOwnership() {
        KnowledgeCatalogRepository catalog = mock(KnowledgeCatalogRepository.class);
        ScenarioRepository scenarios = mock(ScenarioRepository.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(scenarios.findByCode("general")).thenReturn(Optional.of(scenario(List.of())));
        base(catalog, "kb-a", true);
        base(catalog, "kb-b", true);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(
                List.of(document("kb-a", "a-1", "A 库资料一"),
                        document("kb-a", "a-2", "A 库资料二"),
                        document("kb-a", "a-3", "A 库资料三"),
                        document("kb-a", "a-4", "A 库资料四"),
                        document("kb-a", "a-5", "A 库资料五"),
                        document("kb-a", "a-6", "A 库资料六")),
                List.of(document("kb-a", "leaked", "错误归属的资料"),
                        document("kb-b", "b-1", "B 库资料一"),
                        document("kb-b", "b-2", "B 库资料二"),
                        document("kb-b", "b-3", "B 库资料三"),
                        document("kb-b", "b-4", "B 库资料四"),
                        document("kb-b", "b-5", "B 库资料五")));
        when(client.prompt()).thenReturn(spec);
        when(spec.user(any(Consumer.class))).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.toolContext(anyMap())).thenReturn(spec);
        when(spec.call()).thenReturn(response);
        when(response.content()).thenReturn("综合资料。[资料 1][资料 2][资料 3][资料 4][资料 5][资料 6]");
        ScenarioToolPolicy policy = mock(ScenarioToolPolicy.class);
        when(policy.allowedCallbacks(any())).thenReturn(List.of());
        KnowledgeChatService service = service(catalog, scenarios, vectorStore, client, policy);

        var result = service.answerWithKnowledgeBases("conversation", List.of("kb-a", "kb-b"),
                "general", "请对比两份资料", List.of(), null, "request");

        ArgumentCaptor<SearchRequest> searches = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(2)).similaritySearch(searches.capture());
        assertThat(searches.getAllValues()).allSatisfy(search -> {
            assertThat(search.getTopK()).isEqualTo(6);
            assertThat(search.getFilterExpression().toString()).contains("knowledge_base_id");
        });
        assertThat(searches.getAllValues().get(0).getFilterExpression().toString()).contains("kb-a");
        assertThat(searches.getAllValues().get(1).getFilterExpression().toString()).contains("kb-b");
        assertThat(result.citations()).extracting(citation -> citation.getKnowledgeBaseId())
                .containsExactly("kb-a", "kb-b", "kb-a", "kb-b", "kb-a", "kb-b");
        assertThat(result.citations()).extracting(citation -> citation.getChunkId())
                .containsExactly("a-1", "b-1", "a-2", "b-2", "a-3", "b-3");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void stillSearchesReadyBaseWhenAnotherSelectedBaseHasNoIndex() {
        KnowledgeCatalogRepository catalog = mock(KnowledgeCatalogRepository.class);
        ScenarioRepository scenarios = mock(ScenarioRepository.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient client = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(scenarios.findByCode("general")).thenReturn(Optional.of(scenario(List.of())));
        base(catalog, "kb-a", true);
        base(catalog, "kb-b", false);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(document("kb-a", "a-1", "A 库资料")));
        when(client.prompt()).thenReturn(spec);
        when(spec.user(any(Consumer.class))).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.toolContext(anyMap())).thenReturn(spec);
        when(spec.call()).thenReturn(response);
        when(response.content()).thenReturn("回答 [资料 1]");
        ScenarioToolPolicy policy = mock(ScenarioToolPolicy.class);
        when(policy.allowedCallbacks(any())).thenReturn(List.of());

        var result = service(catalog, scenarios, vectorStore, client, policy)
                .answerWithKnowledgeBases("conversation", List.of("kb-a", "kb-b"),
                        "general", "请解释资料", List.of(), null, "request");
        verify(vectorStore).similaritySearch(any(SearchRequest.class));
        assertThat(result.citations()).extracting(citation -> citation.getKnowledgeBaseId())
                .containsExactly("kb-a");
    }

    @Test
    void rejectsRemovedOrNewlyDisallowedBaseAtAnswerTime() {
        KnowledgeCatalogRepository catalog = mock(KnowledgeCatalogRepository.class);
        ScenarioRepository scenarios = mock(ScenarioRepository.class);
        when(scenarios.findByCode("general")).thenReturn(Optional.of(scenario(List.of())));
        KnowledgeChatService service = service(catalog, scenarios, null, null,
                mock(ScenarioToolPolicy.class));
        assertThatThrownBy(() -> service.answerWithKnowledgeBases("conversation", List.of("deleted"),
                "general", "问题", List.of(), null, "request"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).contains("已删除");
                });

        base(catalog, "kb-a", true);
        when(scenarios.findByCode("general")).thenReturn(Optional.of(scenario(List.of("kb-other"))));
        assertThatThrownBy(() -> service.answerWithKnowledgeBases("conversation", List.of("kb-a"),
                "general", "问题", List.of(), null, "request"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).contains("不允许");
                });
    }

    private ScenarioDefinition scenario(List<String> allowed) {
        return ScenarioDefinition.builder().code("general").name("通用")
                .knowledgeMode("可选").allowedKnowledgeBaseIds(allowed).build();
    }

    private void base(KnowledgeCatalogRepository catalog, String id, boolean ready) {
        when(catalog.findKnowledgeBase(id)).thenReturn(Optional.of(
                KnowledgeBase.builder().id(id).name(id).build()));
        when(catalog.findDocuments(id)).thenReturn(ready
                ? List.of(KnowledgeDocument.builder().id("doc-" + id).status(DocumentStatus.READY).build())
                : List.of());
    }

    private Document document(String baseId, String chunkId, String text) {
        return new Document(text, Map.of("knowledge_base_id", baseId, "document_id", "doc-" + baseId,
                "chunk_id", chunkId, "file_name", baseId + ".md"));
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private KnowledgeChatService service(KnowledgeCatalogRepository catalog, ScenarioRepository scenarios,
                                         VectorStore vectorStore, ChatClient client, ScenarioToolPolicy policy) {
        return new KnowledgeChatService(catalog, scenarios, provider(vectorStore), provider(client),
                provider(client), policy, new ToolTraceRecorder(), true, "test-model");
    }
}
