package com.chatbot.ai.tools;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.repository.CustomerServiceDataRepository;
import com.chatbot.ai.service.BusinessAuthorizationService;
import com.chatbot.ai.service.PendingActionService;
import com.chatbot.ai.service.ToolTraceRecorder;
import com.chatbot.ai.service.TrustedToolContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomerServiceToolsFailureTest {
    private static final AuthenticatedUser ACTOR =
            new AuthenticatedUser("operator-1", "operator", "客服", "USER");

    @Test
    void orderToolFailureIsRecordedAsFailedAndDoesNotLeaveTraceSessionOpen() {
        CustomerServiceDataRepository repository = mock(CustomerServiceDataRepository.class);
        BusinessAuthorizationService authorization = mock(BusinessAuthorizationService.class);
        PendingActionService pendingActionService = mock(PendingActionService.class);
        ToolTraceRecorder recorder = new ToolTraceRecorder();
        CustomerServiceTools tools = new CustomerServiceTools(
                repository, recorder, authorization, pendingActionService);

        when(authorization.requireOrderAccess(any(AuthenticatedUser.class), eq("ORD-FAIL-001")))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "订单数据源暂不可用"));

        String traceId = "tool-failure-trace";
        recorder.begin(traceId);
        Map<String, Object> values = new LinkedHashMap<>(TrustedToolContext.values(
                ACTOR, "conversation-1", null, "request-1", "commerce-support", false));
        values.put(ToolTraceRecorder.TRACE_ID_CONTEXT_KEY, traceId);
        ToolContext context = new ToolContext(Map.copyOf(values));

        assertThatThrownBy(() -> tools.queryOrder("ORD-FAIL-001", context))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("订单数据源暂不可用");

        assertThat(recorder.finish(traceId))
                .singleElement()
                .satisfies(trace -> {
                    assertThat(trace.getPhase()).isEqualTo("tool");
                    assertThat(trace.getTitle()).isEqualTo("查询订单履约");
                    assertThat(trace.getStatus()).isEqualTo("failed");
                    assertThat(trace.getDetail()).doesNotContain("订单数据源暂不可用");
                });
        assertThat(recorder.finish(traceId)).isEmpty();
    }
}
