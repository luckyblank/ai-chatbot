package com.chatbot.ai.controller;

import com.chatbot.ai.domain.action.PendingActionStatus;
import com.chatbot.ai.domain.action.PendingActionView;
import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.domain.vo.PendingActionDecisionRequest;
import com.chatbot.ai.security.AuthInterceptor;
import com.chatbot.ai.service.PendingActionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/pending-actions")
@RequiredArgsConstructor
public class PendingActionController {
    private final PendingActionService service;

    @GetMapping
    public List<PendingActionView> list(@RequestParam String conversationId,
                                        HttpServletRequest request) {
        return service.list(actor(request), conversationId);
    }

    @GetMapping("/{actionId}")
    public PendingActionView get(@PathVariable String actionId, HttpServletRequest request) {
        return service.get(actor(request), actionId);
    }

    @PostMapping("/{actionId}/confirm")
    public ResponseEntity<PendingActionView> confirm(@PathVariable String actionId,
                                                      @Valid @RequestBody PendingActionDecisionRequest decision,
                                                      HttpServletRequest request) {
        PendingActionView result = service.confirm(actor(request), actionId, decision.expectedVersion());
        return result.status() == PendingActionStatus.SUCCEEDED
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }

    @PostMapping("/{actionId}/cancel")
    public ResponseEntity<PendingActionView> cancel(@PathVariable String actionId,
                                                     @Valid @RequestBody PendingActionDecisionRequest decision,
                                                     HttpServletRequest request) {
        PendingActionView result = service.cancel(actor(request), actionId, decision.expectedVersion());
        return result.status() == PendingActionStatus.CANCELLED
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(HttpStatus.CONFLICT).body(result);
    }

    private AuthenticatedUser actor(HttpServletRequest request) {
        return (AuthenticatedUser) request.getAttribute(AuthInterceptor.USER_ATTRIBUTE);
    }
}
