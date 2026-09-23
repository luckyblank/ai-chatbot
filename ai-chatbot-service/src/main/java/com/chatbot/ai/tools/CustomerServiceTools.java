package com.chatbot.ai.tools;

import com.chatbot.ai.repository.CustomerServiceDataRepository;
import com.chatbot.ai.service.ToolTraceRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CustomerServiceTools {
    private final CustomerServiceDataRepository repository;
    private final ToolTraceRecorder traceRecorder;

    @Tool(description = "按客户编号查询已脱敏的客户身份、会员/订阅等级、账号状态与服务权益。不得猜测客户编号。")
    public CustomerServiceDataRepository.CustomerView queryCustomerEntitlements(
            @ToolParam(description = "客户编号，例如 CUST-10001") String customerNo,
            ToolContext toolContext) {
        long started = System.nanoTime();
        try {
            var result = repository.findCustomer(customerNo).orElse(null);
            traceRecorder.record(toolContext, "查询客户权益", "客户编号=" + safe(customerNo) + "；" + (result == null ? "未找到客户" : "返回已脱敏客户资料与服务权益"), "completed", elapsed(started));
            return result;
        } catch (RuntimeException exception) {
            traceRecorder.record(toolContext, "查询客户权益", "查询失败，详细原因已写入服务日志", "failed", elapsed(started)); throw exception;
        }
    }

    @Tool(description = "按订单号查询真实订单的渠道、商品/服务、金额、订单状态与履约状态。只有用户提供了订单号时才可调用。")
    public CustomerServiceDataRepository.OrderView queryOrder(
            @ToolParam(description = "订单号，例如 ORD-20260918-001") String orderNo,
            ToolContext toolContext) {
        long started = System.nanoTime();
        try {
            var result = repository.findOrder(orderNo).orElse(null);
            traceRecorder.record(toolContext, "查询订单履约", "订单号=" + safe(orderNo) + "；" + (result == null ? "未找到订单" : "已返回订单与履约状态"), "completed", elapsed(started));
            return result;
        } catch (RuntimeException exception) {
            traceRecorder.record(toolContext, "查询订单履约", "查询失败，详细原因已写入服务日志", "failed", elapsed(started)); throw exception;
        }
    }

    @Tool(description = "按主体编号查询企业租户、内部员工或平台商家的已脱敏服务档案。支持 TENANT、EMP、MER 编号，不得跨主体猜测或批量查询。")
    public CustomerServiceDataRepository.BusinessSubjectView queryBusinessSubject(
            @ToolParam(description = "业务主体编号，例如 TENANT-2001、EMP-3108 或 MER-8802") String subjectNo,
            ToolContext toolContext) {
        long started = System.nanoTime();
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
        var order = repository.findOrder(orderNo).orElse(null);
        EligibilityResult result;
        if (order == null) result = new EligibilityResult(false, "未找到订单，无法校验", true);
        else if ("服务中".equals(order.orderStatus()) && "退货".equals(requestedAction)) result = new EligibilityResult(false, "数字化服务不适用实物退货流程，需转合同变更审核", true);
        else if (order.deliveredAt() != null && Duration.between(order.deliveredAt(), Instant.now()).toDays() <= 7) result = new EligibilityResult(true, "签收未超过 7 天，可进入售后材料核验", false);
        else result = new EligibilityResult(false, "超出自动受理范围，需要人工复核合同、商品状态或例外政策", true);
        traceRecorder.record(toolContext, "校验售后资格", "订单号=" + safe(orderNo) + "；诉求=" + requestedAction + "；结论=" + result.reason(), "completed", elapsed(started));
        return result;
    }

    @Tool(description = "查询客户已有服务工单，可使用订单号缩小范围。返回工单状态与负责团队，不包含内部处理备注。")
    public List<CustomerServiceDataRepository.TicketView> queryServiceTickets(
            @ToolParam(description = "客户编号") String customerNo,
            @ToolParam(required = false, description = "订单号，可选") String orderNo,
            ToolContext toolContext) {
        long started = System.nanoTime();
        var result = repository.findTickets(customerNo, orderNo);
        traceRecorder.record(toolContext, "查询服务工单", "客户编号=" + safe(customerNo) + "；返回 " + result.size() + " 条工单", "completed", elapsed(started));
        return result;
    }

    @Tool(description = "创建企业客服工单。只有用户已经明确确认提交，且客户编号、问题摘要完整时才可调用。不得代替用户确认。")
    public CustomerServiceDataRepository.TicketView createServiceTicket(
            @ToolParam(description = "客户编号") String customerNo,
            @ToolParam(required = false, description = "关联订单号，可选") String orderNo,
            @ToolParam(description = "问题分类：退款、退货、换货、物流、账户、权限或其他") String category,
            @ToolParam(description = "优先级：低、中、高、紧急") String priority,
            @ToolParam(description = "不超过 200 字的问题摘要，不得包含密码、完整证件号或完整银行卡号") String summary,
            @ToolParam(description = "用户明确确认提交时传入 CONFIRMED") String confirmation,
            ToolContext toolContext) {
        long started = System.nanoTime();
        if (!"CONFIRMED".equals(confirmation)) {
            traceRecorder.record(toolContext, "创建服务工单", "未获得用户明确确认，已阻止写入", "skipped", elapsed(started));
            throw new IllegalArgumentException("创建工单前必须获得用户明确确认");
        }
        if (repository.findCustomer(customerNo).isEmpty() && repository.findBusinessSubject(customerNo).isEmpty()) {
            throw new IllegalArgumentException("客户或业务主体编号不存在");
        }
        String safeSummary = summary == null ? "" : summary.trim();
        if (safeSummary.isBlank() || safeSummary.length() > 200) throw new IllegalArgumentException("问题摘要需为 1—200 个字符");
        var result = repository.createTicket(customerNo, orderNo, category, priority, safeSummary);
        traceRecorder.record(toolContext, "创建服务工单", "工单号=" + result.ticketNo() + "；分类=" + category + "；负责团队=" + result.ownerTeam(), "completed", elapsed(started));
        return result;
    }

    private String safe(String value) { return value == null ? "未提供" : value.replaceAll("[^A-Za-z0-9-]", ""); }
    private long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
    public record EligibilityResult(boolean eligible, String reason, boolean requiresManualReview) { }
}
