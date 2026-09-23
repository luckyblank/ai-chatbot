package com.chatbot.ai.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ToolTraceRecorderTest {

    @Test
    void recordsToolTraceAcrossThreadsUsingToolContext() {
        ToolTraceRecorder recorder = new ToolTraceRecorder();
        recorder.begin("stream-request-1");
        ToolContext context = new ToolContext(Map.of(
                ToolTraceRecorder.TRACE_ID_CONTEXT_KEY, "stream-request-1"));

        CompletableFuture.runAsync(() -> recorder.record(
                context, "查询订单履约", "订单号=ORD-1", "completed", 8L)).join();

        assertThat(recorder.finish("stream-request-1"))
                .singleElement()
                .satisfies(trace -> {
                    assertThat(trace.getPhase()).isEqualTo("tool");
                    assertThat(trace.getTitle()).isEqualTo("查询订单履约");
                    assertThat(trace.getDetail()).isEqualTo("订单号=ORD-1");
                    assertThat(trace.getStatus()).isEqualTo("completed");
                    assertThat(trace.getDurationMs()).isEqualTo(8L);
                });
    }
}
