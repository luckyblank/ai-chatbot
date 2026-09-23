package com.chatbot.ai.service;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.domain.knowledge.DocumentStatus;
import com.chatbot.ai.domain.knowledge.KnowledgeDocument;
import com.chatbot.ai.domain.scenario.ScenarioDefinition;
import com.chatbot.ai.repository.KnowledgeCatalogRepository;
import com.chatbot.ai.repository.ScenarioRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChatServiceNoEvidenceTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void emptyKnowledgeRecallStillLetsAuthorizedReadOnlyOrderToolReachModel() {
        KnowledgeCatalogRepository catalog = mock(KnowledgeCatalogRepository.class);
        ScenarioRepository scenarios = mock(ScenarioRepository.class);
        VectorStore vectorStore = mock(VectorStore.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        ScenarioToolPolicy policy = mock(ScenarioToolPolicy.class);
        FunctionCallback queryOrder = callback("queryOrder");
        FunctionCallback eligibility = callback("checkAfterSalesEligibility");

        ScenarioDefinition scenario = ScenarioDefinition.builder().code("commerce-support")
                .name("电商售后").knowledgeMode("推荐").tools(List.of("订单履约查询", "售后资格校验"))
                .process(List.of()).guardrail("政策不足时转人工").build();
        when(scenarios.findByCode("commerce-support")).thenReturn(Optional.of(scenario));
        when(catalog.findDocuments("kb-1")).thenReturn(List.of(
                KnowledgeDocument.builder().id("doc-1").status(DocumentStatus.READY).build()));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(policy.allowedCallbacks(scenario)).thenReturn(List.of(queryOrder, eligibility));
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(Consumer.class))).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.toolContext(anyMap())).thenReturn(spec);
        when(spec.tools(any(FunctionCallback[].class))).thenReturn(spec);
        when(spec.call()).thenReturn(response);
        when(response.content()).thenReturn("未找到政策证据；订单真实状态可通过订单查询工具核验。");

        KnowledgeChatService service = new KnowledgeChatService(catalog, scenarios,
                provider(vectorStore), provider(chatClient), provider(chatClient), policy,
                new ToolTraceRecorder(), true, "test-model");
        AuthenticatedUser actor = new AuthenticatedUser("operator-1", "operator", "客服", "USER");

        KnowledgeChatService.AnswerResult result = service.answer("conversation-1", "kb-1",
                "commerce-support", "查询 ORD-20260918-001，并判断能否退货", List.of(),
                actor, "request-1234");

        assertThat(result.answer()).contains("订单");
        ArgumentCaptor<FunctionCallback[]> tools = ArgumentCaptor.forClass(FunctionCallback[].class);
        verify(spec).tools(tools.capture());
        assertThat(tools.getValue()).extracting(FunctionCallback::getName)
                .containsExactly("queryOrder");
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(spec).toolContext(context.capture());
        assertThat(context.getValue())
                .containsEntry(TrustedToolContext.ACTOR_ID, actor.id())
                .containsEntry(TrustedToolContext.CONVERSATION_ID, "conversation-1")
                .containsEntry(TrustedToolContext.REQUEST_ID, "request-1234")
                .containsEntry(TrustedToolContext.POLICY_EVIDENCE_AVAILABLE, false);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void knowledgeBaseWithoutReadyDocumentsStillLetsReadOnlyOrderToolReachModel() {
        KnowledgeCatalogRepository catalog = mock(KnowledgeCatalogRepository.class);
        ScenarioRepository scenarios = mock(ScenarioRepository.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        ScenarioToolPolicy policy = mock(ScenarioToolPolicy.class);
        FunctionCallback queryOrder = callback("queryOrder");
        FunctionCallback eligibility = callback("checkAfterSalesEligibility");
        ScenarioDefinition scenario = ScenarioDefinition.builder().code("commerce-support")
                .name("电商售后").knowledgeMode("推荐").tools(List.of("订单履约查询", "售后资格校验"))
                .process(List.of()).guardrail("政策不足时转人工").build();
        when(scenarios.findByCode("commerce-support")).thenReturn(Optional.of(scenario));
        when(catalog.findDocuments("kb-empty")).thenReturn(List.of());
        when(policy.allowedCallbacks(scenario)).thenReturn(List.of(queryOrder, eligibility));
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(Consumer.class))).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.toolContext(anyMap())).thenReturn(spec);
        when(spec.tools(any(FunctionCallback[].class))).thenReturn(spec);
        when(spec.call()).thenReturn(response);
        when(response.content()).thenReturn("知识证据不可用；订单状态仍可核验。");

        KnowledgeChatService service = new KnowledgeChatService(catalog, scenarios,
                provider((VectorStore) null), provider(chatClient), provider(chatClient), policy,
                new ToolTraceRecorder(), true, "test-model");
        AuthenticatedUser actor = new AuthenticatedUser("operator-1", "operator", "客服", "USER");

        KnowledgeChatService.AnswerResult result = service.answer("conversation-1", "kb-empty",
                "commerce-support", "查询 ORD-20260918-001，并判断能否退货", List.of(),
                actor, "request-no-ready");

        assertThat(result.answer()).contains("订单状态");
        ArgumentCaptor<FunctionCallback[]> tools = ArgumentCaptor.forClass(FunctionCallback[].class);
        verify(spec).tools(tools.capture());
        assertThat(tools.getValue()).extracting(FunctionCallback::getName)
                .containsExactly("queryOrder");
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(spec).toolContext(context.capture());
        assertThat(context.getValue())
                .containsEntry(TrustedToolContext.POLICY_EVIDENCE_AVAILABLE, false);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void missingBusinessIdentifierPromptForbidsGuessingAndAsksForTheMissingField() {
        KnowledgeCatalogRepository catalog = mock(KnowledgeCatalogRepository.class);
        ScenarioRepository scenarios = mock(ScenarioRepository.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        ChatClient.PromptUserSpec promptUser = mock(ChatClient.PromptUserSpec.class);
        ScenarioToolPolicy policy = mock(ScenarioToolPolicy.class);
        FunctionCallback queryOrder = callback("queryOrder");
        FunctionCallback prepareTicket = callback("prepareServiceTicket");
        ScenarioDefinition scenario = ScenarioDefinition.builder().code("commerce-support")
                .name("电商售后").knowledgeMode("推荐")
                .tools(List.of("订单履约查询", "准备工单草案"))
                .process(List.of("核对订单", "准备草案")).guardrail("缺少编号时先追问").build();
        when(scenarios.findByCode("commerce-support")).thenReturn(Optional.of(scenario));
        when(policy.allowedCallbacks(scenario)).thenReturn(List.of(queryOrder, prepareTicket));
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.user(any(Consumer.class))).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.toolContext(anyMap())).thenReturn(spec);
        when(spec.tools(any(FunctionCallback[].class))).thenReturn(spec);
        when(spec.call()).thenReturn(response);
        when(response.content()).thenReturn("请提供订单号或客户编号，我会在核对后准备工单草案。");
        when(promptUser.text(any(String.class))).thenReturn(promptUser);

        KnowledgeChatService service = new KnowledgeChatService(catalog, scenarios,
                provider((VectorStore) null), provider(chatClient), provider(chatClient), policy,
                new ToolTraceRecorder(), true, "test-model");

        KnowledgeChatService.AnswerResult result = service.answer("conversation-missing-id", null,
                "commerce-support", "商品有问题，帮我建工单", List.of(),
                new AuthenticatedUser("operator-1", "operator", "客服", "USER"),
                "request-missing-id");

        ArgumentCaptor<Consumer<ChatClient.PromptUserSpec>> prompt =
                ArgumentCaptor.forClass(Consumer.class);
        verify(spec).user(prompt.capture());
        prompt.getValue().accept(promptUser);
        ArgumentCaptor<String> promptText = ArgumentCaptor.forClass(String.class);
        verify(promptUser).text(promptText.capture());
        assertThat(promptText.getValue())
                .contains("只能追问缺失字段")
                .contains("不得猜测、编造")
                .contains("不得使用客服操作员 userId 代替客户编号");
        assertThat(result.answer()).isEqualTo("请提供订单号或客户编号，我会在核对后准备工单草案。");
        verify(queryOrder, never()).call(any(), any());
        verify(prepareTicket, never()).call(any(), any());
    }

    private FunctionCallback callback(String name) {
        FunctionCallback callback = mock(FunctionCallback.class);
        when(callback.getName()).thenReturn(name);
        return callback;
    }

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
