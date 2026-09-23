package com.chatbot.ai.controller;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.domain.chat.ConversationSession;
import com.chatbot.ai.repository.ConversationRepository;
import com.chatbot.ai.security.AuthInterceptor;
import com.chatbot.ai.service.AttachmentService;
import com.chatbot.ai.service.ConversationTitleService;
import com.chatbot.ai.service.KnowledgeBaseService;
import com.chatbot.ai.service.KnowledgeChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;

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
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = mock(ConversationRepository.class);
        ConversationController controller = new ConversationController(repository,
                mock(KnowledgeBaseService.class), mock(KnowledgeChatService.class),
                mock(AttachmentService.class), mock(ConversationTitleService.class), mock(ChatMemory.class));
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

    private ConversationSession session(String ownerId) {
        return ConversationSession.builder().id("conversation-1").ownerId(ownerId)
                .title("会话").scenarioCode("general")
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
    }
}
