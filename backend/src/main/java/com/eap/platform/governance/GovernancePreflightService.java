package com.eap.platform.governance;

import com.eap.platform.audit.AuditLog;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.common.TraceContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class GovernancePreflightService {
    private final GovernancePreflightDecisionRepository decisionRepository;
    private final GovernancePolicyRepository policyRepository;
    private final GovernanceDataService dataService;
    private final GovernanceDataEventRepository dataEventRepository;
    private final GovernanceDataProperties properties;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public GovernancePreflightService(
            GovernancePreflightDecisionRepository decisionRepository,
            GovernancePolicyRepository policyRepository,
            GovernanceDataService dataService,
            GovernanceDataEventRepository dataEventRepository,
            GovernanceDataProperties properties,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper
    ) {
        this.decisionRepository = decisionRepository;
        this.policyRepository = policyRepository;
        this.dataService = dataService;
        this.dataEventRepository = dataEventRepository;
        this.properties = properties;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public GovernanceDataDtos.PreflightResponse evaluate(GovernanceDataDtos.PreflightRequest request) {
        String evaluatedFingerprint = fingerprint(request);
        GovernancePreflightDecision existing = decisionRepository.findByDecisionKey(request.decisionKey()).orElse(null);
        if (existing != null) {
            if (blankToNull(existing.getRequestFingerprint()) == null) {
                existing.requestFingerprint = evaluatedFingerprint;
                decisionRepository.save(existing);
                return response(existing);
            }
            if (!sameText(evaluatedFingerprint, existing.getRequestFingerprint())) {
                throw new com.eap.platform.common.BusinessException("同一决策 Key 对应的模型请求内容已变化，请生成新的决策 Key");
            }
            return response(existing);
        }

        List<GovernancePolicy> enabledPolicies = policyRepository.findByEnabledTrue();
        String policyContext = GovernanceApprovalContext.digest(objectMapper, properties, enabledPolicies);
        List<PolicyMatch> matches = enabledPolicies.stream()
                .filter(policy -> scopeMatches(policy, request))
                .map(policy -> matchPolicy(policy, request))
                .filter(PolicyMatch::matched)
                .toList();
        Set<String> reasonCodes = new LinkedHashSet<>();
        matches.forEach(match -> reasonCodes.add(match.reasonCode()));

        GovernanceDataAction action = strongestAction(matches);
        if (properties.isFailClosedOnSecret() && isCompanySecret(request)) {
            action = GovernanceDataAction.BLOCK;
            reasonCodes.add("DEFAULT_SECRET_FAIL_CLOSED");
        }
        if (!properties.isEnforcementEnabled() && action != GovernanceDataAction.ALLOW) {
            action = GovernanceDataAction.WARN;
            reasonCodes.add("ENFORCEMENT_DISABLED_OBSERVE_ONLY");
        }
        if (reasonCodes.isEmpty()) {
            reasonCodes.add("NO_RESTRICTIVE_POLICY_MATCH");
        }

        GovernanceDataDtos.DataEventResponse event = dataService.record(new GovernanceDataDtos.DataEventRequest(
                request.eventKey(), request.agentId(), request.endpointKey(), request.taskId(), request.traceId(),
                request.direction(), request.dataCategory(), request.sensitivity(), action,
                request.internalData(), request.companySecret(), request.tokenCount(), request.payloadBytes(),
                matches.size() == 1 ? matches.getFirst().policy().getId() : null,
                String.join(",", reasonCodes), request.observedAt()
        ));

        GovernancePreflightDecision decision = new GovernancePreflightDecision();
        decision.decisionKey = request.decisionKey();
        decision.dataEventId = event.id();
        decision.action = action;
        decision.gatewayDirective = directive(action);
        decision.requestAllowed = action == GovernanceDataAction.ALLOW || action == GovernanceDataAction.WARN;
        decision.approvalStatus = action == GovernanceDataAction.APPROVAL_REQUIRED
                ? GovernanceApprovalStatus.PENDING : GovernanceApprovalStatus.NOT_REQUIRED;
        decision.matchedPolicyCount = matches.size();
        decision.matchedPoliciesJson = writeJson(matches.stream().map(this::matchedPolicy).toList());
        decision.reasonCodesJson = writeJson(reasonCodes);
        decision.classifier = request.classifier();
        decision.provider = blankToNull(request.provider());
        decision.modelName = blankToNull(request.modelName());
        decision.requestFingerprint = evaluatedFingerprint;
        decision.policyContextDigest = policyContext;
        decision.decidedAt = Instant.now();
        GovernancePreflightDecision saved = decisionRepository.save(decision);
        recordAudit(saved, request);
        return response(saved);
    }

    @Transactional(readOnly = true)
    public com.eap.platform.common.HistoryPage<GovernanceDataDtos.PreflightResponse> page(com.eap.platform.common.DecisionHistoryQuery query) {
        var pageable=query.pageable();
        var spec=query.<GovernancePreflightDecision>specification("approvalStatus",query.enumStatus(GovernanceApprovalStatus.class),"decisionKey","provider","modelName","classifier");
        if(query.getAgentId()!=null || query.getEndpointKey()!=null || query.getEventKey()!=null || query.getTraceId()!=null) spec=spec.and((root,q,cb)->{
            var sub=q.subquery(Long.class);var event=sub.from(GovernanceDataEvent.class);
            var terms=new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            terms.add(cb.equal(event.get("id"),root.get("dataEventId")));
            if(query.getAgentId()!=null) terms.add(cb.equal(event.get("agentId"),query.getAgentId()));
            if(query.getEndpointKey()!=null && !query.getEndpointKey().isBlank()) terms.add(cb.equal(event.get("endpointKey"),query.getEndpointKey()));
            if(query.getEventKey()!=null && !query.getEventKey().isBlank()) terms.add(cb.equal(event.get("eventKey"),query.getEventKey()));
            if(query.getTraceId()!=null && !query.getTraceId().isBlank()) terms.add(cb.equal(event.get("traceId"),query.getTraceId()));
            sub.select(event.get("id")).where(terms.toArray(jakarta.persistence.criteria.Predicate[]::new));
            return cb.exists(sub);
        });
        String context = currentPolicyContext();
        return com.eap.platform.common.HistoryPage.from(decisionRepository.findAll(spec,pageable).map(decision -> response(decision, context)));
    }

    @Transactional(readOnly = true)
    public List<GovernanceDataDtos.PreflightResponse> list(int limit) {
        String context = currentPolicyContext();
        return decisionRepository.findTop500ByOrderByDecidedAtDesc().stream().limit(limit).map(decision -> response(decision, context)).toList();
    }

    @Transactional(readOnly = true, propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public GovernanceDataDtos.PreflightResponse reusableDecision(
            String decisionKey, Long agentId, String endpointKey, String provider, String modelName, String requestFingerprint
    ) {
        GovernancePreflightDecision decision = decisionRepository.findByDecisionKey(decisionKey)
                .orElseThrow(() -> new com.eap.platform.common.BusinessException("治理预检决策不存在或已失效"));
        GovernanceDataEvent event = dataEventRepository.findById(decision.getDataEventId())
                .orElseThrow(() -> new com.eap.platform.common.BusinessException("治理预检关联事件不存在"));
        if (!sameNullable(agentId, event.getAgentId()) || !sameText(endpointKey, event.getEndpointKey())) {
            throw new com.eap.platform.common.BusinessException("审批决策与当前模型调用归属不一致");
        }
        if (decision.getProvider() != null && !sameText(provider, decision.getProvider())) {
            throw new com.eap.platform.common.BusinessException("审批决策与当前模型供应商不一致");
        }
        if (decision.getModelName() != null && !sameText(modelName, decision.getModelName())) {
            throw new com.eap.platform.common.BusinessException("审批决策与当前模型名称不一致");
        }
        if (!sameText(requestFingerprint, decision.getRequestFingerprint())) {
            throw new com.eap.platform.common.BusinessException("审批决策与当前模型请求内容不一致，请重新预检");
        }
        if (decision.getApprovalStatus() != GovernanceApprovalStatus.APPROVED || !decision.isRequestAllowed()) {
            throw new com.eap.platform.common.BusinessException("该决策不是已批准的模型调用凭证");
        }
        requireCurrentApprovalContext(decision);
        return response(decision);
    }

    @Transactional(readOnly = true)
    public GovernanceDataDtos.PreflightResponse get(String decisionKey) {
        return decisionRepository.findByDecisionKey(decisionKey)
                .map(this::response)
                .orElseThrow(() -> new com.eap.platform.common.BusinessException("治理预检决策不存在"));
    }

    @Transactional(readOnly = true)
    public GovernanceDataDtos.EnforcementStatus status() {
        GovernancePreflightDecision lastDecision = decisionRepository.findTopByOrderByDecidedAtDesc().orElse(null);
        return new GovernanceDataDtos.EnforcementStatus(
                properties.isEnforcementEnabled(),
                properties.isFailClosedOnSecret(),
                policyRepository.findByEnabledTrue().size(),
                decisionRepository.count(),
                decisionRepository.countByGatewayDirective(GovernanceGatewayDirective.BLOCK),
                decisionRepository.countByGatewayDirective(GovernanceGatewayDirective.REDACT_AND_RECHECK),
                decisionRepository.countByGatewayDirective(GovernanceGatewayDirective.REQUEST_APPROVAL),
                decisionRepository.countByApprovalStatus(GovernanceApprovalStatus.PENDING),
                lastDecision == null ? null : lastDecision.decidedAt
        );
    }

    @Transactional
    public GovernanceDataDtos.PreflightResponse approve(String decisionKey, GovernanceDataDtos.ApprovalRequest request) {
        GovernancePreflightDecision decision = decisionRepository.findByDecisionKey(decisionKey)
                .orElseThrow(() -> new com.eap.platform.common.BusinessException("治理预检决策不存在"));
        if (decision.getGatewayDirective() != GovernanceGatewayDirective.REQUEST_APPROVAL
                || decision.getApprovalStatus() != GovernanceApprovalStatus.PENDING) {
            throw new com.eap.platform.common.BusinessException("当前决策不处于待审批状态");
        }
        requireCurrentApprovalContext(decision);
        decision.approvalStatus = GovernanceApprovalStatus.APPROVED;
        decision.requestAllowed = true;
        decision.gatewayDirective = GovernanceGatewayDirective.PROCEED_WITH_WARNING;
        decision.approvedBy = normalize(request.approver(), 128);
        decision.approvalReason = normalize(request.reason(), 512);
        decision.approvedAt = Instant.now();
        decision.decidedAt = decision.approvedAt;
        GovernancePreflightDecision saved = decisionRepository.save(decision);
        recordApprovalAudit(saved, request, "APPROVED");
        return response(saved);
    }

    @Transactional
    public GovernanceDataDtos.PreflightResponse reject(String decisionKey, GovernanceDataDtos.ApprovalRequest request) {
        GovernancePreflightDecision decision = decisionRepository.findByDecisionKey(decisionKey)
                .orElseThrow(() -> new com.eap.platform.common.BusinessException("治理预检决策不存在"));
        if (decision.getGatewayDirective() != GovernanceGatewayDirective.REQUEST_APPROVAL
                || decision.getApprovalStatus() != GovernanceApprovalStatus.PENDING) {
            throw new com.eap.platform.common.BusinessException("当前决策不处于待审批状态");
        }
        decision.approvalStatus = GovernanceApprovalStatus.REJECTED;
        decision.requestAllowed = false;
        decision.gatewayDirective = GovernanceGatewayDirective.BLOCK;
        decision.approvedBy = normalize(request.approver(), 128);
        decision.approvalReason = normalize(request.reason(), 512);
        decision.approvedAt = Instant.now();
        decision.decidedAt = decision.approvedAt;
        GovernancePreflightDecision saved = decisionRepository.save(decision);
        recordApprovalAudit(saved, request, "REJECTED");
        return response(saved);
    }

    private PolicyMatch matchPolicy(GovernancePolicy policy, GovernanceDataDtos.PreflightRequest request) {
        try {
            JsonNode rule = readRule(policy.getRuleJson());
            List<Boolean> constraints = new ArrayList<>();
            addTextConstraint(constraints, rule, "sensitivity", request.sensitivity().name());
            addArrayConstraint(constraints, rule, "sensitivities", request.sensitivity().name());
            addTextConstraint(constraints, rule, "dataCategory", request.dataCategory());
            addArrayConstraint(constraints, rule, "dataCategories", request.dataCategory());
            addTextConstraint(constraints, rule, "direction", request.direction().name());
            addArrayConstraint(constraints, rule, "directions", request.direction().name());
            addTextConstraint(constraints, rule, "provider", request.provider());
            addArrayConstraint(constraints, rule, "providers", request.provider());
            addTextConstraint(constraints, rule, "model", request.modelName());
            addArrayConstraint(constraints, rule, "models", request.modelName());
            addBooleanConstraint(constraints, rule, "internalData", request.internalData());
            addBooleanConstraint(constraints, rule, "companySecret", request.companySecret());
            addOverlapConstraint(constraints, rule, "detectedTypes", request.detectedTypes());
            addOverlapConstraint(constraints, rule, "piiTypes", request.detectedTypes());
            boolean matched = !constraints.isEmpty() && constraints.stream().allMatch(Boolean::booleanValue);
            return new PolicyMatch(policy, matched, "POLICY_" + policy.getId() + "_MATCH");
        } catch (JsonProcessingException ignored) {
            return new PolicyMatch(policy, false, "POLICY_" + policy.getId() + "_INVALID_RULE");
        }
    }

    private boolean scopeMatches(GovernancePolicy policy, GovernanceDataDtos.PreflightRequest request) {
        String type = policy.getScopeType() == null ? "GLOBAL" : policy.getScopeType().toUpperCase(Locale.ROOT);
        String value = policy.getScopeValue();
        return switch (type) {
            case "GLOBAL" -> true;
            case "AGENT" -> request.agentId() != null && value != null && value.equals(String.valueOf(request.agentId()));
            case "ENDPOINT" -> equalsIgnoreCase(value, request.endpointKey());
            case "DATA_CATEGORY" -> equalsIgnoreCase(value, request.dataCategory());
            case "PROVIDER" -> equalsIgnoreCase(value, request.provider());
            case "MODEL" -> equalsIgnoreCase(value, request.modelName());
            default -> false;
        };
    }

    private GovernanceDataAction strongestAction(List<PolicyMatch> matches) {
        return matches.stream().map(this::actionFor).max(Comparator.comparingInt(this::severity)).orElse(GovernanceDataAction.ALLOW);
    }

    private GovernanceDataAction actionFor(PolicyMatch match) {
        JsonNode rule;
        try {
            rule = readRule(match.policy().getRuleJson());
        } catch (JsonProcessingException ignored) {
            return GovernanceDataAction.WARN;
        }
        String configuredAction = rule.path("action").asText("").toUpperCase(Locale.ROOT);
        if (configuredAction.contains("REDACT") || configuredAction.contains("MASK")) {
            return GovernanceDataAction.REDACT;
        }
        return switch (match.policy().getEnforcementMode()) {
            case BLOCK -> GovernanceDataAction.BLOCK;
            case APPROVAL_REQUIRED -> GovernanceDataAction.APPROVAL_REQUIRED;
            case WARN -> GovernanceDataAction.WARN;
        };
    }

    private int severity(GovernanceDataAction action) {
        return switch (action) {
            case ALLOW -> 0;
            case WARN -> 1;
            case REDACT -> 2;
            case APPROVAL_REQUIRED -> 3;
            case BLOCK -> 4;
        };
    }

    private GovernanceGatewayDirective directive(GovernanceDataAction action) {
        return switch (action) {
            case ALLOW -> GovernanceGatewayDirective.PROCEED;
            case WARN -> GovernanceGatewayDirective.PROCEED_WITH_WARNING;
            case REDACT -> GovernanceGatewayDirective.REDACT_AND_RECHECK;
            case APPROVAL_REQUIRED -> GovernanceGatewayDirective.REQUEST_APPROVAL;
            case BLOCK -> GovernanceGatewayDirective.BLOCK;
        };
    }

    private boolean isCompanySecret(GovernanceDataDtos.PreflightRequest request) {
        return request.companySecret() || request.sensitivity() == GovernanceDataSensitivity.SECRET
                || request.sensitivity() == GovernanceDataSensitivity.RESTRICTED;
    }

    private void addTextConstraint(List<Boolean> constraints, JsonNode rule, String field, String actual) {
        if (rule.has(field)) {
            constraints.add(actual != null && rule.path(field).asText().equalsIgnoreCase(actual));
        }
    }

    private void addArrayConstraint(List<Boolean> constraints, JsonNode rule, String field, String actual) {
        if (rule.has(field)) {
            constraints.add(actual != null && containsIgnoreCase(rule.path(field), actual));
        }
    }

    private void addBooleanConstraint(List<Boolean> constraints, JsonNode rule, String field, boolean actual) {
        if (rule.has(field)) {
            constraints.add(rule.path(field).asBoolean() == actual);
        }
    }

    private void addOverlapConstraint(List<Boolean> constraints, JsonNode rule, String field, List<String> actual) {
        if (rule.has(field)) {
            List<String> values = actual == null ? List.of() : actual;
            constraints.add(values.stream().anyMatch(item -> containsIgnoreCase(rule.path(field), item)));
        }
    }

    private boolean containsIgnoreCase(JsonNode node, String value) {
        if (node.isTextual()) {
            return node.asText().equalsIgnoreCase(value);
        }
        if (!node.isArray()) {
            return false;
        }
        for (JsonNode child : node) {
            if (child.asText().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    private GovernanceDataDtos.MatchedPolicy matchedPolicy(PolicyMatch match) {
        GovernancePolicy policy = match.policy();
        return new GovernanceDataDtos.MatchedPolicy(
                policy.getId(), policy.getName(), policy.getPolicyType(), policy.getEnforcementMode(), match.reasonCode(), policy.getRevision()
        );
    }

    private GovernanceDataDtos.PreflightResponse response(GovernancePreflightDecision decision) {
        return response(decision, approvalContextRelevant(decision) && decision.policyContextDigest != null ? currentPolicyContext() : null);
    }

    private String currentPolicyContext() {
        return GovernanceApprovalContext.digest(objectMapper, properties, policyRepository.findByEnabledTrue());
    }

    private boolean approvalContextRelevant(GovernancePreflightDecision decision) {
        return decision.getApprovalStatus() == GovernanceApprovalStatus.PENDING
                || decision.getApprovalStatus() == GovernanceApprovalStatus.APPROVED;
    }

    private String approvalContextState(GovernancePreflightDecision decision, String current) {
        if (!approvalContextRelevant(decision)) return "NOT_APPLICABLE";
        if (decision.policyContextDigest == null || !decision.policyContextDigest.matches("[a-f0-9]{64}")) return "UNRECORDED";
        return decision.policyContextDigest.equals(current) ? "CURRENT" : "CHANGED";
    }

    private void requireCurrentApprovalContext(GovernancePreflightDecision decision) {
        String state = approvalContextState(decision, currentPolicyContext());
        if (!"CURRENT".equals(state)) throw new com.eap.platform.common.ConflictException(
                "UNRECORDED".equals(state) ? "审批未记录策略依据，请使用新的决策标识重新预检"
                        : "审批依据的治理策略已变更，请使用新的决策标识重新预检");
    }

    private GovernanceDataDtos.PreflightResponse response(GovernancePreflightDecision decision, String current) {
        return new GovernanceDataDtos.PreflightResponse(
                decision.id, decision.decisionKey, decision.dataEventId, decision.action, decision.gatewayDirective,
                Boolean.TRUE.equals(decision.requestAllowed), decision.matchedPolicyCount,
                readPolicies(decision.matchedPoliciesJson), readReasons(decision.reasonCodesJson),
                decision.classifier, decision.provider, decision.modelName, decision.getApprovalStatus(),
                decision.approvedBy, decision.approvalReason, decision.approvedAt, decision.requestFingerprint, decision.decidedAt,
                approvalContextState(decision, current)
        );
    }

    private void recordApprovalAudit(GovernancePreflightDecision decision, GovernanceDataDtos.ApprovalRequest request, String result) {
        AuditLog log = new AuditLog();
        log.setActor(request.approver());
        log.setAction("GOVERNANCE_APPROVAL");
        log.setResourceType("MODEL_REQUEST");
        log.setResourceId(shorten(decision.decisionKey, 64));
        log.setBeforeJson(null);
        log.setAfterJson(writeJson(new ApprovalAudit(result, decision.approvalReason)));
        log.setResult(result);
        log.setTraceId(shorten(TraceContext.traceId(), 64));
        log.setCreatedAt(Instant.now());
        auditLogRepository.save(log);
    }

    private void recordAudit(GovernancePreflightDecision decision, GovernanceDataDtos.PreflightRequest request) {
        AuditLog log = new AuditLog();
        log.setActor(request.endpointKey() == null ? "agent:" + request.agentId() : "endpoint:" + request.endpointKey());
        log.setAction("GOVERNANCE_PREFLIGHT");
        log.setResourceType("MODEL_REQUEST");
        log.setResourceId(shorten(decision.decisionKey, 64));
        log.setBeforeJson(null);
        log.setAfterJson(writeJson(new AuditDecision(
                decision.gatewayDirective.name(), decision.requestAllowed, request.dataCategory(),
                request.sensitivity().name(), decision.matchedPolicyCount
        )));
        log.setResult(decision.requestAllowed ? "ALLOWED" : "ENFORCED");
        log.setTraceId(shorten(request.traceId() == null || request.traceId().isBlank() ? TraceContext.traceId() : request.traceId(), 64));
        log.setCreatedAt(Instant.now());
        auditLogRepository.save(log);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("治理决策序列化失败", exception);
        }
    }

    private JsonNode readRule(String ruleJson) throws JsonProcessingException {
        JsonNode rule = objectMapper.readTree(ruleJson);
        if (rule != null && rule.isTextual()) {
            rule = objectMapper.readTree(rule.asText());
        }
        return rule == null ? objectMapper.createObjectNode() : rule;
    }

    private String fingerprint(GovernanceDataDtos.PreflightRequest request) {
        String provided = blankToNull(request.requestFingerprint());
        if (provided != null) {
            return provided.toLowerCase(Locale.ROOT);
        }
        try {
            String semanticInput = writeJson(new FingerprintInput(
                    request.agentId(), blankToNull(request.endpointKey()), request.taskId(), request.direction(),
                    request.dataCategory(), request.sensitivity(), request.internalData(), request.companySecret(),
                    request.tokenCount(), request.payloadBytes(), request.classifier(), blankToNull(request.provider()),
                    blankToNull(request.modelName()), request.detectedTypes() == null ? List.of() : request.detectedTypes()
            ));
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(semanticInput.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("治理请求指纹计算失败", exception);
        }
    }

    private List<GovernanceDataDtos.MatchedPolicy> readPolicies(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readerForListOf(GovernanceDataDtos.MatchedPolicy.class).readValue(json);
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    private List<String> readReasons(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readerForListOf(String.class).readValue(json);
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String normalize(String value, int maxLength) {
        String clean = blankToNull(value);
        if (clean == null) return null;
        clean = clean.replaceAll("[\\r\\n\\t]", " ").trim();
        return clean.length() > maxLength ? clean.substring(0, maxLength) : clean;
    }

    private String shorten(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private boolean sameNullable(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }

    private boolean sameText(String left, String right) {
        return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
    }

    private record PolicyMatch(GovernancePolicy policy, boolean matched, String reasonCode) {
    }

    private record AuditDecision(String directive, boolean requestAllowed, String dataCategory, String sensitivity, int matchedPolicyCount) {
    }

    private record ApprovalAudit(String result, String reason) {
    }

    private record FingerprintInput(
            Long agentId, String endpointKey, Long taskId, GovernanceDataDirection direction,
            String dataCategory, GovernanceDataSensitivity sensitivity, boolean internalData, boolean companySecret,
            long tokenCount, long payloadBytes, String classifier, String provider, String modelName,
            List<String> detectedTypes
    ) {
    }
}
