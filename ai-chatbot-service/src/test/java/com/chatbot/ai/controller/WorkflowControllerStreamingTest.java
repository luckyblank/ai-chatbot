package com.chatbot.ai.controller;

import com.chatbot.ai.domain.workflow.WorkflowRun;
import com.chatbot.ai.domain.vo.RunWorkflowRequest;
import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.security.AuthInterceptor;
import com.chatbot.ai.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowControllerStreamingTest {
    @Test
    void rejectsNonAdminWorkflowExecution() throws Exception {
        WorkflowService service = mock(WorkflowService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkflowController(service)).build();
        mvc.perform(post("/api/v1/workflows/workflow-1/runs/stream")
                        .requestAttr(AuthInterceptor.USER_ATTRIBUTE,
                                new AuthenticatedUser("user-1", "user", "普通用户", "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"question\":\"hello\"}}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rendersOrderedServerSentEventsWithFullRunAsTerminalEvent() throws Exception {
        WorkflowService service = mock(WorkflowService.class);
        WorkflowRun run = WorkflowRun.builder().id("run-1").workflowId("workflow-1")
                .status("completed").startedAt(Instant.now()).completedAt(Instant.now()).build();
        when(service.streamRun(eq("workflow-1"), any(RunWorkflowRequest.class))).thenReturn(Flux.just(
                new WorkflowService.RunEvent("run-start", Map.of("runId", "run-1", "totalNodes", 1)),
                new WorkflowService.RunEvent("node-start", Map.of("nodeId", "start", "index", 0)),
                new WorkflowService.RunEvent("node-complete", Map.of("nodeId", "start", "status", "completed")),
                new WorkflowService.RunEvent("complete", run)));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new WorkflowController(service)).build();

        MvcResult pending = mvc.perform(post("/api/v1/workflows/workflow-1/runs/stream")
                        .requestAttr(AuthInterceptor.USER_ATTRIBUTE,
                                new AuthenticatedUser("admin-1", "admin", "管理员", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{}"))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Cache-Control", "no-cache, no-transform"))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();

        MvcResult response = mvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();
        String body = response.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body.indexOf("event:run-start")).isLessThan(body.indexOf("event:node-start"));
        assertThat(body.indexOf("event:node-start")).isLessThan(body.indexOf("event:node-complete"));
        assertThat(body.indexOf("event:node-complete")).isLessThan(body.indexOf("event:complete"));
        assertThat(body).contains("\"mode\":\"execution\"")
                .contains("\"status\":\"completed\"");
    }
}
