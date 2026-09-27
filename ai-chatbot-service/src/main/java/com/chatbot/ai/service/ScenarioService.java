package com.chatbot.ai.service;

import com.chatbot.ai.domain.scenario.ScenarioDefinition;
import com.chatbot.ai.domain.vo.UpdateScenarioRequest;
import com.chatbot.ai.repository.ScenarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashSet;
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
        List<String> tools = validateTools(request.tools());
        List<String> process = validateProcess(request.process());
        scenario.setName(request.name().trim());
        scenario.setShortName(request.shortName().trim());
        scenario.setSummary(request.summary().trim());
        scenario.setKnowledgeMode(request.knowledgeMode());
        scenario.setTools(tools);
        scenario.setProcess(process);
        scenario.setGuardrail(request.guardrail().trim());
        scenario.setUpdatedAt(Instant.now());
        return repository.save(scenario);
    }

    private List<String> clean(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }

    private List<String> validateTools(List<String> values) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String value : clean(values)) {
            String id = ScenarioToolCatalog.idFor(value).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的业务工具：" + value));
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private List<String> validateProcess(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少填写一个处理步骤");
        }
        LinkedHashSet<String> steps = new LinkedHashSet<>();
        for (String value : values) {
            String step = value == null ? "" : value.trim();
            if (step.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "处理步骤不能为空");
            }
            if (!steps.add(step)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "处理步骤不能重复：" + step);
            }
        }
        return List.copyOf(steps);
    }
}
