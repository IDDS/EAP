package com.eap.platform.safety;

import com.eap.platform.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class GenseeSafetyService {
    private final SafetySessionRepository sessionRepository;
    private final SafetyEventRepository eventRepository;
    private final SafetyDecisionLogRepository decisionRepository;
    private final SafetyArtifactRepository artifactRepository;
    private final boolean sidecarEnabled;
    private final String sidecarUrl;

    public GenseeSafetyService(
            SafetySessionRepository sessionRepository,
            SafetyEventRepository eventRepository,
            SafetyDecisionLogRepository decisionRepository,
            SafetyArtifactRepository artifactRepository,
            @Value("${eap.safety.gensee.sidecar-enabled:false}") boolean sidecarEnabled,
            @Value("${eap.safety.gensee.sidecar-url:http://localhost:8787}") String sidecarUrl
    ) {
        this.sessionRepository = sessionRepository;
        this.eventRepository = eventRepository;
        this.decisionRepository = decisionRepository;
        this.artifactRepository = artifactRepository;
        this.sidecarEnabled = sidecarEnabled;
        this.sidecarUrl = sidecarUrl;
    }

    @Transactional(readOnly = true)
    public SafetyDtos.SafetySummary summary() {
        List<SafetyDtos.DecisionResponse> recent = decisionRepository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(SafetyDtos::from)
                .toList();
        return new SafetyDtos.SafetySummary(
                sessionRepository.count(),
                eventRepository.count(),
                decisionRepository.countByDecision(SafetyDecision.DENY),
                decisionRepository.countByDecision(SafetyDecision.ASK),
                decisionRepository.countBySeverity(SafetySeverity.CRITICAL),
                recent
        );
    }

    @Transactional(readOnly = true)
    public List<SafetyDtos.SessionResponse> listSessions() {
        return sessionRepository.findAll().stream().map(SafetyDtos::from).toList();
    }

    @Transactional
    public SafetyDtos.SessionResponse createSession(SafetyDtos.SessionRequest request) {
        SafetySession session = sessionRepository.findBySessionKey(request.sessionKey()).orElseGet(SafetySession::new);
        if (session.id != null) {
            requireExactSessionKey(session, request.sessionKey());
        }
        session.sessionKey = request.sessionKey();
        session.rootAgentId = request.rootAgentId();
        session.objective = request.objective();
        session.status = "ACTIVE";
        return SafetyDtos.from(sessionRepository.save(session));
    }

    @Transactional
    public SafetyDtos.SafetyEvaluationResponse evaluate(SafetyDtos.SafetyEventRequest request) {
        return evaluate(request,true);
    }

    /** Runtime tool arguments are inspected in memory and never persisted as safety payloads. */
    @Transactional
    public SafetyDtos.SafetyEvaluationResponse evaluateMetadata(SafetyDtos.SafetyEventRequest request) {
        return evaluate(request,false);
    }

    private SafetyDtos.SafetyEvaluationResponse evaluate(SafetyDtos.SafetyEventRequest request,boolean retainPayload) {
        SafetySession session = requireSession(request.sessionKey());

        SafetyEvent event = new SafetyEvent();
        event.sessionKey = session.sessionKey;
        event.agentId = request.agentId();
        event.parentAgentId = request.parentAgentId();
        event.eventType = request.eventType();
        event.action = request.action();
        event.target = retainPayload ? request.target() : "tool://" + request.action();
        event.payloadSummary = retainPayload ? request.payloadSummary() : "运行实例工具执行检查；参数不保存";
        event.payloadJson = retainPayload ? request.payloadJson() : null;
        event = eventRepository.save(event);

        DecisionResult result = decide(request);
        SafetyDecisionLog log = new SafetyDecisionLog();
        log.eventId = event.id;
        log.sessionKey = event.sessionKey;
        log.decision = result.decision();
        log.severity = result.severity();
        log.reasonCode = result.reasonCode();
        log.reason = result.reason();
        log.controlActions = result.controlActions();
        log.sidecarMode = sidecarEnabled ? "DELEGATE_READY:" + sidecarUrl : "EAP_LOCAL_POLICY";
        log = decisionRepository.save(log);

        return new SafetyDtos.SafetyEvaluationResponse(SafetyDtos.from(event), SafetyDtos.from(log));
    }

    @Transactional(readOnly = true)
    public List<SafetyDtos.EventResponse> listEvents(String sessionKey) {
        return eventRepository.findBySessionKeyOrderByCreatedAtDesc(sessionKey).stream().map(SafetyDtos::from).toList();
    }

    @Transactional
    public SafetyDtos.ArtifactResponse registerArtifact(SafetyDtos.ArtifactRequest request) {
        SafetySession session = requireSession(request.sessionKey());
        SafetyEvent event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> new BusinessException("安全事件不存在"));
        if (!session.sessionKey.equals(event.sessionKey)) {
            throw new BusinessException("安全事件不属于指定会话");
        }
        SafetyArtifact artifact = new SafetyArtifact();
        artifact.sessionKey = session.sessionKey;
        artifact.eventId = event.id;
        artifact.artifactUri = request.artifactUri();
        artifact.artifactType = request.artifactType();
        artifact.contentHash = request.contentHash();
        return SafetyDtos.from(artifactRepository.save(artifact));
    }

    @Transactional(readOnly = true)
    public List<SafetyDtos.ArtifactResponse> listArtifacts(String sessionKey) {
        return artifactRepository.findBySessionKeyOrderByCreatedAtDesc(sessionKey).stream().map(SafetyDtos::from).toList();
    }

    private SafetySession requireSession(String sessionKey) {
        SafetySession session = sessionRepository.findBySessionKey(sessionKey)
                .orElseThrow(() -> new BusinessException("安全会话不存在"));
        requireExactSessionKey(session, sessionKey);
        return session;
    }

    private void requireExactSessionKey(SafetySession session, String sessionKey) {
        // Database collations may equate different keys; never rewrite or bind an alias.
        if (!session.sessionKey.equals(sessionKey)) {
            throw new BusinessException("安全会话标识不匹配，请使用已登记的完整标识");
        }
    }

    private DecisionResult decide(SafetyDtos.SafetyEventRequest request) {
        String action = request.action().toLowerCase(Locale.ROOT);
        String target = request.target().toLowerCase(Locale.ROOT);
        String payload = ((request.payloadSummary() == null ? "" : request.payloadSummary()) + " " + (request.payloadJson() == null ? "" : request.payloadJson())).toLowerCase(Locale.ROOT);

        if (containsAny(target, "prod", "production", "root", "/etc/", "secret", "private_key") || containsAny(payload, "id_card", "bank_card", "password", "private_key", "secret")) {
            return new DecisionResult(SafetyDecision.DENY, SafetySeverity.CRITICAL, "SENSITIVE_OR_PRIVILEGED_ACCESS", "命中敏感数据或特权资源访问规则，按 fail-closed 拦截。", "BLOCK;REQUIRE_SECURITY_REVIEW;WRITE_AUDIT");
        }
        if (request.eventType() == SafetyEventType.TOOL_CALL && containsAny(action, "delete", "drop", "transfer", "payment", "deploy", "shell", "exec")) {
            return new DecisionResult(SafetyDecision.ASK, SafetySeverity.HIGH, "HIGH_RISK_TOOL_CALL", "高风险工具调用需要人工确认或审批。", "PAUSE;REQUEST_APPROVAL;WRITE_AUDIT");
        }
        if (request.eventType() == SafetyEventType.DELEGATION && request.parentAgentId() == null) {
            return new DecisionResult(SafetyDecision.ASK, SafetySeverity.MEDIUM, "UNTRACED_DELEGATION", "多智能体委托缺少父智能体链路，无法完整溯源。", "REQUIRE_PARENT_AGENT;WRITE_AUDIT");
        }
        if (request.eventType() == SafetyEventType.ARTIFACT_WRITE && !containsAny(target, "artifact://", "s3://", "oss://", "file://")) {
            return new DecisionResult(SafetyDecision.ASK, SafetySeverity.MEDIUM, "UNREGISTERED_ARTIFACT_DESTINATION", "制品写入目标未纳入平台注册范围，建议登记后再执行。", "REQUIRE_ARTIFACT_REGISTRATION;WRITE_AUDIT");
        }
        return new DecisionResult(SafetyDecision.ALLOW, SafetySeverity.LOW, "POLICY_PASS", "未命中阻断或审批策略，允许继续执行。", "ALLOW;WRITE_AUDIT");
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private record DecisionResult(SafetyDecision decision, SafetySeverity severity, String reasonCode, String reason, String controlActions) {
    }
}

