package com.chatbot.ai.service;

import com.chatbot.ai.domain.action.PendingActionStatus;
import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.repository.CustomerServiceDataRepository;
import com.chatbot.ai.repository.PendingActionRepository;
import com.chatbot.ai.repository.ConversationRepository;
import com.chatbot.ai.domain.chat.ConversationSession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real MySQL verification. It is intentionally opt-in and refuses URLs whose
 * schema name does not visibly contain "test".
 *
 * MYSQL_IT_ENABLED=true
 * MYSQL_IT_URL=jdbc:mysql://localhost:3306/ai_chatbot_action_test?...
 * MYSQL_IT_USERNAME=...
 * MYSQL_IT_PASSWORD=...
 */
@SpringBootTest(properties = {
        "app.ai.enabled=false",
        "app.storage.root=target/pending-action-mysql-it-runtime",
        "app.authorization.admin-all-business-scope=true"
})
@Import(PendingActionMySqlTest.FaultConfiguration.class)
@EnabledIfEnvironmentVariable(named = "MYSQL_IT_ENABLED", matches = "(?i)true")
class PendingActionMySqlTest {
    private static final AuthenticatedUser ADMIN =
            new AuthenticatedUser("mysql-it-admin", "mysql-it", "MySQL 测试操作员", "ADMIN");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        String url = System.getenv("MYSQL_IT_URL");
        if (url == null || !url.matches("(?i).*[/_:.-][^/?]*test[^/?]*(?:\\?.*)?$")) {
            throw new IllegalStateException("MYSQL_IT_URL 必须显式指向名称含 test 的隔离 schema");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> required("MYSQL_IT_USERNAME"));
        registry.add("spring.datasource.password", () -> required("MYSQL_IT_PASSWORD"));
    }

    @BeforeAll
    static void requireEnabledFlag() {
        assertThat(System.getenv("MYSQL_IT_ENABLED")).isEqualToIgnoringCase("true");
    }

    @Autowired PendingActionService service;
    @Autowired CustomerServiceDataRepository businessData;
    @Autowired PendingActionRepository actions;
    @Autowired ConversationRepository conversations;
    @Autowired OneShotFaultInjector faultInjector;

    @Test
    void concurrentAndRepeatedConfirmReturnTheSameSingleTicket() throws Exception {
        var draft = prepare(UUID.randomUUID().toString(), "并发确认只创建一张工单");
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> { start.await(); return service.confirm(ADMIN, draft.actionId(), draft.version()); });
            var second = executor.submit(() -> { start.await(); return service.confirm(ADMIN, draft.actionId(), draft.version()); });
            start.countDown();
            var firstResult = first.get(20, TimeUnit.SECONDS);
            var secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResult.status()).isEqualTo(PendingActionStatus.SUCCEEDED);
            assertThat(secondResult.result().ticketNo()).isEqualTo(firstResult.result().ticketNo());
        } finally {
            executor.shutdownNow();
        }

        var responseLossRetry = service.confirm(ADMIN, draft.actionId(), draft.version());
        assertThat(responseLossRetry.result()).isNotNull();
        assertThat(businessData.findTicketByActionId(draft.actionId()))
                .get().extracting(CustomerServiceDataRepository.TicketView::ticketNo)
                .isEqualTo(responseLossRetry.result().ticketNo());
        assertThat(actions.countAudits(draft.actionId(), "CONFIRMED")).isEqualTo(1);
    }

    @Test
    void failureAfterTicketInsertRollsBackAndRetrySucceeds() {
        var draft = prepare(UUID.randomUUID().toString(), "验证插入后的事务回滚");
        faultInjector.failNext();

        assertThatThrownBy(() -> service.confirm(ADMIN, draft.actionId(), draft.version()))
                .hasMessageContaining("mysql-it-injected-after-insert");
        assertThat(businessData.findTicketByActionId(draft.actionId())).isEmpty();
        var retryable = service.get(ADMIN, draft.actionId());
        assertThat(retryable.status()).isEqualTo(PendingActionStatus.PENDING);
        assertThat(retryable.lastError()).contains("安全重试");
        assertThat(actions.countAudits(draft.actionId(), "CONFIRMED")).isZero();
        assertThat(actions.countAudits(draft.actionId(), "EXECUTION_FAILED")).isEqualTo(1);

        var retried = service.confirm(ADMIN, draft.actionId(), draft.version());
        assertThat(retried.status()).isEqualTo(PendingActionStatus.SUCCEEDED);
        assertThat(retried.lastError()).isNull();
        assertThat(businessData.findTicketByActionId(draft.actionId())).isPresent();
    }

    private com.chatbot.ai.domain.action.PendingActionView prepare(String requestId, String summary) {
        String conversationId = "mysql-it-conversation-" + requestId;
        Instant now = Instant.now();
        conversations.save(ConversationSession.builder().id(conversationId).ownerId(ADMIN.id())
                .title("MySQL PendingAction IT").scenarioCode("commerce-support")
                .createdAt(now).updatedAt(now).build());
        TrustedToolContext context = new TrustedToolContext(ADMIN,
                conversationId, null, requestId,
                "commerce-support", true);
        return service.prepareServiceTicket(context, "CUST-10002", "ORD-20260918-001",
                "其他", "中", summary);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " 未配置");
        return value;
    }

    @TestConfiguration
    static class FaultConfiguration {
        @Bean
        OneShotFaultInjector mysqlItFaultInjector() {
            return new OneShotFaultInjector();
        }
    }

    static class OneShotFaultInjector implements TicketCreationFaultInjector {
        private final AtomicBoolean fail = new AtomicBoolean(false);

        void failNext() {
            fail.set(true);
        }

        @Override
        public void afterTicketInserted(com.chatbot.ai.domain.action.PendingAction action,
                                        CustomerServiceDataRepository.CreatedTicket ticket) {
            if (fail.compareAndSet(true, false)) {
                throw new IllegalStateException("mysql-it-injected-after-insert");
            }
        }
    }
}
