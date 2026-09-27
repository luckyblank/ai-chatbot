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
    private final KnowledgeBaseService knowledgeBaseService;

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
        List<String> allowedKnowledgeBaseIds = request.allowedKnowledgeBaseIds() == null
                ? scenario.getAllowedKnowledgeBaseIds()
                : validateKnowledgeBaseIds(request.allowedKnowledgeBaseIds(), "可用知识库");
        List<String> defaultKnowledgeBaseIds = request.defaultKnowledgeBaseIds() == null
                ? scenario.getDefaultKnowledgeBaseIds()
                : validateKnowledgeBaseIds(request.defaultKnowledgeBaseIds(), "默认知识库");
        if (defaultKnowledgeBaseIds.size() > 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认知识库最多选择 3 个");
        }
        if (!allowedKnowledgeBaseIds.isEmpty() && !allowedKnowledgeBaseIds.containsAll(defaultKnowledgeBaseIds)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认知识库必须属于当前场景的可用范围");
        }
        scenario.setName(request.name().trim());
        scenario.setShortName(request.shortName().trim());
        scenario.setSummary(request.summary().trim());
        scenario.setKnowledgeMode(request.knowledgeMode());
        scenario.setAllowedKnowledgeBaseIds(allowedKnowledgeBaseIds);
        scenario.setDefaultKnowledgeBaseIds(defaultKnowledgeBaseIds);
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

    private List<String> validateKnowledgeBaseIds(List<String> values, String label) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能包含空 ID");
            }
            String id = value.trim();
            if (!ids.add(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能重复选择：" + id);
            }
            try {
                knowledgeBaseService.getKnowledgeBase(id);
            } catch (ResponseStatusException exception) {
                if (exception.getStatusCode() != HttpStatus.NOT_FOUND) throw exception;
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不存在：" + id);
            }
        }
        return List.copyOf(ids);
    }
}
