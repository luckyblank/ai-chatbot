package com.chatbot.ai.controller;

import com.chatbot.ai.domain.scenario.ScenarioDefinition;
import com.chatbot.ai.domain.vo.UpdateScenarioRequest;
import com.chatbot.ai.service.ScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @GetMapping("/{code}")
    public ScenarioDefinition get(@PathVariable String code) {
        return service.get(code);
    }

    @PutMapping("/{code}")
    public ScenarioDefinition update(@PathVariable String code,
                                     @Valid @RequestBody UpdateScenarioRequest request) {
        return service.update(code, request);
    }
}
