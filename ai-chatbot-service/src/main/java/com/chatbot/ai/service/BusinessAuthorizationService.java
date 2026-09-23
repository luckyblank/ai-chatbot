package com.chatbot.ai.service;

import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.repository.BusinessScopeRepository;
import com.chatbot.ai.repository.CustomerServiceDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class BusinessAuthorizationService {
    private final BusinessScopeRepository scopeRepository;
    private final CustomerServiceDataRepository businessData;

    @Value("${app.authorization.admin-all-business-scope:true}")
    private boolean adminAllBusinessScope;

    public void requireBusinessSubjectAccess(AuthenticatedUser actor, String subjectNo) {
        requireActor(actor);
        if (subjectNo == null || subjectNo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "业务主体编号不能为空");
        }
        if (isExplicitAdminAll(actor) || scopeRepository.hasScope(actor.id(), subjectNo.trim())) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前操作员无权访问该业务主体");
    }

    public CustomerServiceDataRepository.OrderView requireOrderAccess(AuthenticatedUser actor,
                                                                       String orderNo) {
        requireActor(actor);
        if (orderNo == null || orderNo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单号不能为空");
        }
        CustomerServiceDataRepository.OrderView order = businessData.findOrder(orderNo.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        requireBusinessSubjectAccess(actor, order.customerNo());
        return order;
    }

    public void requireTicketTargetAccess(AuthenticatedUser actor, String customerNo, String orderNo) {
        requireActor(actor);
        String normalizedCustomerNo = customerNo == null ? "" : customerNo.trim();
        if (normalizedCustomerNo.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户或业务主体编号不能为空");
        }
        boolean targetExists = businessData.findCustomer(normalizedCustomerNo).isPresent()
                || businessData.findBusinessSubject(normalizedCustomerNo).isPresent();
        if (!targetExists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "客户或业务主体不存在");
        }
        requireBusinessSubjectAccess(actor, normalizedCustomerNo);
        if (orderNo != null && !orderNo.isBlank()) {
            CustomerServiceDataRepository.OrderView order = requireOrderAccess(actor, orderNo);
            if (!normalizedCustomerNo.equals(order.customerNo())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单不属于所选客户");
            }
        }
    }

    public boolean isExplicitAdminAll(AuthenticatedUser actor) {
        return actor != null && adminAllBusinessScope && "ADMIN".equals(actor.role());
    }

    private void requireActor(AuthenticatedUser actor) {
        if (actor == null || actor.id() == null || actor.id().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录状态无效");
        }
    }
}
