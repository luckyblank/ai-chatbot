package com.chatbot.ai.service;

import com.chatbot.ai.domain.scenario.ScenarioDefinition;
import com.chatbot.ai.domain.vo.UpdateScenarioRequest;
import com.chatbot.ai.repository.ScenarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScenarioService {
    private final ScenarioRepository repository;

    public List<ScenarioDefinition> list() {
        return repository.findAll();
    }

    public ScenarioDefinition get(String code) {
        return repository.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "场景不存在"));
    }

    public ScenarioDefinition update(String code, UpdateScenarioRequest request) {
        ScenarioDefinition scenario = get(code);
        scenario.setName(request.name().trim());
        scenario.setShortName(request.shortName().trim());
        scenario.setSummary(request.summary().trim());
        scenario.setKnowledgeMode(request.knowledgeMode());
        scenario.setTools(clean(request.tools()));
        scenario.setProcess(clean(request.process()));
        scenario.setGuardrail(request.guardrail().trim());
        scenario.setUpdatedAt(Instant.now());
        return repository.save(scenario);
    }

    private List<String> clean(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }
}
