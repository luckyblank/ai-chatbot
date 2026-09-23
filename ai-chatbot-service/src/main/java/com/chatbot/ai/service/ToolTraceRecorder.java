package com.chatbot.ai.service;

import com.chatbot.ai.domain.chat.ChatTraceStep;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class ToolTraceRecorder {
    /**
     * Passed through Spring AI's tool context so traces remain associated with the
     * correct request even when a streamed model response changes threads.
     */
    public static final String TRACE_ID_CONTEXT_KEY = "customerServiceTraceId";

    private final ConcurrentMap<String, List<ChatTraceStep>> traceSessions = new ConcurrentHashMap<>();
    private final ThreadLocal<String> currentTraceId = new ThreadLocal<>();

    public String begin() {
        String traceId = UUID.randomUUID().toString();
        begin(traceId);
        currentTraceId.set(traceId);
        return traceId;
    }

    public void begin(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            throw new IllegalArgumentException("追踪标识不能为空");
        }
        traceSessions.put(traceId, Collections.synchronizedList(new ArrayList<>()));
    }

    public void record(String title, String detail, String status, long durationMs) {
        record(currentTraceId.get(), title, detail, status, durationMs);
    }

    public void record(ToolContext toolContext, String title, String detail, String status, long durationMs) {
        record(resolveTraceId(toolContext), title, detail, status, durationMs);
    }

    public void record(String traceId, String title, String detail, String status, long durationMs) {
        List<ChatTraceStep> current = traceId == null ? null : traceSessions.get(traceId);
        if (current == null) {
            return;
        }
        current.add(ChatTraceStep.builder()
                .phase("tool")
                .title(title)
                .detail(detail)
                .status(status)
                .durationMs(durationMs)
                .build());
    }

    public List<ChatTraceStep> finish() {
        return finish(currentTraceId.get());
    }

    public List<ChatTraceStep> finish(String traceId) {
        if (traceId == null) {
            return List.of();
        }
        List<ChatTraceStep> current = traceSessions.remove(traceId);
        if (traceId.equals(currentTraceId.get())) {
            currentTraceId.remove();
        }
        if (current == null) {
            return List.of();
        }
        synchronized (current) {
            return List.copyOf(current);
        }
    }

    public void discard(String traceId) {
        finish(traceId);
    }

    private String resolveTraceId(ToolContext toolContext) {
        if (toolContext != null) {
            Map<String, Object> context = toolContext.getContext();
            if (context != null) {
                Object value = context.get(TRACE_ID_CONTEXT_KEY);
                if (value != null && !value.toString().isBlank()) {
                    return value.toString();
                }
            }
        }
        return currentTraceId.get();
    }
}
