package com.chatbot.ai.controller;

import com.chatbot.ai.domain.chat.ChatCitation;
import com.chatbot.ai.domain.chat.ChatAttachment;
import com.chatbot.ai.domain.chat.ChatMessageEntry;
import com.chatbot.ai.domain.chat.ChatTraceStep;
import com.chatbot.ai.domain.chat.ConversationSession;
import com.chatbot.ai.repository.ConversationRepository;
import com.chatbot.ai.service.AttachmentService;
import com.chatbot.ai.service.ConversationTitleService;
import com.chatbot.ai.service.KnowledgeBaseService;
import com.chatbot.ai.service.KnowledgeChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.Media;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConversationControllerStreamingTest {
    @TempDir
    Path tempDirectory;

    private ConversationRepository conversationRepository;
    private KnowledgeChatService knowledgeChatService;
    private AttachmentService attachmentService;
    private ChatMemory chatMemory;
    private ConversationController controller;
    private MockMvc mockMvc;
    private ConversationSession session;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        knowledgeChatService = mock(KnowledgeChatService.class);
        attachmentService = mock(AttachmentService.class);
        chatMemory = mock(ChatMemory.class);
        controller = new ConversationController(
                conversationRepository,
                mock(KnowledgeBaseService.class),
                knowledgeChatService,
                attachmentService,
                mock(ConversationTitleService.class),
                chatMemory);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        session = ConversationSession.builder()
                .id("conversation-1")
                .title("新对话")
                .scenarioCode("general")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        when(conversationRepository.findById("conversation-1")).thenReturn(Optional.of(session));
        when(attachmentService.findOwned(eq("conversation-1"), isNull())).thenReturn(List.of());
    }

    @Test
    void streamsDeltasThenCompleteWithCitationsAndTracesAndPersistsExchange() throws Exception {
        ChatCitation citation = ChatCitation.builder()
                .documentId("document-1")
                .fileName("售后规则.md")
                .excerpt("七日无理由规则")
                .build();
        ChatTraceStep trace = ChatTraceStep.builder()
                .phase("retrieval")
                .title("向量检索")
                .status("completed")
                .durationMs(12L)
                .build();
        KnowledgeChatService.AnswerResult answer = new KnowledgeChatService.AnswerResult(
                "您好，可以处理。", List.of(citation), List.of(trace));
        when(knowledgeChatService.streamAnswer(eq("conversation-1"), isNull(), eq("general"),
                eq("可以处理吗"), anyList()))
                .thenReturn(Flux.just(
                        new KnowledgeChatService.AnswerDelta("您好，"),
                        new KnowledgeChatService.AnswerDelta("可以处理。"),
                        new KnowledgeChatService.AnswerCompleted(answer)));
        when(conversationRepository.appendExchange(eq("conversation-1"), any(), any()))
                .thenReturn(new ConversationRepository.AppendExchangeResult(session, false));

        MvcResult pending = mockMvc.perform(post("/api/v1/conversations/conversation-1/messages/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"可以处理吗\"}"))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event:start")))
                .andExpect(content().string(containsString("event:delta")))
                .andExpect(content().string(containsString("event:complete")))
                .andExpect(content().string(containsString("\"fileName\"")))
                .andExpect(content().string(containsString("\"phase\":\"retrieval\"")))
                .andReturn();

        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body.indexOf("event:start")).isLessThan(body.indexOf("event:delta"));
        assertThat(body.indexOf("event:delta")).isLessThan(body.indexOf("event:complete"));

        ArgumentCaptor<ChatMessageEntry> userMessage = ArgumentCaptor.forClass(ChatMessageEntry.class);
        ArgumentCaptor<ChatMessageEntry> assistantMessage = ArgumentCaptor.forClass(ChatMessageEntry.class);
        verify(conversationRepository).appendExchange(eq("conversation-1"),
                userMessage.capture(), assistantMessage.capture());
        assertThat(userMessage.getValue().getContent()).isEqualTo("可以处理吗");
        assertThat(assistantMessage.getValue().getContent()).isEqualTo("您好，可以处理。");
        assertThat(assistantMessage.getValue().getCitations()).containsExactly(citation);
        assertThat(assistantMessage.getValue().getTraces()).containsExactly(trace);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void passesUploadedImageToTheMultimodalModelRequest() throws Exception {
        ChatAttachment image = ChatAttachment.builder()
                .id("image-1")
                .conversationId("conversation-1")
                .name("diagram.png")
                .contentType("image/png")
                .image(true)
                .storageKey("conversation-1/image-1.png")
                .build();
        KnowledgeChatService.AnswerResult answer = new KnowledgeChatService.AnswerResult(
                "图片中是一张流程图。", List.of(), List.of());
        when(attachmentService.findOwned("conversation-1", List.of("image-1")))
                .thenReturn(List.of(image));
        Path imagePath = Files.write(tempDirectory.resolve("test-image.png"), new byte[]{1, 2, 3});
        when(attachmentService.resolve(image)).thenReturn(imagePath);
        when(knowledgeChatService.streamAnswer(eq("conversation-1"), isNull(), eq("general"),
                eq("请分析这张图"), anyList()))
                .thenReturn(Flux.just(new KnowledgeChatService.AnswerCompleted(answer)));
        when(conversationRepository.appendExchange(eq("conversation-1"), any(), any()))
                .thenReturn(new ConversationRepository.AppendExchangeResult(session, false));

        MvcResult pending = mockMvc.perform(post("/api/v1/conversations/conversation-1/messages/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"请分析这张图\",\"attachmentIds\":[\"image-1\"]}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:complete")));

        ArgumentCaptor<List<Media>> mediaCaptor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(knowledgeChatService).streamAnswer(eq("conversation-1"), isNull(), eq("general"),
                eq("请分析这张图"), mediaCaptor.capture());
        assertThat(mediaCaptor.getValue()).hasSize(1);
        assertThat(mediaCaptor.getValue().get(0).getName()).isEqualTo("diagram.png");
        assertThat(mediaCaptor.getValue().get(0).getMimeType().toString()).isEqualTo("image/png");
    }

    @Test
    void regeneratesFromEditedUserMessageAndReplacesFollowingExchange() throws Exception {
        ChatAttachment image = ChatAttachment.builder()
                .id("image-1").conversationId("conversation-1").name("original.png")
                .contentType("image/png").image(true).storageKey("conversation-1/image-1.png").build();
        session.setMessages(new ArrayList<>(List.of(
                ChatMessageEntry.builder().role("user").content("旧问题").attachments(List.of(image))
                        .createdAt(Instant.now()).build(),
                ChatMessageEntry.builder().role("assistant").content("旧回答").createdAt(Instant.now()).build())));
        KnowledgeChatService.AnswerResult answer = new KnowledgeChatService.AnswerResult(
                "新回答", List.of(), List.of());
        when(attachmentService.findOwned("conversation-1", List.of("image-1"))).thenReturn(List.of(image));
        Path imagePath = Files.write(tempDirectory.resolve("original.png"), new byte[]{1, 2, 3});
        when(attachmentService.resolve(image)).thenReturn(imagePath);
        when(knowledgeChatService.streamAnswer(eq("conversation-1"), isNull(), eq("general"),
                eq("修改后的问题"), anyList()))
                .thenReturn(Flux.just(
                        new KnowledgeChatService.AnswerDelta("新回"),
                        new KnowledgeChatService.AnswerCompleted(answer)));

        MvcResult pending = mockMvc.perform(post(
                        "/api/v1/conversations/conversation-1/messages/0/regenerate/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"content\":\"修改后的问题\",\"attachmentIds\":[\"image-1\"]}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:delta")))
                .andExpect(content().string(containsString("event:complete")));

        ArgumentCaptor<ChatMessageEntry> editedUser = ArgumentCaptor.forClass(ChatMessageEntry.class);
        ArgumentCaptor<ChatMessageEntry> regeneratedAssistant = ArgumentCaptor.forClass(ChatMessageEntry.class);
        verify(chatMemory, times(2)).clear("conversation-1");
        verify(conversationRepository).replaceExchange(eq("conversation-1"), eq(0),
                editedUser.capture(), regeneratedAssistant.capture());
        assertThat(editedUser.getValue().getContent()).isEqualTo("修改后的问题");
        assertThat(editedUser.getValue().getAttachments()).containsExactly(image);
        assertThat(regeneratedAssistant.getValue().getContent()).isEqualTo("新回答");
    }

    @Test
    void turnsGenerationFailureIntoTerminalErrorEventWithoutPersistingPartialExchange() throws Exception {
        when(knowledgeChatService.streamAnswer(eq("conversation-1"), isNull(), eq("general"),
                eq("测试异常"), anyList()))
                .thenReturn(Flux.error(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "模型服务暂不可用")));

        MvcResult pending = mockMvc.perform(post("/api/v1/conversations/conversation-1/messages/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"测试异常\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:start")))
                .andExpect(content().string(containsString("event:error")))
                .andReturn();

        assertThat(completed.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("模型服务暂不可用");

        verify(conversationRepository, never()).appendExchange(any(), any(), any());
    }

    @Test
    void emitsHeartbeatWhileWaitingAndStopsAfterEitherTerminalEvent() {
        for (String terminal : List.of("complete", "error")) {
            Flux<ServerSentEvent<Object>> delayedTerminal = Mono
                    .<ServerSentEvent<Object>>just(
                            ServerSentEvent.<Object>builder()
                                    .event(terminal).data(terminal).build())
                    .delayElement(Duration.ofMillis(160))
                    .flux();

            List<String> names = controller.withHeartbeat(delayedTerminal, Duration.ofMillis(20))
                    .map(ServerSentEvent::event)
                    .collectList()
                    .block(Duration.ofSeconds(5));

            assertThat(names).isNotNull().contains("ping");
            assertThat(names.get(names.size() - 1)).isEqualTo(terminal);
            assertThat(names.subList(0, names.size() - 1)).containsOnly("ping");
        }
    }

    @Test
    void restoresSavedMemoryWhenRegenerationFails() throws Exception {
        session.setMessages(new ArrayList<>(List.of(
                ChatMessageEntry.builder().role("user").content("原问题").createdAt(Instant.now()).build(),
                ChatMessageEntry.builder().role("assistant").content("原回答").createdAt(Instant.now()).build())));
        when(knowledgeChatService.streamAnswer(eq("conversation-1"), isNull(), eq("general"),
                eq("修改后的问题"), anyList()))
                .thenReturn(Flux.error(new IllegalStateException("模型断开")));

        MvcResult pending = mockMvc.perform(post(
                        "/api/v1/conversations/conversation-1/messages/0/regenerate/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"content\":\"修改后的问题\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:error")));

        verify(chatMemory, times(2)).clear("conversation-1");
        @SuppressWarnings({"rawtypes", "unchecked"})
        ArgumentCaptor<List<org.springframework.ai.chat.messages.Message>> history =
                (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(chatMemory).add(eq("conversation-1"), history.capture());
        assertThat(history.getValue()).hasSize(2);
        assertThat(session.getMessages()).extracting(ChatMessageEntry::getContent)
                .containsExactly("原问题", "原回答");
        verify(conversationRepository, never()).replaceExchange(any(), anyInt(), any(), any());
    }
}
