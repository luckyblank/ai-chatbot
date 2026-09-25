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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
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
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

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
    void listsCustomerOrdersOnlyWhenScenarioAndActorAuthorizeCustomer() throws Exception {
        when(scenarios.findByCode("commerce-support")).thenReturn(Optional.of(
                ScenarioDefinition.builder().code("commerce-support")
                        .tools(List.of("客户订单查询")).build()));
        when(businessData.findCustomer("CUST-10002")).thenReturn(Optional.of(
                new CustomerServiceDataRepository.CustomerView("CUST-10002", "客户", "金牌", "***", "正常", "优先客服")));
        when(businessData.findOrdersByCustomer("CUST-10002")).thenReturn(
                new CustomerServiceDataRepository.CustomerOrdersView("CUST-10002", List.of(
                        new CustomerServiceDataRepository.OrderView("ORD-1", "CUST-10002", "web", "商品",
                                BigDecimal.TEN, "已完成", "已签收", null, null)), false));

        Map<String, Object> result = executor.execute(node("tool"),
                mapper.readTree("{\"operation\":\"queryCustomerOrders\"}"),
                Map.of("customerNo", "CUST-10002"), null, "commerce-support", ACTOR);

        assertThat(result).containsEntry("operation", "queryCustomerOrders")
                .containsEntry("found", true);
        assertThat(result.get("result").toString()).contains("ORD-1");
        verify(authorization).requireBusinessSubjectAccess(ACTOR, "CUST-10002");
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
    @SuppressWarnings("unchecked")
    void knowledgeReturnsActualEvidenceAndAnHonestEmptyResult() throws Exception {
        ObjectProvider<VectorStore> provider = mock(ObjectProvider.class);
        VectorStore store = mock(VectorStore.class);
        when(provider.getIfAvailable()).thenReturn(store);
        when(catalog.findKnowledgeBase("kb-evidence")).thenReturn(Optional.of(KnowledgeBase.builder().id("kb-evidence").build()));
        when(catalog.findDocuments("kb-evidence")).thenReturn(List.of(KnowledgeDocument.builder().id("policy").status(DocumentStatus.READY).build()));
        when(store.similaritySearch(any(org.springframework.ai.vectorstore.SearchRequest.class)))
            .thenReturn(List.of(new Document("已签收订单可在七日内申请售后。", Map.of("file_name", "售后规则.txt", "document_id", "policy"))))
            .thenReturn(List.of());
        var realExecutor = new WorkflowNodeExecutor(catalog, businessData, scenarios, authorization, provider, null, mapper);
        var hit = realExecutor.execute(node("knowledge"), mapper.readTree("{}"), Map.of("question", "售后条件"), "kb-evidence", "knowledge-research", ACTOR);
        assertThat(hit).containsEntry("matchCount", 1);
        assertThat(hit.get("matches").toString()).contains("七日", "售后规则.txt", "policy");
        var miss = realExecutor.execute(node("knowledge"), mapper.readTree("{}"), Map.of("question", "没有关联的问题"), "kb-evidence", "knowledge-research", ACTOR);
        assertThat(miss).containsEntry("matchCount", 0).containsEntry("matches", List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void modelExecutesConfiguredPromptAndReportsMissingServiceOrEmptyResponse() throws Exception {
        ObjectProvider<ChatClient> provider = mock(ObjectProvider.class);
        ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(provider.getIfAvailable()).thenReturn(client);
        when(client.prompt().user(anyString()).advisors(any(java.util.function.Consumer.class)).call().content())
            .thenReturn("模型返回的测试摘要").thenReturn(" ");
        var realExecutor = new WorkflowNodeExecutor(catalog, businessData, scenarios, authorization, null, provider, mapper);
        var result = realExecutor.execute(node("model"), mapper.readTree("{\"prompt\":\"总结问题\"}"), Map.of("question", "网络故障"), null, "it-service", ACTOR);
        assertThat(result).containsEntry("answer", "模型返回的测试摘要");
        assertThatThrownBy(() -> realExecutor.execute(node("model"), mapper.readTree("{\"prompt\":\"总结问题\"}"), Map.of("question", "网络故障"), null, "it-service", ACTOR))
            .hasMessageContaining("没有返回内容");
        when(provider.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> realExecutor.execute(node("model"), mapper.readTree("{\"prompt\":\"总结问题\"}"), Map.of("question", "网络故障"), null, "it-service", ACTOR))
            .hasMessageContaining("模型服务未启用");
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
