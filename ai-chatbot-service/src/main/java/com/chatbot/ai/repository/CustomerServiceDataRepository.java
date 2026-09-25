package com.chatbot.ai.repository;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class CustomerServiceDataRepository {
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_customer_profile (
                    id VARCHAR(64) PRIMARY KEY,
                    customer_no VARCHAR(40) NOT NULL UNIQUE,
                    display_name VARCHAR(80) NOT NULL,
                    member_level VARCHAR(30) NOT NULL,
                    phone_masked VARCHAR(30),
                    account_status VARCHAR(30) NOT NULL,
                    entitlements VARCHAR(500),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_service_order (
                    id VARCHAR(64) PRIMARY KEY,
                    order_no VARCHAR(50) NOT NULL UNIQUE,
                    customer_no VARCHAR(40) NOT NULL,
                    channel VARCHAR(40) NOT NULL,
                    product_name VARCHAR(200) NOT NULL,
                    amount DECIMAL(12,2) NOT NULL,
                    order_status VARCHAR(40) NOT NULL,
                    logistics_status VARCHAR(80),
                    paid_at TIMESTAMP NULL,
                    delivered_at TIMESTAMP NULL,
                    INDEX idx_service_order_customer (customer_no)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_business_subject (
                    id VARCHAR(64) PRIMARY KEY,
                    subject_no VARCHAR(40) NOT NULL UNIQUE,
                    subject_type VARCHAR(30) NOT NULL,
                    display_name VARCHAR(120) NOT NULL,
                    service_tier VARCHAR(40) NOT NULL,
                    subject_status VARCHAR(40) NOT NULL,
                    service_context VARCHAR(500)
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ai_service_ticket (
                    id VARCHAR(64) PRIMARY KEY,
                    ticket_no VARCHAR(50) NOT NULL UNIQUE,
                    customer_no VARCHAR(40) NOT NULL,
                    order_no VARCHAR(50),
                    category VARCHAR(50) NOT NULL,
                    priority VARCHAR(20) NOT NULL,
                    summary VARCHAR(500) NOT NULL,
                    status VARCHAR(30) NOT NULL,
                    owner_team VARCHAR(80) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    INDEX idx_service_ticket_customer (customer_no),
                    INDEX idx_service_ticket_order (order_no)
                )
                """);
        ensureTicketActionColumns();
        seedIfEmpty();
    }

    public Optional<CustomerView> findCustomer(String customerNo) {
        return jdbcTemplate.query("SELECT * FROM ai_customer_profile WHERE customer_no=?",
                (rs, row) -> new CustomerView(rs.getString("customer_no"), rs.getString("display_name"),
                        rs.getString("member_level"), rs.getString("phone_masked"),
                        rs.getString("account_status"), rs.getString("entitlements")), customerNo).stream().findFirst();
    }

    public Optional<OrderView> findOrder(String orderNo) {
        return jdbcTemplate.query("SELECT * FROM ai_service_order WHERE order_no=?",
                (rs, row) -> new OrderView(rs.getString("order_no"), rs.getString("customer_no"),
                        rs.getString("channel"), rs.getString("product_name"), rs.getBigDecimal("amount"),
                        rs.getString("order_status"), rs.getString("logistics_status"),
                        toInstant(rs.getTimestamp("paid_at")), toInstant(rs.getTimestamp("delivered_at"))), orderNo)
                .stream().findFirst();
    }

    public List<OrderView> findOrdersByCustomer(String customerNo) {
        return jdbcTemplate.query("""
                SELECT * FROM ai_service_order WHERE customer_no=?
                ORDER BY paid_at DESC, order_no DESC LIMIT 20
                """, (rs, row) -> new OrderView(rs.getString("order_no"), rs.getString("customer_no"),
                rs.getString("channel"), rs.getString("product_name"), rs.getBigDecimal("amount"),
                rs.getString("order_status"), rs.getString("logistics_status"),
                toInstant(rs.getTimestamp("paid_at")), toInstant(rs.getTimestamp("delivered_at"))), customerNo);
    }

    public Optional<BusinessSubjectView> findBusinessSubject(String subjectNo) {
        return jdbcTemplate.query("SELECT * FROM ai_business_subject WHERE subject_no=?",
                (rs, row) -> new BusinessSubjectView(rs.getString("subject_no"), rs.getString("subject_type"),
                        rs.getString("display_name"), rs.getString("service_tier"),
                        rs.getString("subject_status"), rs.getString("service_context")), subjectNo)
                .stream().findFirst();
    }

    public List<TicketView> findTickets(String customerNo, String orderNo) {
        String sql = orderNo == null || orderNo.isBlank()
                ? "SELECT * FROM ai_service_ticket WHERE customer_no=? ORDER BY updated_at DESC LIMIT 20"
                : "SELECT * FROM ai_service_ticket WHERE customer_no=? AND order_no=? ORDER BY updated_at DESC LIMIT 20";
        Object[] args = orderNo == null || orderNo.isBlank() ? new Object[]{customerNo} : new Object[]{customerNo, orderNo};
        return jdbcTemplate.query(sql, (rs, row) -> new TicketView(rs.getString("ticket_no"),
                rs.getString("customer_no"), rs.getString("order_no"), rs.getString("category"),
                rs.getString("priority"), rs.getString("summary"), rs.getString("status"),
                rs.getString("owner_team"), rs.getTimestamp("updated_at").toInstant()), args);
    }

    public CreatedTicket createTicketForAction(String actionId, String actorUserId,
                                               String customerNo, String orderNo,
                                               String category, String priority, String summary) {
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("正式工单必须绑定确认动作");
        }
        String ticketId = UUID.randomUUID().toString();
        String ticketNo = "TK" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 20).toUpperCase(Locale.ROOT);
        Instant now = Instant.now();
        String ownerTeam = switch (category) {
            case "退款", "退货", "换货" -> "售后服务组";
            case "物流" -> "履约协同组";
            case "账户", "权限" -> "账号安全组";
            default -> "综合服务组";
        };
        jdbcTemplate.update("""
                INSERT INTO ai_service_ticket(
                    id,ticket_no,customer_no,order_no,category,priority,summary,status,owner_team,
                    action_id,created_by_user_id,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, ticketId, ticketNo, customerNo, blankToNull(orderNo), category,
                priority, summary, "待处理", ownerTeam, actionId, actorUserId,
                Timestamp.from(now), Timestamp.from(now));
        TicketView view = new TicketView(ticketNo, customerNo, blankToNull(orderNo), category,
                priority, summary, "待处理", ownerTeam, now);
        return new CreatedTicket(ticketId, view);
    }

    public Optional<TicketView> findTicketByActionId(String actionId) {
        return jdbcTemplate.query("SELECT * FROM ai_service_ticket WHERE action_id=?",
                (rs, row) -> new TicketView(rs.getString("ticket_no"), rs.getString("customer_no"),
                        rs.getString("order_no"), rs.getString("category"), rs.getString("priority"),
                        rs.getString("summary"), rs.getString("status"), rs.getString("owner_team"),
                        rs.getTimestamp("updated_at").toInstant()), actionId).stream().findFirst();
    }

    private void ensureTicketActionColumns() {
        if (!columnExists("ai_service_ticket", "action_id")) {
            jdbcTemplate.execute("ALTER TABLE ai_service_ticket ADD COLUMN action_id VARCHAR(64) NULL");
        }
        if (!columnExists("ai_service_ticket", "created_by_user_id")) {
            jdbcTemplate.execute("ALTER TABLE ai_service_ticket ADD COLUMN created_by_user_id VARCHAR(64) NULL");
        }
        if (!indexExists("ai_service_ticket", "uk_service_ticket_action_id")) {
            jdbcTemplate.execute("CREATE UNIQUE INDEX uk_service_ticket_action_id ON ai_service_ticket(action_id)");
        }
    }

    private boolean columnExists(String tableName, String columnName) {
        Boolean exists = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            try (var columns = connection.getMetaData().getColumns(connection.getCatalog(), null, null, null)) {
                while (columns.next()) {
                    if (tableName.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                            && columnName.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
                }
            }
            return false;
        });
        return Boolean.TRUE.equals(exists);
    }

    private boolean indexExists(String tableName, String indexName) {
        Boolean exists = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            try (var indexes = connection.getMetaData().getIndexInfo(
                    connection.getCatalog(), null, tableName, false, false)) {
                while (indexes.next()) {
                    if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
                }
            }
            try (var indexes = connection.getMetaData().getIndexInfo(
                    connection.getCatalog(), null, tableName.toUpperCase(Locale.ROOT), false, false)) {
                while (indexes.next()) {
                    if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
                }
            }
            return false;
        });
        return Boolean.TRUE.equals(exists);
    }

    private void seedIfEmpty() {
        Integer customers = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_customer_profile", Integer.class);
        if (customers != null && customers == 0) {
            jdbcTemplate.update("INSERT INTO ai_customer_profile(id,customer_no,display_name,member_level,phone_masked,account_status,entitlements) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), "CUST-10001", "周先生", "企业专业版", "138****2468", "正常", "7×24 小时技术支持；专属客户成功经理；每月 2 次远程培训");
            jdbcTemplate.update("INSERT INTO ai_customer_profile(id,customer_no,display_name,member_level,phone_masked,account_status,entitlements) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), "CUST-10002", "林女士", "金牌会员", "186****5310", "正常", "优先客服；极速退款；退换货上门取件");
        }
        Integer orders = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_service_order", Integer.class);
        if (orders != null && orders == 0) {
            jdbcTemplate.update("INSERT INTO ai_service_order(id,order_no,customer_no,channel,product_name,amount,order_status,logistics_status,paid_at,delivered_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), "ORD-20260918-001", "CUST-10002", "品牌商城", "智能会议终端 Pro", new BigDecimal("3299.00"), "已完成", "已签收", Timestamp.from(Instant.parse("2026-09-18T03:20:00Z")), Timestamp.from(Instant.parse("2026-09-20T08:35:00Z")));
            jdbcTemplate.update("INSERT INTO ai_service_order(id,order_no,customer_no,channel,product_name,amount,order_status,logistics_status,paid_at,delivered_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), "ORD-20260920-008", "CUST-10001", "企业采购", "协作云年度订阅（100 席位）", new BigDecimal("48000.00"), "服务中", "数字化交付完成", Timestamp.from(Instant.parse("2026-09-20T02:10:00Z")), null);
        }
        Integer subjects = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_business_subject", Integer.class);
        if (subjects != null && subjects == 0) {
            jdbcTemplate.update("INSERT INTO ai_business_subject(id,subject_no,subject_type,display_name,service_tier,subject_status,service_context) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), "TENANT-2001", "企业租户", "星海科技", "企业专业版", "服务正常", "协作云 100 席位；SSO 已开通；客户成功团队负责");
            jdbcTemplate.update("INSERT INTO ai_business_subject(id,subject_no,subject_type,display_name,service_tier,subject_status,service_context) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), "EMP-3108", "内部员工", "产品运营成员", "标准 IT 服务", "在职", "Windows 设备；标准办公软件；无管理员权限");
            jdbcTemplate.update("INSERT INTO ai_business_subject(id,subject_no,subject_type,display_name,service_tier,subject_status,service_context) VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), "MER-8802", "平台商家", "山野生活旗舰店", "重点商家", "经营正常", "家居类目；结算周期 T+7；当前无风控限制");
        }
        Integer tickets = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_service_ticket", Integer.class);
        if (tickets != null && tickets == 0) {
            Instant now = Instant.now().minusSeconds(7200);
            jdbcTemplate.update("INSERT INTO ai_service_ticket(id,ticket_no,customer_no,order_no,category,priority,summary,status,owner_team,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), "TK20260922001", "CUST-10001", "ORD-20260920-008", "权限", "高", "新成员无法加入企业工作区", "处理中", "账号安全组", Timestamp.from(now), Timestamp.from(now));
            jdbcTemplate.update("INSERT INTO ai_service_ticket(id,ticket_no,customer_no,order_no,category,priority,summary,status,owner_team,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), "TK20260922002", "MER-8802", null, "其他", "中", "结算明细与订单汇总不一致", "待补充材料", "商家运营组", Timestamp.from(now), Timestamp.from(now));
        }
    }

    private Instant toInstant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    public record CustomerView(String customerNo, String displayName, String memberLevel,
                               String phoneMasked, String accountStatus, String entitlements) { }
    public record OrderView(String orderNo, String customerNo, String channel, String productName,
                            BigDecimal amount, String orderStatus, String logisticsStatus,
                            Instant paidAt, Instant deliveredAt) { }
    public record BusinessSubjectView(String subjectNo, String subjectType, String displayName,
                                      String serviceTier, String subjectStatus, String serviceContext) { }
    public record TicketView(String ticketNo, String customerNo, String orderNo, String category,
                             String priority, String summary, String status, String ownerTeam, Instant updatedAt) { }
    public record CreatedTicket(String ticketId, TicketView view) { }
}
