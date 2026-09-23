package com.chatbot.ai.service;

import com.chatbot.ai.domain.chat.ChatCitation;
import com.chatbot.ai.domain.chat.ChatTraceStep;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChatServiceStreamingCompatibilityTest {

    @Test
    void routesExplicitBusinessIdentifiersThroughBufferedToolCallPath() {
        assertThat(KnowledgeChatService.requiresBufferedToolCall(
                "请查询订单 ORD-20260918-001 的履约状态")).isTrue();
        assertThat(KnowledgeChatService.requiresBufferedToolCall(
                "客户 cust-10001 有哪些服务权益？")).isTrue();
        assertThat(KnowledgeChatService.requiresBufferedToolCall(
                "检查 TENANT-2001、EMP-3108 和 MER-8802")).isTrue();

        assertThat(KnowledgeChatService.requiresBufferedToolCall(
                "请介绍订单查询流程，不要查询真实数据")).isFalse();
        assertThat(KnowledgeChatService.requiresBufferedToolCall(
                "ORDINARY 文本不应被识别为订单编号")).isFalse();
        assertThat(KnowledgeChatService.requiresBufferedToolCall(null)).isFalse();
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
