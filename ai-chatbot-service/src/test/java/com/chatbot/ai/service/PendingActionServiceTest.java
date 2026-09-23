package com.chatbot.ai.service;

import com.chatbot.ai.domain.action.PendingActionStatus;
import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.repository.CustomerServiceDataRepository;
import com.chatbot.ai.repository.BusinessScopeRepository;
import com.chatbot.ai.repository.ConversationRepository;
import com.chatbot.ai.domain.chat.ConversationSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** H2 verifies deterministic control flow only; MySQL semantics are tested separately. */
@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "app.storage.root=target/pending-action-test-runtime",
        "spring.datasource.url=jdbc:h2:mem:pending-action-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "app.authorization.admin-all-business-scope=true"
})
@TestPropertySource(properties = "app.pending-action.expiry-minutes=15")
class PendingActionServiceTest {
    private static final AuthenticatedUser ADMIN =
            new AuthenticatedUser("pending-action-admin", "admin", "管理员", "ADMIN");

    @Autowired
    PendingActionService service;
    @Autowired
    CustomerServiceDataRepository businessData;
    @Autowired
    BusinessScopeRepository scopes;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    ConversationRepository conversations;

    @Test
    void repeatedPrepareAndConfirmReuseOneActionAndOneTicket() {
        TrustedToolContext context = context(UUID.randomUUID().toString());
        var first = service.prepareServiceTicket(context, "CUST-10002", "ORD-20260918-001",
                "退货", "中", "会议终端申请退货");
        var repeated = service.prepareServiceTicket(context, "CUST-10002", "ORD-20260918-001",
                "退货", "中", "会议终端申请退货");

        assertThat(repeated.actionId()).isEqualTo(first.actionId());
        assertThat(repeated.version()).isEqualTo(first.version());
        assertThat(businessData.findTicketByActionId(first.actionId())).isEmpty();

        var confirmed = service.confirm(ADMIN, first.actionId(), first.version());
        var retry = service.confirm(ADMIN, first.actionId(), first.version());
        assertThat(confirmed.status()).isEqualTo(PendingActionStatus.SUCCEEDED);
        assertThat(retry.result().ticketNo()).isEqualTo(confirmed.result().ticketNo());
        assertThat(businessData.findTicketByActionId(first.actionId()))
                .get().extracting(CustomerServiceDataRepository.TicketView::ticketNo)
                .isEqualTo(confirmed.result().ticketNo());
    }

    @Test
    void changedParametersKeepActionIdButInvalidateOldVersion() {
        TrustedToolContext context = context(UUID.randomUUID().toString());
        var first = service.prepareServiceTicket(context, "CUST-10002", "ORD-20260918-001",
                "物流", "低", "查询物流状态");
        var revised = service.prepareServiceTicket(context, "CUST-10002", "ORD-20260918-001",
                "物流", "高", "物流长时间未更新");

        assertThat(revised.actionId()).isEqualTo(first.actionId());
        assertThat(revised.version()).isEqualTo(first.version() + 1);
        assertThatThrownBy(() -> service.confirm(ADMIN, first.actionId(), first.version()))
                .hasMessageContaining("版本已变化");
        assertThat(businessData.findTicketByActionId(first.actionId())).isEmpty();
        String revision = jdbcTemplate.queryForObject("""
                SELECT detail_json FROM ai_pending_action_audit
                WHERE action_id=? AND event_type='DRAFT_REVISED'
                """, String.class, first.actionId());
        assertThat(revision).contains("查询物流状态").contains("物流长时间未更新");
    }

    @Test
    void cancelAndDifferentActorBlockConfirmation() {
        var draft = service.prepareServiceTicket(context(UUID.randomUUID().toString()),
                "CUST-10001", "ORD-20260920-008", "权限", "中", "成员无法加入工作区");
        AuthenticatedUser other = new AuthenticatedUser("other-user", "other", "其他客服", "ADMIN");
        assertThatThrownBy(() -> service.confirm(other, draft.actionId(), draft.version()))
                .hasMessageContaining("待确认动作不存在");

        var cancelled = service.cancel(ADMIN, draft.actionId(), draft.version());
        var rejected = service.confirm(ADMIN, draft.actionId(), draft.version());
        assertThat(cancelled.status()).isEqualTo(PendingActionStatus.CANCELLED);
        assertThat(rejected.status()).isEqualTo(PendingActionStatus.CANCELLED);
        assertThat(businessData.findTicketByActionId(draft.actionId())).isEmpty();
    }

    @Test
    void expiredDraftAndOutOfScopeTargetAreRejected() {
        var draft = service.prepareServiceTicket(context(UUID.randomUUID().toString()),
                "CUST-10002", "ORD-20260918-001", "退货", "中", "过期测试草案");
        jdbcTemplate.update("UPDATE ai_pending_action SET expires_at=? WHERE action_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), draft.actionId());
        var expired = service.confirm(ADMIN, draft.actionId(), draft.version());
        assertThat(expired.status()).isEqualTo(PendingActionStatus.EXPIRED);
        assertThat(businessData.findTicketByActionId(draft.actionId())).isEmpty();

        AuthenticatedUser scopedOperator =
                new AuthenticatedUser("operator-cust-1", "operator", "客服一号", "USER");
        scopes.grant(scopedOperator.id(), "CUST-10001");
        TrustedToolContext unauthorizedContext = context(scopedOperator, UUID.randomUUID().toString());
        assertThatThrownBy(() -> service.prepareServiceTicket(unauthorizedContext,
                "CUST-10002", "ORD-20260918-001", "其他", "低", "越权草案"))
                .hasMessageContaining("无权访问");
    }

    @Test
    void orderMustBelongToTheDraftCustomer() {
        assertThatThrownBy(() -> service.prepareServiceTicket(context(UUID.randomUUID().toString()),
                "CUST-10001", "ORD-20260918-001", "其他", "中", "错误关联订单"))
                .hasMessageContaining("订单不属于所选客户");
    }

    @Test
    void sensitiveValuesAreRejectedBeforeDraftPersistence() {
        TrustedToolContext context = context(UUID.randomUUID().toString());

        assertThatThrownBy(() -> service.prepareServiceTicket(context,
                "CUST-10002", "ORD-20260918-001", "其他", "中",
                "银行卡号 6222020202020202020，密码 abc123"))
                .hasMessageContaining("不能包含密码");
    }

    private TrustedToolContext context(String requestId) {
        return context(ADMIN, requestId);
    }

    private TrustedToolContext context(AuthenticatedUser actor, String requestId) {
        String conversationId = "conversation-" + requestId;
        Instant now = Instant.now();
        conversations.save(ConversationSession.builder().id(conversationId).ownerId(actor.id())
                .title("PendingAction 测试").scenarioCode("commerce-support")
                .createdAt(now).updatedAt(now).build());
        return new TrustedToolContext(actor, conversationId, null,
                requestId, "commerce-support", true);
    }
}
