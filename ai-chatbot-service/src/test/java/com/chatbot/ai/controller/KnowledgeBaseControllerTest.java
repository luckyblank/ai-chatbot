package com.chatbot.ai.controller;

import com.chatbot.ai.domain.knowledge.KnowledgeDocument;
import com.chatbot.ai.domain.scenario.ScenarioDefinition;
import com.chatbot.ai.repository.KnowledgeCatalogRepository;
import com.chatbot.ai.repository.KnowledgeChunkRepository;
import com.chatbot.ai.service.KnowledgeBaseService;
import com.chatbot.ai.service.KnowledgeIngestionService;
import com.chatbot.ai.service.ScenarioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KnowledgeBaseControllerTest {
    private KnowledgeBaseService knowledgeBaseService;
    private KnowledgeIngestionService ingestionService;
    private ScenarioService scenarioService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        knowledgeBaseService = mock(KnowledgeBaseService.class);
        ingestionService = mock(KnowledgeIngestionService.class);
        scenarioService = mock(ScenarioService.class);
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(
                knowledgeBaseService, ingestionService, mock(KnowledgeCatalogRepository.class),
                mock(KnowledgeChunkRepository.class), scenarioService))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void rejectsDeleteWhenAnyScenarioAllowsOrDefaultsToTheKnowledgeBase() throws Exception {
        when(scenarioService.list()).thenReturn(List.of(
                scenario("required", "必选知识场景", "必选", List.of(), List.of("base-1")),
                scenario("restricted", "受限知识场景", "可选", List.of("base-1"), List.of()),
                scenario("unrelated", "其他场景", "可选", List.of("base-2"), List.of())));

        mvc.perform(delete("/api/v1/knowledge-bases/base-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("必选知识场景（required）"),
                        org.hamcrest.Matchers.containsString("受限知识场景（restricted）"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("其他场景")))));

        verify(knowledgeBaseService, never()).listDocuments("base-1");
        verify(knowledgeBaseService, never()).deleteKnowledgeBase("base-1");
        verify(ingestionService, never()).deleteVectors(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void deletesVectorsAndKnowledgeBaseWhenNoScenarioReferencesIt() throws Exception {
        KnowledgeDocument document = KnowledgeDocument.builder().id("document-1")
                .knowledgeBaseId("base-1").build();
        when(scenarioService.list()).thenReturn(List.of(
                scenario("other", "其他场景", "推荐", List.of("base-2"), List.of("base-2"))));
        when(knowledgeBaseService.listDocuments("base-1")).thenReturn(List.of(document));

        mvc.perform(delete("/api/v1/knowledge-bases/base-1"))
                .andExpect(status().isNoContent());

        InOrder order = inOrder(knowledgeBaseService, scenarioService, ingestionService);
        order.verify(knowledgeBaseService).getKnowledgeBase("base-1");
        order.verify(scenarioService).list();
        order.verify(knowledgeBaseService).listDocuments("base-1");
        order.verify(ingestionService).deleteVectors(document);
        order.verify(knowledgeBaseService).deleteKnowledgeBase("base-1");
    }

    @Test
    void missingKnowledgeBaseStillReturnsNotFound() throws Exception {
        when(knowledgeBaseService.getKnowledgeBase("missing"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在"));

        mvc.perform(delete("/api/v1/knowledge-bases/missing"))
                .andExpect(status().isNotFound());

        verify(scenarioService, never()).list();
        verify(knowledgeBaseService, never()).deleteKnowledgeBase("missing");
    }

    private ScenarioDefinition scenario(String code, String name, String knowledgeMode,
                                        List<String> allowed, List<String> defaults) {
        return ScenarioDefinition.builder().code(code).name(name).knowledgeMode(knowledgeMode)
                .allowedKnowledgeBaseIds(allowed).defaultKnowledgeBaseIds(defaults).build();
    }
}
