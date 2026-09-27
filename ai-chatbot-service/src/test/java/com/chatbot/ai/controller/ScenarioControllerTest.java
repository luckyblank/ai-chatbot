package com.chatbot.ai.controller;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.security.AuthInterceptor;
import com.chatbot.ai.service.ScenarioService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScenarioControllerTest {
    private static final String UPDATE = """
            {"name":"通用智能助手","shortName":"通用助手","summary":"说明","knowledgeMode":"可选",
             "tools":[],"process":["理解任务"],"guardrail":"遵守约束"}
            """;
    private ScenarioService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ScenarioService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ScenarioController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void catalogContainsExecutableChoicesAndLegacyAliases() throws Exception {
        mvc.perform(get("/api/v1/scenarios/tool-catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(7))
                .andExpect(jsonPath("$[0].id").value("customer-entitlements"))
                .andExpect(jsonPath("$[0].requiredInputs[0]").value("客户编号"))
                .andExpect(jsonPath("$[3].label").value("售后人工复核提示"))
                .andExpect(jsonPath("$[3].description").value(org.hamcrest.Matchers.containsString("不会自动判断售后资格通过")))
                .andExpect(jsonPath("$[6].confirmation").value(true))
                .andExpect(jsonPath("$[6].aliases").isArray());
    }

    @Test
    void onlyAdminCanUpdateScenario() throws Exception {
        mvc.perform(put("/api/v1/scenarios/general").contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE).requestAttr(AuthInterceptor.USER_ATTRIBUTE,
                                new AuthenticatedUser("user-1", "user", "普通用户", "USER")))
                .andExpect(status().isForbidden());
        verify(service, never()).update(any(), any());

        mvc.perform(put("/api/v1/scenarios/general").contentType(MediaType.APPLICATION_JSON)
                        .content(UPDATE).requestAttr(AuthInterceptor.USER_ATTRIBUTE,
                                new AuthenticatedUser("admin-1", "admin", "管理员", "ADMIN")))
                .andExpect(status().isOk());
        verify(service).update(any(), any());
    }
}
