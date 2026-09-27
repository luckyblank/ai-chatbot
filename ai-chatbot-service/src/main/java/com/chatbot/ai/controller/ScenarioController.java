package com.chatbot.ai.controller;

import com.chatbot.ai.domain.scenario.ScenarioDefinition;
import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.domain.vo.UpdateScenarioRequest;
import com.chatbot.ai.security.AuthInterceptor;
import com.chatbot.ai.service.ScenarioService;
import com.chatbot.ai.service.ScenarioToolCatalog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/scenarios")
@RequiredArgsConstructor
public class ScenarioController {
    private final ScenarioService service;

    @GetMapping
    public List<ScenarioDefinition> list() {
        return service.list();
    }

    @GetMapping("/tool-catalog")
    public List<ScenarioToolCatalog.Entry> toolCatalog() {
        return ScenarioToolCatalog.entries();
    }

    @GetMapping("/{code}")
    public ScenarioDefinition get(@PathVariable String code) {
        return service.get(code);
    }

    @PutMapping("/{code}")
    public ScenarioDefinition update(@PathVariable String code,
                                     @Valid @RequestBody UpdateScenarioRequest request,
                                     HttpServletRequest servletRequest) {
        AuthenticatedUser actor = (AuthenticatedUser) servletRequest.getAttribute(AuthInterceptor.USER_ATTRIBUTE);
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可修改场景配置");
        }
        return service.update(code, request);
    }
}
