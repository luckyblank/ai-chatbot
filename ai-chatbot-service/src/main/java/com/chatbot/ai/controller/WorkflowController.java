package com.chatbot.ai.controller;

import com.chatbot.ai.domain.vo.RunWorkflowRequest;
import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.domain.vo.SaveWorkflowRequest;
import com.chatbot.ai.domain.workflow.WorkflowDefinition;
import com.chatbot.ai.domain.workflow.WorkflowRun;
import com.chatbot.ai.service.WorkflowService;
import com.chatbot.ai.service.WorkflowTemplateCatalog;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import com.chatbot.ai.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {
    private final WorkflowService service;

    @GetMapping("/templates")
    public List<WorkflowTemplateCatalog.Template> templates(HttpServletRequest request) {
        requireAdmin(request);
        return WorkflowTemplateCatalog.templates();
    }

    @GetMapping public List<WorkflowDefinition> list(HttpServletRequest request) {
        requireAdmin(request);
        return service.list();
    }
    @GetMapping("/{id}") public WorkflowDefinition get(@PathVariable String id,
                                                         HttpServletRequest request) {
        requireAdmin(request);
        return service.get(id);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public WorkflowDefinition create(@Valid @RequestBody SaveWorkflowRequest request,
                                     HttpServletRequest servletRequest) {
        requireAdmin(servletRequest);
        return service.create(request);
    }
    @PutMapping("/{id}") public WorkflowDefinition update(@PathVariable String id,
                                                            @Valid @RequestBody SaveWorkflowRequest request,
                                                            HttpServletRequest servletRequest) {
        requireAdmin(servletRequest);
        return service.update(id, request);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, HttpServletRequest request) {
        requireAdmin(request);
        service.delete(id);
    }
    @PostMapping("/{id}/runs") public WorkflowRun run(@PathVariable String id,
                                                         @RequestBody(required = false) RunWorkflowRequest request,
                                                         HttpServletRequest servletRequest) {
        AuthenticatedUser actor = requireAdmin(servletRequest);
        return service.run(id, request, actor);
    }
    @PostMapping(value = "/{id}/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> streamRun(@PathVariable String id,
                                                    @RequestBody(required = false) RunWorkflowRequest request,
                                                    HttpServletRequest servletRequest,
                                                    HttpServletResponse response) {
        AuthenticatedUser actor = requireAdmin(servletRequest);
        Flux<WorkflowService.RunEvent> events = service.streamRun(id, request, actor);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return events.map(event -> ServerSentEvent.builder((Object) event.data()).event(event.name()).build())
                .onErrorResume(exception -> Flux.just(ServerSentEvent.builder((Object) Map.of(
                        "message", "工作流运行中断，请检查服务端日志后重试。"))
                        .event("error").build()));
    }
    public record ResumeWorkflowRequest(boolean approved) { }

    @PostMapping(value = "/{id}/runs/{runId}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> resumeRun(@PathVariable String id, @PathVariable String runId,
                                                    @RequestBody ResumeWorkflowRequest request,
                                                    HttpServletRequest servletRequest,
                                                    HttpServletResponse response) {
        AuthenticatedUser actor = requireAdmin(servletRequest);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return service.resumeRun(id, runId, request.approved(), actor)
                .map(event -> ServerSentEvent.builder((Object) event.data()).event(event.name()).build())
                .onErrorResume(exception -> Flux.just(ServerSentEvent.builder((Object) Map.of(
                        "message", "审批继续执行失败，请刷新运行记录后重试。"))
                        .event("error").build()));
    }
    @GetMapping("/{id}/runs") public List<WorkflowRun> runs(@PathVariable String id,
                                                              HttpServletRequest request) {
        AuthenticatedUser actor = requireAdmin(request);
        return service.runs(id, actor);
    }
    @GetMapping("/{id}/runs/{runId}") public WorkflowRun getRun(@PathVariable String id,
                                                                  @PathVariable String runId,
                                                                  HttpServletRequest request) {
        AuthenticatedUser actor = requireAdmin(request);
        return service.getRun(id, runId, actor);
    }

    private AuthenticatedUser requireAdmin(HttpServletRequest request) {
        AuthenticatedUser actor = (AuthenticatedUser) request.getAttribute(AuthInterceptor.USER_ATTRIBUTE);
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可运行工作流并查看运行记录");
        }
        return actor;
    }
}
