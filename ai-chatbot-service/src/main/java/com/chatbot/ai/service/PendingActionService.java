package com.chatbot.ai.service;

import com.chatbot.ai.domain.action.PendingAction;
import com.chatbot.ai.domain.action.PendingActionParameters;
import com.chatbot.ai.domain.action.PendingActionStatus;
import com.chatbot.ai.domain.action.PendingActionView;
import com.chatbot.ai.domain.auth.AuthenticatedUser;
import com.chatbot.ai.repository.CustomerServiceDataRepository;
import com.chatbot.ai.repository.PendingActionRepository;
import com.chatbot.ai.repository.ScenarioRepository;
import com.chatbot.ai.repository.ConversationRepository;
import com.chatbot.ai.domain.chat.ConversationSession;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class PendingActionService {
    public static final String CREATE_SERVICE_TICKET = "CREATE_SERVICE_TICKET";
    private static final Set<String> CATEGORIES = Set.of("退款", "退货", "换货", "物流", "账户", "权限", "其他");
    private static final Set<String> PRIORITIES = Set.of("低", "中", "高", "紧急");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(密码|口令|验证码|password|passwd|passcode|otp|api[_ -]?key|access[_ -]?token|secret)"
                    + "\\s*[:：=是]?\\s*[A-Za-z0-9/+_.-]{4,}");
    private static final Pattern FULL_ID_NUMBER = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern LONG_PAYMENT_NUMBER = Pattern.compile("(?<!\\d)(?:\\d[ -]?){13,19}(?!\\d)");
    private static final Pattern JWT_OR_PROVIDER_TOKEN = Pattern.compile(
            "(?i)(?:sk-[A-Za-z0-9_-]{12,}|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})");
    private static final TicketCreationFaultInjector NO_FAULT = (action, ticket) -> { };

    private final PendingActionRepository repository;
    private final CustomerServiceDataRepository businessData;
    private final BusinessAuthorizationService authorization;
    private final ScenarioRepository scenarios;
    private final ScenarioToolPolicy scenarioToolPolicy;
    private final ConversationRepository conversations;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<TicketCreationFaultInjector> faultInjectors;
    private final PendingActionFailureRecorder failureRecorder;

    @Value("${app.pending-action.expiry-minutes:15}")
    private long expiryMinutes;

    @Transactional
    public PendingActionView prepareServiceTicket(TrustedToolContext context,
                                                  String customerNo,
                                                  String orderNo,
                                                  String category,
                                                  String priority,
                                                  String summary) {
        if (context == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少可信工具上下文");
        context.requireActionRequest();
        if (context.conversationId() == null || context.conversationId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "工作流审批不授予写权限；本轮仅允许已认证会话准备工单草案");
        }
        requireConversationOwnership(context.actor(), context.conversationId(), context.scenarioCode());
        requireScenarioPermission(context.scenarioCode());
        PendingActionParameters parameters = normalize(customerNo, orderNo, category, priority, summary);
        authorization.requireTicketTargetAccess(context.actor(), parameters.customerNo(), parameters.orderNo());

        String stableRequestKey = stableRequestKey(context, CREATE_SERVICE_TICKET);
        String fingerprint = fingerprint(parameters);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(Math.max(1, expiryMinutes)));
        String actionId = UUID.nameUUIDFromBytes(("pending-action:" + stableRequestKey)
                .getBytes(StandardCharsets.UTF_8)).toString();
        PendingAction candidate = PendingAction.builder()
                .actionId(actionId)
                .stableRequestKey(stableRequestKey)
                .actorUserId(context.actor().id())
                .conversationId(context.conversationId())
                .runId(context.runId())
                .scenarioCode(context.scenarioCode())
                .actionType(CREATE_SERVICE_TICKET)
                .parameters(parameters)
                .parameterFingerprint(fingerprint)
                .version(1)
                .status(PendingActionStatus.PENDING)
                .expiresAt(expiresAt)
                .createdAt(now)
                .updatedAt(now)
                .build();
        repository.insertIfAbsent(candidate);

        PendingAction current = repository.findByStableRequestKeyForUpdate(stableRequestKey)
                .orElseThrow(() -> new IllegalStateException("工单草案初始化失败"));
        requireActorOwnership(current, context.actor());
        if (repository.countAudits(current.getActionId(), "PREPARED") == 0) {
            repository.appendAudit(current.getActionId(), "PREPARED", context.actor().id(),
                    current.getVersion(), null, PendingActionStatus.PENDING,
                    detail("parameterFingerprint", current.getParameterFingerprint()), now);
        }

        if (current.getStatus() == PendingActionStatus.PENDING
                && !current.getExpiresAt().isAfter(now)) {
            repository.markExpired(current.getActionId(), current.getVersion(), now);
            repository.appendAudit(current.getActionId(), "EXPIRED", context.actor().id(),
                    current.getVersion(), PendingActionStatus.PENDING, PendingActionStatus.EXPIRED,
                    detail("reason", "draft_expired_before_prepare_retry"), now);
            return view(reload(current.getActionId()));
        }

        if (fingerprint.equals(current.getParameterFingerprint())) {
            return view(current);
        }
        if (current.getStatus() != PendingActionStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该业务请求已经结束；如需再次建单，请发起新的、可见的客服请求");
        }
        long nextVersion = current.getVersion() + 1;
        int updated = repository.updateDraft(current.getActionId(), current.getVersion(), parameters,
                fingerprint, nextVersion, expiresAt, context.scenarioCode(), now);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "工单草案已被更新，请刷新后重试");
        repository.appendAudit(current.getActionId(), "DRAFT_REVISED", context.actor().id(),
                nextVersion, PendingActionStatus.PENDING, PendingActionStatus.PENDING,
                revisionDetail(current.getParameters(), current.getParameterFingerprint(),
                        parameters, fingerprint), now);
        return view(reload(current.getActionId()));
    }

    @Transactional
    public PendingActionView get(AuthenticatedUser actor, String actionId) {
        PendingAction action = repository.findByIdForUpdate(actionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "待确认动作不存在"));
        requireActorOwnership(action, actor);
        requireConversationOwnership(actor, action.getConversationId(), action.getScenarioCode());
        expireIfNecessary(action, actor, Instant.now());
        return view(reload(actionId));
    }

    @Transactional
    public List<PendingActionView> list(AuthenticatedUser actor, String conversationId) {
        requireActor(actor);
        if (conversationId == null || conversationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conversationId 不能为空");
        }
        requireConversationOwnership(actor, conversationId.trim(), null);
        List<PendingAction> actions = repository.findByActorAndConversation(actor.id(), conversationId.trim());
        Instant now = Instant.now();
        for (PendingAction action : actions) expireIfNecessary(action, actor, now);
        return repository.findByActorAndConversation(actor.id(), conversationId.trim()).stream()
                .map(this::view).toList();
    }

    @Transactional
    public PendingActionView confirm(AuthenticatedUser actor, String actionId, long expectedVersion) {
        PendingAction action = repository.findByIdForUpdate(actionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "待确认动作不存在"));
        requireActorOwnership(action, actor);
        requireConversationOwnership(actor, action.getConversationId(), action.getScenarioCode());
        requireVersion(action, expectedVersion);
        Instant now = Instant.now();
        if (action.getStatus() == PendingActionStatus.SUCCEEDED) return view(action);
        if (action.getStatus() == PendingActionStatus.CANCELLED
                || action.getStatus() == PendingActionStatus.EXPIRED) return view(action);
        if (!action.getExpiresAt().isAfter(now)) {
            expireIfNecessary(action, actor, now);
            return view(reload(actionId));
        }

        requireScenarioPermission(action.getScenarioCode());
        PendingActionParameters parameters = normalize(action.getParameters().customerNo(),
                action.getParameters().orderNo(), action.getParameters().category(),
                action.getParameters().priority(), action.getParameters().summary());
        if (!fingerprint(parameters).equals(action.getParameterFingerprint())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工单草案参数校验失败");
        }
        authorization.requireTicketTargetAccess(actor, parameters.customerNo(), parameters.orderNo());

        try {
            CustomerServiceDataRepository.CreatedTicket created = businessData.createTicketForAction(
                    action.getActionId(), actor.id(), parameters.customerNo(), parameters.orderNo(),
                    parameters.category(), parameters.priority(), parameters.summary());
            faultInjectors.orderedStream().findFirst().orElse(NO_FAULT).afterTicketInserted(action, created);
            String resultJson = writeResult(created.view());
            int updated = repository.markSucceeded(action.getActionId(), expectedVersion,
                    created.ticketId(), created.view().ticketNo(), resultJson, now);
            if (updated != 1) throw new IllegalStateException("确认动作状态更新失败");
            repository.appendAudit(action.getActionId(), "CONFIRMED", actor.id(), expectedVersion,
                    PendingActionStatus.PENDING, PendingActionStatus.SUCCEEDED,
                    detail("ticketNo", created.view().ticketNo()), now);
            return view(reload(actionId));
        } catch (RuntimeException exception) {
            recordFailureAfterRollback(action.getActionId(), actor.id(), expectedVersion);
            throw exception;
        }
    }

    @Transactional
    public PendingActionView cancel(AuthenticatedUser actor, String actionId, long expectedVersion) {
        PendingAction action = repository.findByIdForUpdate(actionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "待确认动作不存在"));
        requireActorOwnership(action, actor);
        requireConversationOwnership(actor, action.getConversationId(), action.getScenarioCode());
        requireVersion(action, expectedVersion);
        if (action.getStatus() == PendingActionStatus.CANCELLED) return view(action);
        if (action.getStatus() != PendingActionStatus.PENDING) return view(action);
        Instant now = Instant.now();
        if (!action.getExpiresAt().isAfter(now)) {
            expireIfNecessary(action, actor, now);
            return view(reload(actionId));
        }
        int updated = repository.markCancelled(actionId, expectedVersion, now);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "工单草案状态已变化，请刷新后重试");
        repository.appendAudit(actionId, "CANCELLED", actor.id(), expectedVersion,
                PendingActionStatus.PENDING, PendingActionStatus.CANCELLED, null, now);
        return view(reload(actionId));
    }

    private void expireIfNecessary(PendingAction action, AuthenticatedUser actor, Instant now) {
        if (action.getStatus() != PendingActionStatus.PENDING || action.getExpiresAt().isAfter(now)) return;
        int updated = repository.markExpired(action.getActionId(), action.getVersion(), now);
        if (updated == 1) {
            repository.appendAudit(action.getActionId(), "EXPIRED", actor.id(), action.getVersion(),
                    PendingActionStatus.PENDING, PendingActionStatus.EXPIRED, null, now);
        }
    }

    private void requireScenarioPermission(String scenarioCode) {
        var scenario = scenarios.findByCode(scenarioCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "业务场景已停用或不存在"));
        if (!scenarioToolPolicy.isAllowed(scenario, "prepareServiceTicket")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前业务场景不允许准备或确认工单");
        }
    }

    private PendingActionParameters normalize(String customerNo, String orderNo, String category,
                                              String priority, String summary) {
        String normalizedCustomer = identifier(customerNo, "客户或业务主体编号");
        String normalizedOrder = orderNo == null || orderNo.isBlank() ? null : identifier(orderNo, "订单号");
        String normalizedCategory = category == null ? "" : category.trim();
        String normalizedPriority = priority == null ? "" : priority.trim();
        String normalizedSummary = summary == null ? "" : summary.trim().replaceAll("\\s+", " ");
        if (!CATEGORIES.contains(normalizedCategory)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工单分类必须是退款、退货、换货、物流、账户、权限或其他");
        }
        if (!PRIORITIES.contains(normalizedPriority)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "工单优先级必须是低、中、高或紧急");
        }
        if (normalizedSummary.isBlank() || normalizedSummary.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "问题摘要需为 1—200 个字符");
        }
        if (containsSensitiveValue(normalizedSummary)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "问题摘要不能包含密码、验证码、完整证件号、完整银行卡号、访问令牌或密钥");
        }
        return new PendingActionParameters(normalizedCustomer, normalizedOrder,
                normalizedCategory, normalizedPriority, normalizedSummary);
    }

    private boolean containsSensitiveValue(String summary) {
        return SECRET_ASSIGNMENT.matcher(summary).find()
                || FULL_ID_NUMBER.matcher(summary).find()
                || LONG_PAYMENT_NUMBER.matcher(summary).find()
                || JWT_OR_PROVIDER_TOKEN.matcher(summary).find();
    }

    private String identifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > 80 || !normalized.matches("[A-Za-z0-9-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "格式无效");
        }
        return normalized;
    }

    private String stableRequestKey(TrustedToolContext context, String actionType) {
        String owner = context.conversationId() != null ? "conversation:" + context.conversationId()
                : "run:" + context.runId();
        return sha256(context.actor().id() + "\u0000" + owner + "\u0000"
                + context.requestId() + "\u0000" + actionType);
    }

    private String fingerprint(PendingActionParameters parameters) {
        try {
            return sha256(objectMapper.writeValueAsString(parameters));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("工单草案参数无法序列化", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private void requireActorOwnership(PendingAction action, AuthenticatedUser actor) {
        requireActor(actor);
        if (!actor.id().equals(action.getActorUserId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "待确认动作不存在");
        }
    }

    private void requireActor(AuthenticatedUser actor) {
        if (actor == null || actor.id() == null || actor.id().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录状态无效");
        }
    }

    private void requireConversationOwnership(AuthenticatedUser actor,
                                              String conversationId,
                                              String expectedScenarioCode) {
        requireActor(actor);
        if (conversationId == null || conversationId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工单草案未绑定可验证的会话");
        }
        ConversationSession conversation = conversations.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "关联会话已不存在，不能准备或执行工单"));
        boolean legacyAdmin = (conversation.getOwnerId() == null || conversation.getOwnerId().isBlank())
                && "ADMIN".equals(actor.role());
        boolean owner = actor.id().equals(conversation.getOwnerId());
        boolean admin = "ADMIN".equals(actor.role());
        if (!(legacyAdmin || owner || admin)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "待确认动作不存在");
        }
        if (expectedScenarioCode != null && conversation.getScenarioCode() != null
                && !expectedScenarioCode.equals(conversation.getScenarioCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "会话业务场景已变化，请重新准备工单草案");
        }
    }

    private void requireVersion(PendingAction action, long expectedVersion) {
        if (expectedVersion < 1 || action.getVersion() != expectedVersion) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工单草案版本已变化，请刷新后重新确认");
        }
    }

    private PendingAction reload(String actionId) {
        return repository.findById(actionId)
                .orElseThrow(() -> new IllegalStateException("待确认动作状态丢失"));
    }

    private PendingActionView view(PendingAction action) {
        return new PendingActionView(action.getActionId(), action.getConversationId(), action.getRunId(),
                action.getActionType(), action.getParameters(), action.getParameterFingerprint(),
                action.getVersion(), action.getStatus(), action.getExpiresAt(), readResult(action.getResultJson()),
                action.getLastError(), action.getCreatedAt(), action.getUpdatedAt(),
                action.getConfirmedAt(), action.getCancelledAt());
    }

    private String writeResult(CustomerServiceDataRepository.TicketView result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("工单结果无法序列化", exception);
        }
    }

    private CustomerServiceDataRepository.TicketView readResult(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, CustomerServiceDataRepository.TicketView.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的工单结果无法解析", exception);
        }
    }

    private String detail(String key, String value) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(key, value == null ? "" : value));
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private String revisionDetail(PendingActionParameters previous, String previousFingerprint,
                                  PendingActionParameters current, String currentFingerprint) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "previousParameters", previous,
                    "previousFingerprint", previousFingerprint,
                    "currentParameters", current,
                    "currentFingerprint", currentFingerprint));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("工单草案修订审计无法序列化", exception);
        }
    }

    private void recordFailureAfterRollback(String actionId, String actorUserId, long version) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            failureRecorder.record(actionId, actorUserId, version);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    failureRecorder.record(actionId, actorUserId, version);
                }
            }
        });
    }
}
