package com.chatbot.ai.service;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.domain.knowledge.KnowledgeBase;
import com.chatbot.ai.domain.knowledge.KnowledgeDocument;
import com.chatbot.ai.domain.knowledge.DocumentStatus;
import com.chatbot.ai.domain.scenario.ScenarioDefinition;
import com.chatbot.ai.domain.workflow.WorkflowNode;
import com.chatbot.ai.repository.CustomerServiceDataRepository;
import com.chatbot.ai.repository.KnowledgeCatalogRepository;
import com.chatbot.ai.repository.ScenarioRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class WorkflowNodeExecutorTest {
    private static final AuthenticatedUser ACTOR = new AuthenticatedUser("admin-1", "admin", "管理员", "ADMIN");
    private final ObjectMapper mapper = new ObjectMapper();
    private KnowledgeCatalogRepository catalog;
    private CustomerServiceDataRepository businessData;
    private ScenarioRepository scenarios;
    private BusinessAuthorizationService authorization;
    private WorkflowNodeExecutor executor;

    @BeforeEach
    void setUp() {
        catalog = mock(KnowledgeCatalogRepository.class);
        businessData = mock(CustomerServiceDataRepository.class);
        scenarios = mock(ScenarioRepository.class);
        authorization = mock(BusinessAuthorizationService.class);
        executor = new WorkflowNodeExecutor(catalog, businessData, scenarios, authorization, null, null, mapper);
    }

    @Test
    void rejectsMissingConditionValueInsteadOfMatchingEmptySubstring() throws Exception {
        assertThatThrownBy(() -> executor.execute(node("condition"),
                mapper.readTree("{\"field\":\"question\",\"operator\":\"contains\"}"),
                Map.of("question", "申请权限"), null, "it-service", ACTOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("非空 value");
    }

    @Test
    void deniesToolNotAuthorizedByScenario() throws Exception {
        when(scenarios.findByCode("it-service")).thenReturn(Optional.of(
                ScenarioDefinition.builder().code("it-service").tools(List.of()).build()));
        assertThatThrownBy(() -> executor.execute(node("tool"),
                mapper.readTree("{\"operation\":\"queryOrder\"}"),
                Map.of("orderNo", "ORD-123"), null, "it-service", ACTOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("未授权");
    }

    @Test
    void executesAuthorizedReadOnlyOrderQueryAgainstRepository() throws Exception {
        when(scenarios.findByCode("commerce-support")).thenReturn(Optional.of(
                ScenarioDefinition.builder().code("commerce-support")
                        .tools(List.of("订单履约查询")).build()));
        when(authorization.requireOrderAccess(ACTOR, "ORD-123")).thenReturn(null);
        Map<String, Object> result = executor.execute(node("tool"),
                mapper.readTree("{\"operation\":\"queryOrder\"}"),
                Map.of("orderNo", "ORD-123"), null, "commerce-support", ACTOR);
        assertThat(result).containsEntry("operation", "queryOrder")
                .containsEntry("found", false);
        verify(authorization).requireOrderAccess(ACTOR, "ORD-123");
    }

    @Test
    void conditionCanBranchOnActualToolResultField() throws Exception {
        when(scenarios.findByCode("commerce-support")).thenReturn(Optional.of(
                ScenarioDefinition.builder().code("commerce-support")
                        .tools(List.of("订单履约查询")).build()));
        when(authorization.requireOrderAccess(ACTOR, "ORD-123")).thenReturn(
                new CustomerServiceDataRepository.OrderView("ORD-123", "CUST-1", "web", "产品",
                        BigDecimal.TEN, "已付款", "待发货", null, null));
        Map<String, Object> toolResult = executor.execute(node("tool"),
                mapper.readTree("{\"operation\":\"queryOrder\"}"),
                Map.of("orderNo", "ORD-123"), null, "commerce-support", ACTOR);
        assertThat(toolResult.get("result")).isInstanceOf(Map.class);
        Map<String, Object> condition = executor.execute(node("condition"),
                mapper.readTree("{\"field\":\"result.orderStatus\",\"operator\":\"equals\",\"value\":\"已付款\"}"),
                toolResult, null, "commerce-support", ACTOR);
        assertThat(condition).containsEntry("matched", true);
    }

    @Test
    void requiresIndexedDocumentsBeforeKnowledgeRetrieval() throws Exception {
        when(catalog.findKnowledgeBase("kb-1")).thenReturn(Optional.of(KnowledgeBase.builder().id("kb-1").build()));
        when(catalog.findDocuments("kb-1")).thenReturn(List.of());
        assertThatThrownBy(() -> executor.execute(node("knowledge"),
                mapper.readTree("{\"knowledgeBaseId\":\"kb-1\"}"),
                Map.of("question", "怎么操作"), null, "it-service", ACTOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已完成索引");
    }

    @Test
    void timesOutKnowledgeCallAndDoesNotReturnALateResult() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> vectorProvider = mock(ObjectProvider.class);
        VectorStore store = mock(VectorStore.class);
        when(vectorProvider.getIfAvailable()).thenReturn(store);
        when(catalog.findKnowledgeBase("kb-1")).thenReturn(Optional.of(KnowledgeBase.builder().id("kb-1").build()));
        when(catalog.findDocuments("kb-1")).thenReturn(List.of(
                KnowledgeDocument.builder().id("doc-1").status(DocumentStatus.READY).build()));
        when(store.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(200);
                    return List.of();
                });
        WorkflowNodeExecutor timedExecutor = new WorkflowNodeExecutor(
                catalog, businessData, scenarios, authorization, vectorProvider, null, mapper);
        timedExecutor.setExternalTimeoutForTest(Duration.ofMillis(10));

        assertThatThrownBy(() -> timedExecutor.execute(node("knowledge"),
                mapper.readTree("{\"knowledgeBaseId\":\"kb-1\"}"),
                Map.of("question", "退款时效"), null, "commerce-support", ACTOR))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("知识检索超时");
    }

    private WorkflowNode node(String type) {
        return WorkflowNode.builder().id("node-1").name("节点").type(type).config("{}").build();
    }
}
