package com.chatbot.ai.tools;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.repository.BusinessScopeRepository;
import com.chatbot.ai.repository.CustomerServiceDataRepository;
import com.chatbot.ai.service.BusinessAuthorizationService;
import com.chatbot.ai.service.PendingActionService;
import com.chatbot.ai.service.ToolTraceRecorder;
import com.chatbot.ai.service.TrustedToolContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomerOrderLookupTest {
    private static final AuthenticatedUser ACTOR =
            new AuthenticatedUser("operator-1", "operator", "客服", "USER");

    @Test
    void customerNumberCanLeadToAnAuthorizedOrderAndManualReview() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:customer-order-lookup;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        CustomerServiceDataRepository repository = new CustomerServiceDataRepository(new JdbcTemplate(dataSource));
        repository.initialize();
        BusinessScopeRepository scopes = mock(BusinessScopeRepository.class);
        when(scopes.hasScope(ACTOR.id(), "CUST-10002")).thenReturn(true);
        BusinessAuthorizationService authorization = new BusinessAuthorizationService(scopes, repository);
        ReflectionTestUtils.setField(authorization, "adminAllBusinessScope", false);
        CustomerServiceTools tools = new CustomerServiceTools(repository, new ToolTraceRecorder(),
                authorization, mock(PendingActionService.class));
        ToolContext context = new ToolContext(TrustedToolContext.values(
                ACTOR, "conversation-1", null, "request-1", "commerce-support", true));

        CustomerServiceDataRepository.CustomerOrdersView lookup = tools.queryCustomerOrders("CUST-10002", context);
        assertThat(lookup.hasMore()).isFalse();
        assertThat(lookup.orders()).extracting(CustomerServiceDataRepository.OrderView::orderNo)
                .containsExactly("ORD-20260918-001");
        assertThat(tools.queryOrder(lookup.orders().get(0).orderNo(), context).customerNo())
                .isEqualTo("CUST-10002");
        assertThat(tools.checkAfterSalesEligibility(lookup.orders().get(0).orderNo(), "退货", context)
                .requiresManualReview()).isTrue();

        assertThatThrownBy(() -> tools.queryCustomerOrders("CUST-10001", context))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> tools.queryCustomerOrders(ACTOR.id(), context))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void largeCustomerOrderHistoryIsBoundedAndMarkedIncomplete() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:customer-order-limit;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        CustomerServiceDataRepository repository = new CustomerServiceDataRepository(jdbc);
        repository.initialize();
        for (int index = 0; index < 21; index++) {
            jdbc.update("""
                    INSERT INTO ai_service_order(id,order_no,customer_no,channel,product_name,amount,
                    order_status,logistics_status,paid_at) VALUES(?,?,?,?,?,?,?,?,?)
                    """, "extra-" + index, "ORD-EXTRA-" + index, "CUST-10002", "web", "商品", 1,
                    "已完成", "已签收", java.sql.Timestamp.from(
                            java.time.Instant.parse("2026-09-25T00:00:00Z").plusSeconds(index)));
        }

        CustomerServiceDataRepository.CustomerOrdersView result = repository.findOrdersByCustomer("CUST-10002");
        assertThat(result.orders()).hasSize(20);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.orders().get(0).orderNo()).isEqualTo("ORD-EXTRA-20");
    }
}
