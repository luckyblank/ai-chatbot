package com.chatbot.ai.tools;

import com.chatbot.ai.repository.CustomerServiceDataRepository;
import com.chatbot.ai.domain.action.PendingActionView;
import com.chatbot.ai.service.BusinessAuthorizationService;
import com.chatbot.ai.service.PendingActionService;
import com.chatbot.ai.service.ToolTraceRecorder;
import com.chatbot.ai.service.TrustedToolContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class CustomerServiceTools {
    private static final Set<String> AFTER_SALES_ACTIONS = Set.of("退款", "退货", "换货");
    private final CustomerServiceDataRepository repository;
    private final ToolTraceRecorder traceRecorder;
    private final BusinessAuthorizationService authorization;
    private final PendingActionService pendingActionService;

    @Tool(description = "按客户编号查询已脱敏的客户身份、会员/订阅等级、账号状态与服务权益。不得猜测客户编号。")
    public CustomerServiceDataRepository.CustomerView queryCustomerEntitlements(
            @ToolParam(description = "客户编号，例如 CUST-10001") String customerNo,
            ToolContext toolContext) {
        long started = System.nanoTime();
        try {
            TrustedToolContext trusted = TrustedToolContext.require(toolContext);
            authorization.requireBusinessSubjectAccess(trusted.actor(), customerNo);
            var result = repository.findCustomer(customerNo).orElse(null);
            traceRecorder.record(toolContext, "查询客户权益", "客户编号=" + safe(customerNo) + "；" + (result == null ? "未找到客户" : "返回已脱敏客户资料与服务权益"), "completed", elapsed(started));
            return result;
        } catch (RuntimeException exception) {
            traceRecorder.record(toolContext, "查询客户权益", "查询失败，详细原因已写入服务日志", "failed", elapsed(started)); throw exception;
        }
    }

    @Tool(description = "按订单号查询真实订单的渠道、商品/服务、金额、订单状态与履约状态。订单号须由用户提供，或来自本轮已授权的客户订单查询结果，不得猜测。")
    public CustomerServiceDataRepository.OrderView queryOrder(
            @ToolParam(description = "订单号，例如 ORD-20260918-001") String orderNo,
            ToolContext toolContext) {
        long started = System.nanoTime();
        try {
            TrustedToolContext trusted = TrustedToolContext.require(toolContext);
            var result = authorization.requireOrderAccess(trusted.actor(), orderNo);
            traceRecorder.record(toolContext, "查询订单履约", "订单号=" + safe(orderNo) + "；" + (result == null ? "未找到订单" : "已返回订单与履约状态"), "completed", elapsed(started));
            return result;
        } catch (RuntimeException exception) {
            traceRecorder.record(toolContext, "查询订单履约", "查询失败，详细原因已写入服务日志", "failed", elapsed(started)); throw exception;
        }
    }

    @Tool(description = "按客户编号查询该客户最近最多 20 笔订单，返回订单号、商品、状态和履约信息。仅使用用户提供的客户编号；客服操作员 userId 不是客户编号。")
    public List<CustomerServiceDataRepository.OrderView> queryCustomerOrders(
            @ToolParam(description = "客户编号，例如 CUST-10002") String customerNo,
            ToolContext toolContext) {
        long started = System.nanoTime();
        try {
            TrustedToolContext trusted = TrustedToolContext.require(toolContext);
            authorization.requireBusinessSubjectAccess(trusted.actor(), customerNo);
            String normalizedCustomerNo = customerNo.trim();
            if (repository.findCustomer(normalizedCustomerNo).isEmpty()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "客户不存在");
            }
            var result = repository.findOrdersByCustomer(normalizedCustomerNo);
            traceRecorder.record(toolContext, "查询客户订单", "客户编号=" + safe(normalizedCustomerNo) + "；返回 " + result.size() + " 笔订单", "completed", elapsed(started));
            return result;
        } catch (RuntimeException exception) {
            traceRecorder.record(toolContext, "查询客户订单", "查询失败，详细原因已写入服务日志", "failed", elapsed(started));
            throw exception;
        }
    }

    @Tool(description = "按主体编号查询企业租户、内部员工或平台商家的已脱敏服务档案。支持 TENANT、EMP、MER 编号，不得跨主体猜测或批量查询。")
    public CustomerServiceDataRepository.BusinessSubjectView queryBusinessSubject(
            @ToolParam(description = "业务主体编号，例如 TENANT-2001、EMP-3108 或 MER-8802") String subjectNo,
            ToolContext toolContext) {
        long started = System.nanoTime();
        TrustedToolContext trusted = TrustedToolContext.require(toolContext);
        authorization.requireBusinessSubjectAccess(trusted.actor(), subjectNo);
        var result = repository.findBusinessSubject(subjectNo).orElse(null);
        traceRecorder.record(toolContext, "查询业务主体", "主体编号=" + safe(subjectNo) + "；" + (result == null ? "未找到主体" : "返回类型、服务等级与当前状态"), "completed", elapsed(started));
        return result;
    }

    @Tool(description = "检查订单是否满足退款、退货或换货的基础资格。结果仅用于客服初筛，不能替代最终审核。")
    public EligibilityResult checkAfterSalesEligibility(
            @ToolParam(description = "订单号") String orderNo,
            @ToolParam(description = "售后动作：退款、退货或换货") String requestedAction,
            ToolContext toolContext) {
        long started = System.nanoTime();
        TrustedToolContext trusted = TrustedToolContext.require(toolContext);
        var order = authorization.requireOrderAccess(trusted.actor(), orderNo);
        String normalizedAction = requestedAction == null ? "" : requestedAction.trim();
        if (!AFTER_SALES_ACTIONS.contains(normalizedAction)) {
            throw new IllegalArgumentException("售后动作必须是退款、退货或换货");
        }
        EligibilityResult result;
        if (!trusted.policyEvidenceAvailable()) {
            result = new EligibilityResult(false,
                    "当前没有足够的政策证据，不能自动认定售后资格，需要补充政策资料或转人工复核", true);
        } else {
            // Retrieval presence alone cannot prove that a particular policy clause
            // applies to this order. Until evidence is bound to a claim-level rule,
            // keep the decision conservative and require a human review.
            result = new EligibilityResult(false,
                    "已检索到相关资料，但尚未完成具体条款与本订单的逐项核验，需要人工复核", true);
        }
        traceRecorder.record(toolContext, "校验售后资格", "订单号=" + safe(orderNo) + "；诉求=" + requestedAction + "；结论=" + result.reason(), "completed", elapsed(started));
        return result;
    }

    @Tool(description = "查询客户已有服务工单，可使用订单号缩小范围。返回工单状态与负责团队，不包含内部处理备注。")
    public List<CustomerServiceDataRepository.TicketView> queryServiceTickets(
            @ToolParam(description = "客户编号") String customerNo,
            @ToolParam(required = false, description = "订单号，可选") String orderNo,
            ToolContext toolContext) {
        long started = System.nanoTime();
        TrustedToolContext trusted = TrustedToolContext.require(toolContext);
        authorization.requireBusinessSubjectAccess(trusted.actor(), customerNo);
        if (orderNo != null && !orderNo.isBlank()) {
            var order = authorization.requireOrderAccess(trusted.actor(), orderNo);
            if (!customerNo.equals(order.customerNo())) throw new IllegalArgumentException("订单不属于所选客户");
        }
        var result = repository.findTickets(customerNo, orderNo);
        traceRecorder.record(toolContext, "查询服务工单", "客户编号=" + safe(customerNo) + "；返回 " + result.size() + " 条工单", "completed", elapsed(started));
        return result;
    }

    @Tool(description = "准备企业客服工单草案，供界面展示并等待已认证用户确认。本工具绝不创建正式工单；不得声称草案已经执行。")
    public PendingActionView prepareServiceTicket(
            @ToolParam(description = "客户编号") String customerNo,
            @ToolParam(required = false, description = "关联订单号，可选") String orderNo,
            @ToolParam(description = "问题分类：退款、退货、换货、物流、账户、权限或其他") String category,
            @ToolParam(description = "优先级：低、中、高、紧急") String priority,
            @ToolParam(description = "不超过 200 字的问题摘要，不得包含密码、完整证件号或完整银行卡号") String summary,
            ToolContext toolContext) {
        long started = System.nanoTime();
        TrustedToolContext trusted = TrustedToolContext.require(toolContext);
        PendingActionView result = pendingActionService.prepareServiceTicket(
                trusted, customerNo, orderNo, category, priority, summary);
        traceRecorder.record(toolContext, "准备服务工单草案",
                "动作=" + result.actionId() + "；版本=" + result.version() + "；状态=" + result.status(),
                "completed", elapsed(started));
        return result;
    }

    private String safe(String value) { return value == null ? "未提供" : value.replaceAll("[^A-Za-z0-9-]", ""); }
    private long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
    public record EligibilityResult(boolean eligible, String reason, boolean requiresManualReview) { }
}
