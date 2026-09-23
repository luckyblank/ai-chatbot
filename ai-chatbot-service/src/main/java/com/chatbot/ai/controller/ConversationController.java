package com.chatbot.ai.controller;

import com.chatbot.ai.domain.chat.ChatMessageEntry;
import com.chatbot.ai.domain.chat.ConversationSession;
import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.domain.vo.ChatAnswer;
import com.chatbot.ai.domain.vo.CreateConversationRequest;
import com.chatbot.ai.domain.vo.RegenerateMessageRequest;
import com.chatbot.ai.domain.vo.SendMessageRequest;
import com.chatbot.ai.domain.vo.RenameConversationRequest;
import com.chatbot.ai.domain.chat.ChatAttachment;
import com.chatbot.ai.repository.ConversationRepository;
import com.chatbot.ai.service.KnowledgeBaseService;
import com.chatbot.ai.service.KnowledgeChatService;
import com.chatbot.ai.service.AttachmentService;
import com.chatbot.ai.service.ConversationTitleService;
import com.chatbot.ai.security.AuthInterceptor;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.core.io.FileSystemResource;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.Media;
import org.springframework.util.MimeType;
import java.nio.charset.StandardCharsets;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ConversationController {
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private final ConversationRepository conversationRepository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeChatService knowledgeChatService;
    private final AttachmentService attachmentService;
    private final ConversationTitleService conversationTitleService;
    private final ChatMemory chatMemory;

    @GetMapping
    public List<ConversationSession> list(
            @RequestParam(value = "knowledgeBaseId", required = false) String knowledgeBaseId,
            HttpServletRequest request) {
        AuthenticatedUser actor = requireActor(request);
        return conversationRepository.findByKnowledgeBaseId(knowledgeBaseId).stream()
                .filter(session -> canAccess(session, actor))
                .toList();
    }

    @GetMapping("/{conversationId}")
    public ConversationSession get(@PathVariable String conversationId, HttpServletRequest request) {
        return requireOwnedConversation(conversationId, requireActor(request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationSession create(@Valid @RequestBody CreateConversationRequest request,
                                      HttpServletRequest servletRequest) {
        AuthenticatedUser actor = requireActor(servletRequest);
        if (request.knowledgeBaseId() != null && !request.knowledgeBaseId().isBlank()) {
            knowledgeBaseService.getKnowledgeBase(request.knowledgeBaseId());
        }
        Instant now = Instant.now();
        String title = request.title() == null || request.title().isBlank()
                ? "新对话" : request.title().trim();
        ConversationSession session = ConversationSession.builder()
                .id(UUID.randomUUID().toString())
                .ownerId(actor.id())
                .knowledgeBaseId(request.knowledgeBaseId() == null || request.knowledgeBaseId().isBlank()
                        ? null : request.knowledgeBaseId())
                .scenarioCode(request.scenarioCode() == null || request.scenarioCode().isBlank()
                        ? "general" : request.scenarioCode())
                .title(title)
                .titleCustomized(!ConversationRepository.isDefaultTitle(title))
                .createdAt(now)
                .updatedAt(now)
                .build();
        return conversationRepository.save(session);
    }

    @PatchMapping("/{conversationId}")
    public ConversationSession rename(@PathVariable String conversationId,
                                      @Valid @RequestBody RenameConversationRequest request,
                                      HttpServletRequest servletRequest) {
        requireOwnedConversation(conversationId, requireActor(servletRequest));
        return conversationRepository.rename(conversationId, request.title().trim());
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String conversationId, HttpServletRequest request) {
        requireOwnedConversation(conversationId, requireActor(request));
        conversationRepository.delete(conversationId);
        attachmentService.deleteConversation(conversationId);
    }

    @PostMapping("/{conversationId}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatAttachment uploadAttachment(@PathVariable String conversationId,
                                           @RequestParam("file") MultipartFile file,
                                           HttpServletRequest request) {
        requireOwnedConversation(conversationId, requireActor(request));
        return attachmentService.store(conversationId, file);
    }

    @GetMapping("/{conversationId}/attachments/{attachmentId}/content")
    public ResponseEntity<FileSystemResource> attachmentContent(@PathVariable String conversationId,
                                                                 @PathVariable String attachmentId,
                                                                 HttpServletRequest request) {
        requireOwnedConversation(conversationId, requireActor(request));
        ChatAttachment attachment = attachmentService.getOwned(conversationId, attachmentId);
        FileSystemResource resource = new FileSystemResource(attachmentService.resolve(attachment));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.inline()
                        .filename(attachment.getName(), StandardCharsets.UTF_8).build().toString())
                .body(resource);
    }

    @PostMapping("/{conversationId}/messages")
    public ChatAnswer send(@PathVariable String conversationId,
                           @Valid @RequestBody SendMessageRequest request,
                           HttpServletRequest servletRequest) {
        AuthenticatedUser actor = requireActor(servletRequest);
        ConversationSession session = requireOwnedConversation(conversationId, actor);
        List<ChatAttachment> attachments = ownedAttachments(session.getId(), request.attachmentIds());
        KnowledgeChatService.AnswerResult result = knowledgeChatService.answer(
                session.getId(), session.getKnowledgeBaseId(), session.getScenarioCode(), request.message(),
                imageMedia(attachments), actor, request.requestId());
        return persistExchange(session, request, attachments, result, request.requestId());
    }

    @PostMapping(value = "/{conversationId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> sendStream(@PathVariable String conversationId,
                                                     @Valid @RequestBody SendMessageRequest request,
                                                     HttpServletRequest servletRequest,
                                                     HttpServletResponse response) {
        AuthenticatedUser actor = requireActor(servletRequest);
        ConversationSession session = requireOwnedConversation(conversationId, actor);
        List<ChatAttachment> attachments = ownedAttachments(session.getId(), request.attachmentIds());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        Flux<ServerSentEvent<Object>> answerEvents = Flux.defer(() -> {
                    restoreChatMemory(session);
                    return knowledgeChatService.streamAnswer(
                                    session.getId(), session.getKnowledgeBaseId(), session.getScenarioCode(),
                                    request.message(), imageMedia(attachments), actor, request.requestId())
                            .map(event -> {
                                if (event instanceof KnowledgeChatService.AnswerDelta delta) {
                                    return event("delta", Map.of("delta", delta.delta()));
                                }
                                KnowledgeChatService.AnswerCompleted completed =
                                        (KnowledgeChatService.AnswerCompleted) event;
                                ChatAnswer answer = persistExchange(session, request, attachments,
                                        completed.result(), request.requestId());
                                return event("complete", answer);
                            });
                })
                .doFinally(signal -> restoreChatMemory(session));

        return streamResponse(session.getId(), answerEvents);
    }

    @PostMapping(value = "/{conversationId}/messages/{messageIndex}/regenerate/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> regenerateStream(@PathVariable String conversationId,
                                                           @PathVariable int messageIndex,
                                                           @Valid @RequestBody RegenerateMessageRequest request,
                                                           HttpServletRequest servletRequest,
                                                           HttpServletResponse response) {
        AuthenticatedUser actor = requireActor(servletRequest);
        ConversationSession session = requireOwnedConversation(conversationId, actor);
        ChatMessageEntry original = requireUserMessage(session, messageIndex);
        String stableRequestId = original.getRequestId() == null || original.getRequestId().isBlank()
                ? request.requestId() : original.getRequestId();
        List<ChatAttachment> attachments = request.attachmentIds() == null
                ? original.getAttachments() == null ? List.of() : List.copyOf(original.getAttachments())
                : ownedAttachments(session.getId(), request.attachmentIds());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");

        Flux<ServerSentEvent<Object>> answerEvents = Flux.defer(() -> {
                    rebuildChatMemory(session, messageIndex);
                    return knowledgeChatService.streamAnswer(
                                    session.getId(), session.getKnowledgeBaseId(), session.getScenarioCode(),
                                    request.content(), imageMedia(attachments), actor, stableRequestId)
                            .map(event -> {
                                if (event instanceof KnowledgeChatService.AnswerDelta delta) {
                                    return event("delta", Map.of("delta", delta.delta()));
                                }
                                KnowledgeChatService.AnswerCompleted completed =
                                        (KnowledgeChatService.AnswerCompleted) event;
                                ChatAnswer answer = persistRegeneratedExchange(
                                        session, messageIndex, request.content(), attachments,
                                        completed.result(), stableRequestId);
                                return event("complete", answer);
                            });
                })
                .doFinally(signal -> restoreChatMemory(session));

        return streamResponse(session.getId(), answerEvents);
    }

    private Flux<ServerSentEvent<Object>> streamResponse(
            String conversationId, Flux<ServerSentEvent<Object>> answerEvents) {
        Flux<ServerSentEvent<Object>> events = answerEvents
                .onErrorResume(exception -> Flux.just(event("error", Map.of(
                        "message", clientErrorMessage(exception)))))
                .concatWithValues(event("error", Map.of("message", "流式回答意外中断，请重试")))
                .subscribeOn(Schedulers.boundedElastic());
        return Flux.concat(Flux.just(event("start", Map.of("conversationId", conversationId))),
                withHeartbeat(events, HEARTBEAT_INTERVAL));
    }

    Flux<ServerSentEvent<Object>> withHeartbeat(
            Flux<ServerSentEvent<Object>> events, Duration interval) {
        Flux<ServerSentEvent<Object>> heartbeats = Flux.interval(interval)
                .map(tick -> event("ping", Map.of()));
        return Flux.merge(events, heartbeats)
                .takeUntil(item -> "complete".equals(item.event()) || "error".equals(item.event()));
    }

    private ChatMessageEntry requireUserMessage(ConversationSession session, int messageIndex) {
        if (session.getMessages() == null || messageIndex < 0 || messageIndex >= session.getMessages().size()) {
            throw new IllegalArgumentException("消息不存在");
        }
        ChatMessageEntry message = session.getMessages().get(messageIndex);
        if (!"user".equalsIgnoreCase(message.getRole())) {
            throw new IllegalArgumentException("仅支持编辑用户发送的消息");
        }
        return message;
    }

    private void rebuildChatMemory(ConversationSession session, int messageIndex) {
        chatMemory.clear(session.getId());
        List<ChatMessageEntry> messages = session.getMessages() == null ? List.of() : session.getMessages();
        List<Message> history = new ArrayList<>();
        for (int index = 0; index < Math.min(messageIndex, messages.size()); index++) {
            ChatMessageEntry entry = messages.get(index);
            if (entry.getContent() == null || entry.getContent().isBlank()) continue;
            if ("user".equalsIgnoreCase(entry.getRole())) {
                List<ChatAttachment> attachments = entry.getAttachments() == null
                        ? List.of() : entry.getAttachments();
                history.add(new UserMessage(entry.getContent(), imageMedia(attachments)));
            }
            if ("assistant".equalsIgnoreCase(entry.getRole())) history.add(new AssistantMessage(entry.getContent()));
        }
        if (!history.isEmpty()) chatMemory.add(session.getId(), history);
    }

    private void restoreChatMemory(ConversationSession session) {
        rebuildChatMemory(session, Integer.MAX_VALUE);
    }

    private ChatAnswer persistRegeneratedExchange(ConversationSession session,
                                                   int messageIndex,
                                                   String content,
                                                   List<ChatAttachment> attachments,
                                                   KnowledgeChatService.AnswerResult result,
                                                   String requestId) {
        ChatMessageEntry userMessage = ChatMessageEntry.builder()
                .requestId(requestId)
                .role("user")
                .content(content.trim())
                .attachments(attachments)
                .createdAt(Instant.now())
                .build();
        ChatMessageEntry assistantMessage = ChatMessageEntry.builder()
                .requestId(requestId)
                .role("assistant")
                .content(result.answer())
                .createdAt(Instant.now())
                .citations(result.citations())
                .traces(result.traces())
                .build();
        conversationRepository.replaceExchange(session.getId(), messageIndex, userMessage, assistantMessage);
        return new ChatAnswer(session.getId(), result.answer(), result.citations(), result.traces());
    }

    private List<ChatAttachment> ownedAttachments(String conversationId, List<String> attachmentIds) {
        List<ChatAttachment> attachments = attachmentService.findOwned(conversationId, attachmentIds);
        if (attachmentIds != null && attachments.size() != attachmentIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "部分附件不存在或不属于当前会话");
        }
        return attachments;
    }

    private List<Media> imageMedia(List<ChatAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return List.of();
        return attachments.stream()
                .filter(ChatAttachment::isImage)
                .map(attachment -> Media.builder()
                        .id(attachment.getId())
                        .name(attachment.getName())
                        .mimeType(MimeType.valueOf(attachment.getContentType()))
                        .data(new FileSystemResource(attachmentService.resolve(attachment)))
                        .build())
                .toList();
    }

    private ChatAnswer persistExchange(ConversationSession session,
                                       SendMessageRequest request,
                                       List<ChatAttachment> attachments,
                                       KnowledgeChatService.AnswerResult result,
                                       String requestId) {
        Instant now = Instant.now();
        ChatMessageEntry userMessage = ChatMessageEntry.builder()
                .requestId(requestId)
                .role("user")
                .content(request.message())
                .attachments(attachments)
                .createdAt(now)
                .build();
        ChatMessageEntry assistantMessage = ChatMessageEntry.builder()
                .requestId(requestId)
                .role("assistant")
                .content(result.answer())
                .createdAt(Instant.now())
                .citations(result.citations())
                .traces(result.traces())
                .build();
        ConversationRepository.AppendExchangeResult appendResult = conversationRepository.appendExchange(
                session.getId(), userMessage, assistantMessage);
        if (appendResult.shouldGenerateTitle()) {
            String generatedTitle = conversationTitleService.generate(request.message());
            conversationRepository.renameDefaultTitle(session.getId(), generatedTitle);
        }
        return new ChatAnswer(session.getId(), result.answer(), result.citations(), result.traces());
    }

    private ServerSentEvent<Object> event(String eventName, Object data) {
        return ServerSentEvent.builder(data).event(eventName).build();
    }

    private String clientErrorMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) {
            if (current instanceof ResponseStatusException) {
                break;
            }
            current = current.getCause();
        }
        if (current instanceof ResponseStatusException statusException
                && statusException.getReason() != null
                && !statusException.getReason().isBlank()) {
            return statusException.getReason();
        }
        return "回答生成失败，请稍后重试";
    }

    private AuthenticatedUser requireActor(HttpServletRequest request) {
        AuthenticatedUser actor = request == null ? null
                : (AuthenticatedUser) request.getAttribute(AuthInterceptor.USER_ATTRIBUTE);
        if (actor == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
        return actor;
    }

    private ConversationSession requireOwnedConversation(String conversationId, AuthenticatedUser actor) {
        ConversationSession session = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        if (!canAccess(session, actor)) {
            // Avoid revealing whether another operator's conversation exists.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在");
        }
        return session;
    }

    private boolean canAccess(ConversationSession session, AuthenticatedUser actor) {
        if (session.getOwnerId() == null || session.getOwnerId().isBlank()) {
            // Conservative migration rule: only administrators can inspect legacy,
            // unowned conversations; access never assigns ownership implicitly.
            return "ADMIN".equals(actor.role());
        }
        return session.getOwnerId().equals(actor.id()) || "ADMIN".equals(actor.role());
    }
}
