package com.eap.platform.discovery;

import com.eap.platform.audit.AuditLog;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.common.TraceContext;
import com.eap.platform.config.SecurityProperties;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DiscoveryAgentV1Service {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final DiscoveryEnrollmentTokenRepository enrollmentTokenRepository;
    private final DiscoveryEndpointRepository endpointRepository;
    private final DiscoveryIngestionBatchRepository batchRepository;
    private final AgentDiscoveryService discoveryService;
    private final DiscoveryAgentV1Properties properties;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final SecurityProperties securityProperties;

    public DiscoveryAgentV1Service(
            DiscoveryEnrollmentTokenRepository enrollmentTokenRepository,
            DiscoveryEndpointRepository endpointRepository,
            DiscoveryIngestionBatchRepository batchRepository,
            AgentDiscoveryService discoveryService,
            DiscoveryAgentV1Properties properties,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            SecurityProperties securityProperties
    ) {
        this.enrollmentTokenRepository = enrollmentTokenRepository;
        this.endpointRepository = endpointRepository;
        this.batchRepository = batchRepository;
        this.discoveryService = discoveryService;
        this.properties = properties;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.securityProperties = securityProperties;
    }

    @Transactional
    public DiscoveryAgentV1Dtos.EnrollmentTokenResponse createEnrollmentToken(
            DiscoveryAgentV1Dtos.EnrollmentTokenRequest request
    ) {
        String rawToken = randomSecret("eap_enroll_");
        DiscoveryEnrollmentToken token = new DiscoveryEnrollmentToken();
        token.tokenHash = sha256(rawToken);
        token.tokenHint = hint(rawToken);
        token.tenantId = blankDefault(request.tenantId(), "default");
        token.siteName = blankToNull(request.siteName());
        token.environmentName = blankToNull(request.environmentName());
        token.department = blankToNull(request.department());
        token.labelsJson = json(request.labels() == null ? Map.of() : request.labels());
        token.maxUses = request.maxUses() == null ? 1 : request.maxUses();
        token.usedCount = 0;
        token.status = "ACTIVE";
        token.expiresAt = Instant.now().plus(request.ttlMinutes() == null ? 15 : request.ttlMinutes(), ChronoUnit.MINUTES);
        token.createdBy = blankDefault(request.createdBy(), "platform-admin");
        token = enrollmentTokenRepository.save(token);
        audit(token.createdBy, "CREATE_ENROLLMENT_TOKEN", "DISCOVERY_ENROLLMENT_TOKEN", String.valueOf(token.id),
                Map.of("tenantId", token.tenantId, "expiresAt", token.expiresAt, "maxUses", token.maxUses));
        return enrollmentTokenResponse(token, rawToken);
    }

    @Transactional(readOnly = true)
    public List<DiscoveryAgentV1Dtos.EnrollmentTokenResponse> listEnrollmentTokens() {
        return enrollmentTokenRepository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(token -> enrollmentTokenResponse(token, null))
                .toList();
    }

    @Transactional
    public DiscoveryAgentV1Dtos.EnrollResponse enroll(DiscoveryAgentV1Dtos.EnrollRequest request) {
        DiscoveryEnrollmentToken token = enrollmentTokenRepository.findByTokenHash(sha256(request.enrollmentToken()))
                .orElseThrow(() -> new BusinessException("注册令牌无效"));
        Instant now = Instant.now();
        if (!"ACTIVE".equals(token.status) || token.expiresAt.isBefore(now) || token.usedCount >= token.maxUses) {
            throw new BusinessException("注册令牌已过期或已用尽");
        }

        DiscoveryEndpoint endpoint = endpointRepository.findByInstallationId(request.installationId()).orElse(null);
        if (endpoint != null && !"REVOKED".equals(endpoint.status) && endpoint.uninstalledAt == null) {
            throw new BusinessException("该安装实例已经注册，请使用现有机器凭证");
        }
        if (endpoint == null) endpoint = new DiscoveryEndpoint();

        String credential = randomSecret("eap_agent_");
        String contentCredential = randomSecret("eap_content_");
        endpoint.endpointKey = endpoint.endpointKey == null ? "agent-" + UUID.randomUUID() : endpoint.endpointKey;
        endpoint.installationId = request.installationId();
        endpoint.machineFingerprint = request.machineFingerprint();
        endpoint.credentialHash = sha256(credential);
        endpoint.credentialHint = hint(credential);
        endpoint.contentCredentialHash = sha256(contentCredential);
        endpoint.contentCredentialHint = hint(contentCredential);
        endpoint.hostName = request.hostName();
        endpoint.accountName = blankToNull(request.accountName());
        endpoint.operatingSystem = blankToNull(request.operatingSystem());
        endpoint.architecture = blankToNull(request.architecture());
        endpoint.collectorVersion = request.agentVersion();
        endpoint.capabilitiesJson = json(request.capabilities() == null ? List.of() : request.capabilities());
        endpoint.tenantId = token.tenantId;
        endpoint.siteName = token.siteName;
        endpoint.environmentName = token.environmentName;
        endpoint.department = token.department;
        endpoint.labelsJson = token.labelsJson;
        endpoint.status = "ONLINE";
        endpoint.policyVersion = properties.getPolicyVersion();
        endpoint.rulesVersion = properties.getRulesVersion();
        endpoint.lastHeartbeatAt = now;
        endpoint.lastSeenAt = now;
        endpoint.uninstalledAt = null;
        endpoint.fingerprintReviewRequired = false;
        endpoint.contentCaptureStatus = properties.isContentApprovalDisabled()
                ? "ENABLED_NO_APPROVAL" : "DISABLED";
        endpoint.contentPlatformApprovedBy = null;
        endpoint.contentPlatformApprovedAt = null;
        endpoint.contentDataApprovedBy = null;
        endpoint.contentDataApprovedAt = null;
        endpoint = endpointRepository.save(endpoint);

        token.usedCount++;
        token.lastUsedAt = now;
        if (token.usedCount >= token.maxUses) token.status = "USED";
        audit(endpoint.hostName, "ENROLL_DISCOVERY_AGENT", "DISCOVERY_ENDPOINT", String.valueOf(endpoint.id),
                Map.of("endpointKey", endpoint.endpointKey, "tenantId", endpoint.tenantId,
                        "agentVersion", endpoint.collectorVersion,
                        "contentApprovalMode", properties.effectiveContentApprovalMode()));
        return new DiscoveryAgentV1Dtos.EnrollResponse(
                endpoint.endpointKey, credential, endpoint.credentialHint,
                contentCredential, endpoint.contentCredentialHint, policy(endpoint), now
        );
    }

    @Transactional
    public DiscoveryAgentV1Dtos.HeartbeatResponse heartbeat(
            String credential,
            DiscoveryAgentV1Dtos.HeartbeatRequest request
    ) {
        DiscoveryEndpoint endpoint = authenticate(credential);
        if (!endpoint.installationId.equals(request.installationId())) {
            throw new BusinessException("机器凭证与安装实例不匹配");
        }
        boolean fingerprintChanged = !endpoint.machineFingerprint.equals(request.machineFingerprint());
        if (fingerprintChanged) {
            endpoint.fingerprintReviewRequired = true;
            endpoint.status = "PENDING_REVIEW";
        } else if (!"REVOKED".equals(endpoint.status) && endpoint.uninstalledAt == null) {
            endpoint.status = "ONLINE";
        }
        Instant now = Instant.now();
        endpoint.collectorVersion = request.agentVersion();
        endpoint.policyVersion = blankDefault(request.policyVersion(), endpoint.policyVersion);
        endpoint.rulesVersion = blankDefault(request.rulesVersion(), endpoint.rulesVersion);
        endpoint.queueDepth = request.queueDepth();
        endpoint.queueBytes = request.queueBytes();
        endpoint.lastScanAt = request.lastScanAt();
        endpoint.lastError = blankToNull(request.lastError());
        endpoint.capabilitiesJson = json(request.capabilities() == null ? List.of() : request.capabilities());
        if (properties.isContentApprovalDisabled() && "DISABLED".equals(endpoint.contentCaptureStatus)) {
            endpoint.contentCaptureStatus = "ENABLED_NO_APPROVAL";
            audit("system-policy", "ENABLE_CONTENT_CAPTURE_WITHOUT_APPROVAL", "DISCOVERY_ENDPOINT",
                    String.valueOf(endpoint.id), Map.of("contentApprovalMode", "NONE"));
        }
        endpoint.lastHeartbeatAt = now;
        endpoint.lastSeenAt = now;
        return new DiscoveryAgentV1Dtos.HeartbeatResponse(
                endpoint.endpointKey, endpoint.status, Boolean.TRUE.equals(endpoint.fingerprintReviewRequired), policy(endpoint), now
        );
    }

    @Transactional(readOnly = true)
    public DiscoveryAgentV1Dtos.PolicyResponse policy(String credential) {
        return policy(authenticate(credential));
    }

    @Transactional
    public DiscoveryAgentV1Dtos.ObservationBatchResponse ingest(
            String credential,
            DiscoveryAgentV1Dtos.ObservationBatchRequest request
    ) {
        DiscoveryEndpoint endpoint = authenticate(credential);
        DiscoveryIngestionBatch existing = batchRepository
                .findByEndpointKeyAndBatchId(endpoint.endpointKey, request.batchId()).orElse(null);
        if (existing != null) {
            return batchResponse(existing, true);
        }

        DiscoveryIngestionBatch batch = new DiscoveryIngestionBatch();
        batch.endpointKey = endpoint.endpointKey;
        batch.batchId = request.batchId();
        batch.scanId = request.scanId();
        batch.snapshotType = request.snapshotType().name();
        batch.observationsCount = request.observations().size();
        batch.receivedAt = Instant.now();
        batch.status = "RECEIVED";
        batch = batchRepository.save(batch);

        int discoveredCount = 0;
        if (!request.observations().isEmpty()) {
            List<AgentDiscoveryDtos.EndpointEvidenceRequest> observations = request.observations().stream()
                    .map(this::evidence)
                    .toList();
            AgentDiscoveryDtos.EndpointIngestionResponse ingested = discoveryService.ingestEndpoint(
                    new AgentDiscoveryDtos.EndpointObservationRequest(endpoint.endpointKey, observations)
            );
            discoveredCount = ingested.run().discoveredCount();
        }
        if (request.snapshotType() == DiscoveryAgentV1Dtos.SnapshotType.FULL) {
            discoveryService.reconcileEndpointSnapshot(endpoint.endpointKey,
                    request.observations().stream().map(DiscoveryAgentV1Dtos.ObservationRequest::fingerprint).collect(java.util.stream.Collectors.toSet()));
        }

        batch.discoveredCount = discoveredCount;
        batch.status = "COMPLETED";
        batch.completedAt = Instant.now();
        endpoint.lastScanAt = request.observedAt() == null ? batch.completedAt : request.observedAt();
        endpoint.lastSeenAt = batch.completedAt;
        endpoint.lastError = null;
        return batchResponse(batch, false);
    }

    @Transactional(readOnly = true)
    public List<DiscoveryAgentV1Dtos.EndpointAgentResponse> listAgents() {
        return endpointRepository.findAllByInstallationIdIsNotNullOrderByLastSeenAtDesc().stream()
                .map(this::endpointResponse).toList();
    }

    @Transactional
    public DiscoveryAgentV1Dtos.EndpointAgentResponse approveContent(
            Long endpointId,
            DiscoveryAgentV1Dtos.ContentApprovalRequest request
    ) {
        DiscoveryEndpoint endpoint = findEndpoint(endpointId);
        if (properties.isContentApprovalDisabled()) {
            throw new BusinessException("当前版本已配置为内容采集免审核模式，无需提交审批");
        }
        String role = request.approvalRole().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PLATFORM_ADMIN", "DATA_OWNER").contains(role)) {
            throw new BusinessException("内容采集审批角色必须是 PLATFORM_ADMIN 或 DATA_OWNER");
        }
        String approvedBy = request.approvedBy().trim();
        if (securityProperties.isJwtMode()) {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()
                    || jwt.getAuthorities().stream().noneMatch(authority -> authority.getAuthority().equals("ROLE_" + role))) {
                throw new AccessDeniedException("当前认证用户不具备所选内容采集审批角色");
            }
            approvedBy = jwt.getToken().getSubject();
            if (approvedBy == null || approvedBy.isBlank()) {
                throw new AccessDeniedException("内容采集审批必须具有认证用户标识");
            }
        }
        String otherApprover = "PLATFORM_ADMIN".equals(role)
                ? endpoint.contentDataApprovedBy : endpoint.contentPlatformApprovedBy;
        if (otherApprover != null && otherApprover.equalsIgnoreCase(approvedBy)) {
            throw new BusinessException("平台管理员和数据负责人必须由不同人员审批");
        }
        Instant now = Instant.now();
        if ("PLATFORM_ADMIN".equals(role)) {
            endpoint.contentPlatformApprovedBy = approvedBy;
            endpoint.contentPlatformApprovedAt = now;
        } else if ("DATA_OWNER".equals(role)) {
            endpoint.contentDataApprovedBy = approvedBy;
            endpoint.contentDataApprovedAt = now;
        } else {
            throw new BusinessException("内容采集审批角色必须是 PLATFORM_ADMIN 或 DATA_OWNER");
        }
        if (endpoint.contentPlatformApprovedBy != null && endpoint.contentDataApprovedBy != null) {
            if (endpoint.contentPlatformApprovedBy.equalsIgnoreCase(endpoint.contentDataApprovedBy)) {
                throw new BusinessException("平台管理员和数据负责人必须由不同人员审批");
            }
            endpoint.contentCaptureStatus = "ENABLED";
        } else {
            endpoint.contentCaptureStatus = "PENDING_APPROVAL";
        }
        audit(approvedBy, "APPROVE_CONTENT_CAPTURE", "DISCOVERY_ENDPOINT", String.valueOf(endpoint.id),
                Map.of("approvalRole", role, "contentCaptureStatus", endpoint.contentCaptureStatus));
        return endpointResponse(endpoint);
    }

    @Transactional
    public DiscoveryAgentV1Dtos.EndpointAgentResponse revokeContent(Long endpointId, String actor) {
        DiscoveryEndpoint endpoint = findEndpoint(endpointId);
        endpoint.contentCaptureStatus = properties.isContentApprovalDisabled()
                ? "DISABLED_MANUALLY" : "DISABLED";
        endpoint.contentPlatformApprovedBy = null;
        endpoint.contentPlatformApprovedAt = null;
        endpoint.contentDataApprovedBy = null;
        endpoint.contentDataApprovedAt = null;
        audit(blankDefault(actor, "platform-admin"), "REVOKE_CONTENT_CAPTURE", "DISCOVERY_ENDPOINT", String.valueOf(endpoint.id), Map.of());
        return endpointResponse(endpoint);
    }

    @Transactional
    public DiscoveryAgentV1Dtos.EndpointAgentResponse enableContent(Long endpointId, String actor) {
        if (!properties.isContentApprovalDisabled()) {
            throw new BusinessException("当前版本要求完成内容采集审批");
        }
        DiscoveryEndpoint endpoint = findEndpoint(endpointId);
        endpoint.contentCaptureStatus = "ENABLED_NO_APPROVAL";
        audit(blankDefault(actor, "platform-admin"), "ENABLE_CONTENT_CAPTURE_WITHOUT_APPROVAL",
                "DISCOVERY_ENDPOINT", String.valueOf(endpoint.id), Map.of("contentApprovalMode", "NONE"));
        return endpointResponse(endpoint);
    }

    @Transactional
    public DiscoveryAgentV1Dtos.EndpointAgentResponse revokeAgent(Long endpointId, String actor) {
        DiscoveryEndpoint endpoint = findEndpoint(endpointId);
        endpoint.status = "REVOKED";
        endpoint.credentialHash = null;
        endpoint.contentCredentialHash = null;
        endpoint.contentCaptureStatus = "DISABLED";
        audit(blankDefault(actor, "platform-admin"), "REVOKE_DISCOVERY_AGENT", "DISCOVERY_ENDPOINT", String.valueOf(endpoint.id), Map.of());
        return endpointResponse(endpoint);
    }

    @Transactional
    public DiscoveryAgentV1Dtos.EndpointAgentResponse approveFingerprint(Long endpointId, String actor) {
        DiscoveryEndpoint endpoint = findEndpoint(endpointId);
        endpoint.fingerprintReviewRequired = false;
        endpoint.status = "ONLINE";
        audit(blankDefault(actor, "platform-admin"), "APPROVE_MACHINE_FINGERPRINT", "DISCOVERY_ENDPOINT", String.valueOf(endpoint.id), Map.of());
        return endpointResponse(endpoint);
    }

    @Transactional
    public void uninstall(String credential) {
        DiscoveryEndpoint endpoint = authenticate(credential);
        endpoint.status = "UNINSTALLED";
        endpoint.uninstalledAt = Instant.now();
        endpoint.credentialHash = null;
        endpoint.contentCredentialHash = null;
        endpoint.contentCaptureStatus = "DISABLED";
        audit(endpoint.hostName, "UNINSTALL_DISCOVERY_AGENT", "DISCOVERY_ENDPOINT", String.valueOf(endpoint.id), Map.of());
    }

    @Transactional(readOnly = true)
    public DiscoveryEndpoint authenticate(String credential) {
        if (credential == null || credential.isBlank()) throw new BusinessException("缺少发现 Agent 机器凭证");
        DiscoveryEndpoint endpoint = endpointRepository.findByCredentialHash(sha256(credential.trim()))
                .orElseThrow(() -> new BusinessException("发现 Agent 机器凭证无效"));
        if ("REVOKED".equals(endpoint.status) || "UNINSTALLED".equals(endpoint.status) || endpoint.uninstalledAt != null) {
            throw new BusinessException("发现 Agent 已被吊销或卸载");
        }
        return endpoint;
    }

    @Transactional(readOnly = true)
    public DiscoveryEndpoint authenticateContent(String credential) {
        if (credential == null || credential.isBlank()) throw new BusinessException("缺少内容代理机器凭证");
        DiscoveryEndpoint endpoint = endpointRepository.findByContentCredentialHash(sha256(credential.trim()))
                .orElseThrow(() -> new BusinessException("内容代理机器凭证无效"));
        if ("REVOKED".equals(endpoint.status) || "UNINSTALLED".equals(endpoint.status) || endpoint.uninstalledAt != null) {
            throw new BusinessException("发现 Agent 已被吊销或卸载");
        }
        return endpoint;
    }

    @Transactional(readOnly = true)
    public DiscoveryAgentV1Dtos.PolicyResponse contentPolicy(String credential) {
        return policy(authenticateContent(credential));
    }

    public DiscoveryAgentV1Dtos.EndpointAgentResponse endpointResponse(DiscoveryEndpoint endpoint) {
        boolean contentEnabled = isContentCaptureEnabled(endpoint);
        String contentStatus = effectiveContentCaptureStatus(endpoint);
        List<String> collectionScopes = new ArrayList<>(List.of("PROCESS", "DOCKER", "PORTS", "FILES", "SKILLS", "PLUGINS", "MCP", "A2A", "HOST_OBSERVER"));
        if (endpoint.capabilitiesJson != null && endpoint.capabilitiesJson.contains("EBPF")) collectionScopes.add("EBPF_METADATA");
        if (contentEnabled) collectionScopes.add("BUSINESS_CONTENT");
        String tokenUsageStatus = contentEnabled ? "AVAILABLE_WHEN_REPORTED" : "NOT_AVAILABLE";
        String tokenUsageNote = contentEnabled
                ? "仅在模型响应包含 usage 或经网关/OTel 上报时可用；共享 API Key 的未归因差额单独保留"
                : "未启用业务内容通道；eBPF 不读取 HTTPS 正文，无法提供 Token 用量";
        return new DiscoveryAgentV1Dtos.EndpointAgentResponse(
                endpoint.id, endpoint.endpointKey, endpoint.hostName, endpoint.accountName, endpoint.operatingSystem,
                endpoint.architecture, endpoint.collectorVersion, endpoint.status, endpoint.tenantId,
                endpoint.siteName, endpoint.environmentName, endpoint.department, readMap(endpoint.labelsJson),
                readList(endpoint.capabilitiesJson), endpoint.policyVersion, endpoint.rulesVersion,
                endpoint.lastHeartbeatAt, endpoint.lastScanAt, value(endpoint.queueDepth), value(endpoint.queueBytes),
                endpoint.lastError, Boolean.TRUE.equals(endpoint.fingerprintReviewRequired), contentStatus,
                endpoint.contentPlatformApprovedBy, endpoint.contentDataApprovedBy,
                properties.effectiveContentApprovalMode(), contentEnabled, collectionScopes,
                tokenUsageStatus, tokenUsageNote, endpoint.firstSeenAt,
                endpoint.lastSeenAt, endpoint.uninstalledAt
        );
    }

    private DiscoveryAgentV1Dtos.PolicyResponse policy(DiscoveryEndpoint endpoint) {
        boolean contentEnabled = isContentCaptureEnabled(endpoint);
        return new DiscoveryAgentV1Dtos.PolicyResponse(
                properties.getPolicyVersion(), properties.getRulesVersion(),
                properties.getHeartbeatIntervalSeconds(), properties.getScanIntervalSeconds(),
                properties.getFullSnapshotIntervalSeconds(), properties.getOfflineQueueDays(),
                properties.getOfflineQueueMaxBytes(), properties.isRequireHttps(),
                List.of("PROCESS", "DOCKER", "PORTS", "FILES", "SKILLS", "PLUGINS", "MCP", "A2A", "HOST_OBSERVER"),
                AgentSignatureCatalog.RULES.stream().map(rule -> new DiscoveryAgentV1Dtos.AgentSignatureRuleResponse(
                        rule.product(), rule.vendor(), rule.framework(), rule.processMarkers(), rule.contentMarkers(),
                        rule.packageNames(), rule.homeDirectories(), rule.manifestNames()
                )).toList(),
                contentEnabled, contentEnabled && properties.isContentAutoAttachEnabled(),
                contentEnabled && properties.isContentAutoAttachEnabled()
                        ? properties.contentAutoAttachProfileList() : List.of(),
                contentEnabled ? properties.getRawContentSampleRate() : 0.0,
                properties.getRawContentRetentionDays()
        );
    }

    public boolean isContentCaptureEnabled(DiscoveryEndpoint endpoint) {
        if (Boolean.TRUE.equals(endpoint.fingerprintReviewRequired)) return false;
        if (properties.isContentApprovalDisabled()) {
            return !"DISABLED_MANUALLY".equals(endpoint.contentCaptureStatus);
        }
        return "ENABLED".equals(endpoint.contentCaptureStatus);
    }

    private String effectiveContentCaptureStatus(DiscoveryEndpoint endpoint) {
        if (properties.isContentApprovalDisabled()) {
            return isContentCaptureEnabled(endpoint) ? "NOT_REQUIRED" : "DISABLED";
        }
        if ("ENABLED_NO_APPROVAL".equals(endpoint.contentCaptureStatus)) return "DISABLED";
        return endpoint.contentCaptureStatus;
    }

    private AgentDiscoveryDtos.EndpointEvidenceRequest evidence(DiscoveryAgentV1Dtos.ObservationRequest item) {
        Map<String, Object> details = new LinkedHashMap<>();
        put(details, "framework", item.framework());
        put(details, "runtimeStatus", item.runtimeStatus());
        put(details, "ports", item.ports());
        put(details, "protocols", item.protocols());
        put(details, "serviceEndpoints", item.serviceEndpoints());
        put(details, "skills", item.skills());
        put(details, "plugins", item.plugins());
        put(details, "tools", item.tools());
        put(details, "mcpServers", item.mcpServers());
        put(details, "models", item.models());
        put(details, "relationships", item.relationships());
        if (item.securitySignals() != null) details.putAll(item.securitySignals());
        int mcpCount = item.mcpServers() == null ? 0 : item.mcpServers().size();
        int endpointCount = item.serviceEndpoints() == null ? 0 : item.serviceEndpoints().size();
        boolean autoApprove = signal(item.securitySignals(), "autoApprove", "autoApproveDetected");
        Boolean trusted = signal(item.securitySignals(), "untrustedProcess", "trustedProcessFalse") ? false : null;
        return new AgentDiscoveryDtos.EndpointEvidenceRequest(
                item.fingerprint(), item.name(), item.vendor(), item.product(), item.version(), item.assetType(),
                item.sourceType(), item.sourceLocation(), item.accountName(), item.processName(), item.executablePath(),
                autoApprove, trusted, mcpCount, item.confidence(),
                DiscoveryAgentV1Dtos.recommendedRisk(item.securitySignals(), mcpCount, endpointCount),
                item.evidenceType(), details
        );
    }

    private boolean signal(Map<String, Object> values, String... keys) {
        if (values == null) return false;
        for (String key : keys) {
            Object value = values.get(key);
            if (value instanceof Boolean bool && bool) return true;
            if (value != null && "true".equalsIgnoreCase(String.valueOf(value))) return true;
        }
        return false;
    }

    private DiscoveryAgentV1Dtos.ObservationBatchResponse batchResponse(DiscoveryIngestionBatch batch, boolean duplicate) {
        return new DiscoveryAgentV1Dtos.ObservationBatchResponse(
                batch.endpointKey, batch.batchId, duplicate, batch.observationsCount,
                batch.discoveredCount, batch.completedAt
        );
    }

    private DiscoveryAgentV1Dtos.EnrollmentTokenResponse enrollmentTokenResponse(DiscoveryEnrollmentToken token, String raw) {
        return new DiscoveryAgentV1Dtos.EnrollmentTokenResponse(
                token.id, raw, token.tokenHint, token.tenantId, token.siteName, token.environmentName,
                token.department, readMap(token.labelsJson), token.maxUses, token.usedCount,
                token.status, token.expiresAt, token.createdBy, token.createdAt
        );
    }

    private DiscoveryEndpoint findEndpoint(Long id) {
        return endpointRepository.findById(id).orElseThrow(() -> new BusinessException("发现探针不存在"));
    }

    private String randomSecret(String prefix) {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String hint(String secret) {
        return secret.substring(0, Math.min(18, secret.length())) + "…";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("发现 Agent 配置无法序列化");
        }
    }

    private Map<String, String> readMap(String value) {
        if (value == null || value.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(value, STRING_MAP);
        } catch (JsonProcessingException ignored) {
            return Map.of();
        }
    }

    private List<String> readList(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    private void audit(String actor, String action, String resourceType, String resourceId, Object after) {
        AuditLog log = new AuditLog();
        log.setActor(blankDefault(actor, "system"));
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setAfterJson(json(after));
        log.setResult("SUCCESS");
        log.setTraceId(TraceContext.traceId());
        log.setCreatedAt(Instant.now());
        auditLogRepository.save(log);
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private long value(Long value) { return value == null ? 0L : value; }
}
