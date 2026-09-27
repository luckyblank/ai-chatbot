package com.chatbot.ai.service;

import com.chatbot.ai.domain.scenario.ScenarioDefinition;
import com.chatbot.ai.domain.vo.UpdateScenarioRequest;
import com.chatbot.ai.repository.ScenarioRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioServiceTest {
    @Test
    void acceptsStableIdsAndLegacyLabelsButPersistsOnlyStableIds() {
        ScenarioRepository repository = mock(ScenarioRepository.class);
        ScenarioDefinition scenario = ScenarioDefinition.builder().code("commerce-support")
                .name("原名称").tools(List.of("客户订单查询")).build();
        when(repository.findByCode("commerce-support")).thenReturn(Optional.of(scenario));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ScenarioDefinition updated = new ScenarioService(repository).update("commerce-support",
                request(List.of(" customer-orders ", "客户订单查询", "创建服务工单")));

        assertThat(updated.getTools()).containsExactly("customer-orders", "prepare-service-ticket");
        verify(repository).save(scenario);
    }

    @Test
    void rejectsUnknownToolWithoutMutatingOrSavingScenario() {
        ScenarioRepository repository = mock(ScenarioRepository.class);
        ScenarioDefinition scenario = ScenarioDefinition.builder().code("general")
                .name("原名称").tools(List.of("历史未知工具")).build();
        when(repository.findByCode("general")).thenReturn(Optional.of(scenario));

        assertThatThrownBy(() -> new ScenarioService(repository).update("general",
                request(List.of("customer-orders", "历史未知工具"))))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).contains("历史未知工具");
                });
        assertThat(scenario.getName()).isEqualTo("原名称");
        assertThat(scenario.getTools()).containsExactly("历史未知工具");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsDuplicateStepsInsteadOfSilentlyChangingTheSavedSequence() {
        ScenarioRepository repository = mock(ScenarioRepository.class);
        ScenarioDefinition scenario = ScenarioDefinition.builder().code("general")
                .name("原名称").build();
        when(repository.findByCode("general")).thenReturn(Optional.of(scenario));

        UpdateScenarioRequest request = new UpdateScenarioRequest("新名称", "新简称", "说明", "可选",
                List.of(), List.of("理解任务", " 理解任务 "), "遵守约束");
        assertThatThrownBy(() -> new ScenarioService(repository).update("general", request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).contains("重复");
                });
        assertThat(scenario.getName()).isEqualTo("原名称");
        verify(repository, never()).save(any());
    }

    private UpdateScenarioRequest request(List<String> tools) {
        return new UpdateScenarioRequest("新名称", "新简称", "说明", "可选", tools,
                List.of("理解任务"), "遵守约束");
    }
}
