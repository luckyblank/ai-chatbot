package com.chatbot.ai.service;

import com.chatbot.ai.domain.chat.ChatCitation;
import com.chatbot.ai.domain.chat.ChatTraceStep;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeChatServiceStreamingCompatibilityTest {

    @Test
    void citationExcerptKeepsTheFullRetrievedChunk() {
        String chunk = "文档标题\n" + "售后处理依据。".repeat(40);
        assertThat(chunk.length()).isGreaterThan(180);
        assertThat(KnowledgeChatService.excerpt(chunk)).isEqualTo(chunk);
    }

    @Test
    void routesEveryToolEnabledTurnThroughCompleteToolCallPath() {
        assertThat(KnowledgeChatService.requiresBufferedToolCall(
                List.of(org.mockito.Mockito.mock(FunctionCallback.class)))).isTrue();
        assertThat(KnowledgeChatService.requiresBufferedToolCall(List.of())).isFalse();
        assertThat(KnowledgeChatService.requiresBufferedToolCall(null)).isFalse();
        assertThat(KnowledgeChatService.timeoutSeconds(
                List.of(org.mockito.Mockito.mock(FunctionCallback.class)), 45, 120)).isEqualTo(120);
        assertThat(KnowledgeChatService.timeoutSeconds(List.of(), 45, 120)).isEqualTo(45);
    }

    @Test
    void timeoutCancelsTheRunningModelTask() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);

        assertThatThrownBy(() -> KnowledgeChatService.awaitModelCall(() -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException exception) {
                interrupted.countDown();
                throw exception;
            }
            return "late answer";
        }, 1)).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
        assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void interruptingTheWaitingRequestCancelsTheRunningModelTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread request = new Thread(() -> {
            try {
                KnowledgeChatService.awaitModelCall(() -> {
                    started.countDown();
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                        throw exception;
                    }
                    return "late answer";
                }, 30);
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        request.start();
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            request.interrupt();
            request.join(2_000);
            assertThat(request.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOfSatisfying(ResponseStatusException.class,
                    exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            request.interrupt();
        }
    }

    @Test
    void splitsBufferedAnswerOnNaturalBoundariesWithoutChangingContent() {
        String answer = "## 订单结果\n\n订单已完成，当前履约状态为已签收。"
                + "如需申请售后，请先确认商品状态；客服会继续协助。\n"
                + "- 订单号：ORD-20260918-001\n- 状态：已签收";

        List<String> chunks = KnowledgeChatService.splitAnswer(answer);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(String.join("", chunks)).isEqualTo(answer);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk).isNotEmpty();
            assertThat(chunk.length()).isLessThanOrEqualTo(48);
        });
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk).endsWith("。"));
        assertThat(chunks).anySatisfy(chunk -> assertThat(chunk).endsWith("\n"));
    }

    @Test
    void bufferedEventsEmitDeltasThenOneCompleteWithOriginalCitationsAndTraces() {
        ChatCitation citation = ChatCitation.builder()
                .documentId("doc-1")
                .fileName("售后政策.md")
                .excerpt("七日无理由")
                .build();
        ChatTraceStep trace = ChatTraceStep.builder()
                .phase("tool")
                .title("查询订单履约")
                .detail("订单号=ORD-20260918-001；已返回订单与履约状态")
                .status("completed")
                .durationMs(8L)
                .build();
        KnowledgeChatService.AnswerResult result = new KnowledgeChatService.AnswerResult(
                "订单已完成，当前履约状态为已签收。如需售后，我可以继续协助。",
                List.of(citation), List.of(trace));

        List<KnowledgeChatService.AnswerStreamEvent> events = KnowledgeChatService
                .bufferedAnswerEvents(result, Duration.ZERO)
                .collectList()
                .block();

        assertThat(events).isNotNull().hasSizeGreaterThan(1);
        assertThat(events.subList(0, events.size() - 1))
                .allMatch(KnowledgeChatService.AnswerDelta.class::isInstance);
        String streamedAnswer = events.subList(0, events.size() - 1).stream()
                .map(KnowledgeChatService.AnswerDelta.class::cast)
                .map(KnowledgeChatService.AnswerDelta::delta)
                .reduce("", String::concat);
        assertThat(streamedAnswer).isEqualTo(result.answer());

        assertThat(events.get(events.size() - 1))
                .isInstanceOfSatisfying(KnowledgeChatService.AnswerCompleted.class, completed -> {
                    assertThat(completed.result()).isSameAs(result);
                    assertThat(completed.result().citations()).containsExactly(citation);
                    assertThat(completed.result().traces()).containsExactly(trace);
                });
    }
}
