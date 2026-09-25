package com.chatbot.ai.controller;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.domain.chat.ConversationSession;
import com.chatbot.ai.domain.chat.ChatCitation;
import com.chatbot.ai.domain.chat.ChatMessageEntry;
import com.chatbot.ai.domain.knowledge.KnowledgeChunk;
import com.chatbot.ai.repository.ConversationRepository;
import com.chatbot.ai.repository.KnowledgeChunkRepository;
import com.chatbot.ai.security.AuthInterceptor;
import com.chatbot.ai.service.AttachmentService;
import com.chatbot.ai.service.ConversationTitleService;
import com.chatbot.ai.service.KnowledgeBaseService;
import com.chatbot.ai.service.KnowledgeChatService;
import com.chatbot.ai.service.WidgetAssistantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationControllerOwnershipTest {
    private static final AuthenticatedUser OPERATOR_A =
            new AuthenticatedUser("operator-a", "operator-a", "客服 A", "USER");
    private static final AuthenticatedUser OPERATOR_B =
            new AuthenticatedUser("operator-b", "operator-b", "客服 B", "USER");
    private static final AuthenticatedUser ADMIN =
            new AuthenticatedUser("admin-1", "admin", "管理员", "ADMIN");

    private ConversationRepository repository;
    private KnowledgeBaseService knowledgeBaseService;
    private WidgetAssistantService widgetAssistantService;
    private KnowledgeChunkRepository chunkRepository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = mock(ConversationRepository.class);
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        widgetAssistantService = mock(WidgetAssistantService.class);
        chunkRepository = mock(KnowledgeChunkRepository.class);
        ConversationController controller = new ConversationController(repository,
                knowledgeBaseService, mock(KnowledgeChatService.class),
                mock(AttachmentService.class), mock(ConversationTitleService.class), widgetAssistantService,
                chunkRepository, mock(ChatMemory.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void anotherOperatorCannotDiscoverConversation() throws Exception {
        when(repository.findById("conversation-1")).thenReturn(Optional.of(session(OPERATOR_A.id())));

        mvc.perform(get("/api/v1/conversations/conversation-1")
                        .requestAttr(AuthInterceptor.USER_ATTRIBUTE, OPERATOR_B))
                .andExpect(status().isNotFound());
    }

    @Test
    void legacyUnownedConversationIsAdminOnlyAndNeverImplicitlyClaimed() throws Exception {
        ConversationSession legacy = session(null);
        when(repository.findById("conversation-1")).thenReturn(Optional.of(legacy));

        mvc.perform(get("/api/v1/conversations/conversation-1")
                        .requestAttr(AuthInterceptor.USER_ATTRIBUTE, OPERATOR_A))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/conversations/conversation-1")
                        .requestAttr(AuthInterceptor.USER_ATTRIBUTE, ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").doesNotExist());
        assertThat(legacy.getOwnerId()).isNull();
    }

    @Test
    void existingConversationShowsFocusedEvidenceWithoutChangingStoredCitation() throws Exception {
        ChatCitation savedCitation = ChatCitation.builder()
                .documentId("document-1").chunkId("chunk-1")
                .fileName("售后手册.md").excerpt("旧的 180 字摘要…").build();
        ConversationSession saved = session(OPERATOR_A.id());
        saved.setKnowledgeBaseId("base-1");
        saved.setMessages(List.of(
                ChatMessageEntry.builder().role("user").content("七日无理由退货怎么判断？").build(),
                ChatMessageEntry.builder().role("assistant")
                        .content("先核对签收后的七日窗口。[资料 1]")
                        .citations(List.of(savedCitation)).build()));
        when(repository.findById("conversation-1")).thenReturn(Optional.of(saved));
        when(chunkRepository.findById("chunk-1")).thenReturn(Optional.of(KnowledgeChunk.builder()
                .id("chunk-1").knowledgeBaseId("base-1").documentId("document-1")
                .content("# 一、服务范围\n客服应记录订单信息。\n# 三、七日无理由退货判断\n"
                        + "网络销售应先判断消费者是否处于签收后的七日窗口，再核对法定例外。")
                .build()));

        mvc.perform(get("/api/v1/conversations/conversation-1")
                        .requestAttr(AuthInterceptor.USER_ATTRIBUTE, OPERATOR_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[1].citations[0].excerpt")
                        .value("网络销售应先判断消费者是否处于签收后的七日窗口，再核对法定例外。"))
                .andExpect(jsonPath("$.messages[1].citations[0].sectionTitle")
                        .value("三、七日无理由退货判断"));
        assertThat(savedCitation.getExcerpt()).isEqualTo("旧的 180 字摘要…");
    }

    @Test
    void citationHydrationDoesNotReadAChunkFromAnotherKnowledgeBase() throws Exception {
        ConversationSession saved = session(OPERATOR_A.id());
        saved.setKnowledgeBaseId("base-1");
        saved.setMessages(List.of(ChatMessageEntry.builder().role("assistant")
                .citations(List.of(ChatCitation.builder().documentId("document-1")
                        .chunkId("chunk-1").excerpt("原摘要").build())).build()));
        when(repository.findById("conversation-1")).thenReturn(Optional.of(saved));
        when(chunkRepository.findById("chunk-1")).thenReturn(Optional.of(KnowledgeChunk.builder()
                .id("chunk-1").knowledgeBaseId("base-2").documentId("document-1")
                .content("其他知识库的内容").build()));

        mvc.perform(get("/api/v1/conversations/conversation-1")
                        .requestAttr(AuthInterceptor.USER_ATTRIBUTE, OPERATOR_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].citations[0].excerpt").value("原摘要"));
    }

    @Test
    void newConversationIsBoundToAuthenticatedOperator() throws Exception {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        String response = mvc.perform(post("/api/v1/conversations")
                        .requestAttr(AuthInterceptor.USER_ATTRIBUTE, OPERATOR_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"售后咨询\",\"scenarioCode\":\"commerce-support\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(OPERATOR_A.id()))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).contains(OPERATOR_A.id());
    }

    @Test
    void widgetConversationUsesServerResolvedKnowledgeAndRejectsUnavailableKnowledge() throws Exception {
        when(widgetAssistantService.context()).thenReturn(new WidgetAssistantService.Context(
                "knowledge-research", "product-base", "产品手册", true, "已接入产品知识"));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post("/api/v1/conversations/widget/conversations")
                        .requestAttr(AuthInterceptor.USER_ATTRIBUTE, OPERATOR_A))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(OPERATOR_A.id()))
                .andExpect(jsonPath("$.scenarioCode").value("knowledge-research"))
                .andExpect(jsonPath("$.knowledgeBaseId").value("product-base"));

        when(widgetAssistantService.context()).thenReturn(new WidgetAssistantService.Context(
                "knowledge-research", "product-base", "产品手册", false, "知识库尚未索引"));
        mvc.perform(post("/api/v1/conversations/widget/conversations")
                        .requestAttr(AuthInterceptor.USER_ATTRIBUTE, OPERATOR_A))
                .andExpect(status().isConflict());
    }

    private ConversationSession session(String ownerId) {
        return ConversationSession.builder().id("conversation-1").ownerId(ownerId)
                .title("会话").scenarioCode("general")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
    }
}
