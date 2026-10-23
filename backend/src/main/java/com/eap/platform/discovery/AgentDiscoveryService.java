package com.eap.platform.discovery;

import com.eap.platform.agent.Agent;
import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.agent.RiskLevel;
import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AgentDiscoveryService {
    private final DiscoveryProperties properties;
    private final LocalAgentScanner scanner;
    private final DiscoveredAgentRepository agentRepository;
    private final DiscoveryEndpointRepository endpointRepository;
    private final DiscoveryRunRepository runRepository;
    private final DiscoveryObservationRepository observationRepository;
    private final DiscoveryIdentityLinkRepository identityLinkRepository;
    private final AgentRepository managedAgentRepository;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);

    public AgentDiscoveryService(
            DiscoveryProperties properties,
            LocalAgentScanner scanner,
            DiscoveredAgentRepository agentRepository,
            DiscoveryEndpointRepository endpointRepository,
            DiscoveryRunRepository runRepository,
            DiscoveryObservationRepository observationRepository,
            DiscoveryIdentityLinkRepository identityLinkRepository,
            AgentRepository managedAgentRepository,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.scanner = scanner;
        this.agentRepository = agentRepository;
        this.endpointRepository = endpointRepository;
        this.runRepository = runRepository;
        this.observationRepository = observationRepository;
        this.identityLinkRepository = identityLinkRepository;
        this.managedAgentRepository = managedAgentRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentDiscoveryDtos.DiscoveryRunResponse runScan(DiscoveryTriggerType triggerType) {
        if (!scanRunning.compareAndSet(false, true)) {
            throw new BusinessException("已有自动发现扫描正在运行");
        }
        DiscoveryRun run = new DiscoveryRun();
        run.triggerType = triggerType;
        run.startedAt = Instant.now();
        run = runRepository.save(run);
        try {
            List<DiscoveryEvidence> evidence = scanner.scan();
            Set<Long> discoveredIds = new LinkedHashSet<>();
            for (DiscoveryEvidence item : evidence) {
                DiscoveredAgent agent = upsert(item);
                if (agent == null) continue;
                discoveredIds.add(agent.id);
                saveObservation(run.id, agent.id, item);
            }
            run.status = DiscoveryRunStatus.SUCCEEDED;
            run.observationsCount = evidence.size();
            run.discoveredCount = discoveredIds.size();
            run.finishedAt = Instant.now();
            return AgentDiscoveryDtos.from(runRepository.save(run));
        } catch (RuntimeException exception) {
            run.status = DiscoveryRunStatus.FAILED;
            run.errorMessage = truncate(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(), 1024);
            run.finishedAt = Instant.now();
            runRepository.save(run);
            throw exception;
        } finally {
            scanRunning.set(false);
        }
    }

    @Transactional(readOnly = true)
    public List<AgentDiscoveryDtos.DiscoveredAgentResponse> listAgents() {
        return agentRepository.findAllByOrderByLastSeenAtDesc().stream().map(AgentDiscoveryDtos::from).toList();
    }

    @Transactional(readOnly = true)
    public List<AgentDiscoveryDtos.DiscoveredAgentResponse> listByManagedAgentId(Long managedAgentId) {
        return agentRepository.findAllByManagedAgentIdOrderByLastSeenAtDesc(managedAgentId).stream()
                .map(AgentDiscoveryDtos::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AgentDiscoveryDtos.DiscoveryRunResponse> listRuns() {
        return runRepository.findTop20ByOrderByStartedAtDesc().stream().map(AgentDiscoveryDtos::from).toList();
    }

    @Transactional(readOnly = true)
    public List<AgentDiscoveryDtos.DiscoveryEndpointResponse> listEndpoints() {
        return endpointRepository.findAllByOrderByLastSeenAtDesc().stream().map(AgentDiscoveryDtos::from).toList();
    }

    @Transactional(readOnly = true)
    public List<AgentDiscoveryDtos.DiscoveryIdentityResponse> listIdentityLinks() {
        return identityLinkRepository.findTop100ByOrderByLastSeenAtDesc().stream()
                .map(AgentDiscoveryDtos::from).toList();
    }

    @Transactional
    public AgentDiscoveryDtos.DiscoveryEndpointResponse registerEndpoint(AgentDiscoveryDtos.EndpointRegistrationRequest request) {
        return AgentDiscoveryDtos.from(upsertEndpoint(request));
    }

    @Transactional
    public AgentDiscoveryDtos.EndpointIngestionResponse ingestEndpoint(AgentDiscoveryDtos.EndpointObservationRequest request) {
        DiscoveryEndpoint endpoint = endpointRepository.findByEndpointKey(request.endpointKey())
                .orElseThrow(() -> new BusinessException("端点尚未注册：" + request.endpointKey()));
        touchEndpoint(endpoint);

        DiscoveryRun run = new DiscoveryRun();
        run.triggerType = DiscoveryTriggerType.ENDPOINT;
        run.endpointKey = endpoint.endpointKey;
        run.startedAt = Instant.now();
        run = runRepository.save(run);
        try {
            Set<Long> discoveredIds = new LinkedHashSet<>();
            for (AgentDiscoveryDtos.EndpointEvidenceRequest item : request.observations()) {
                DiscoveryEvidence evidence = toEvidence(endpoint, item);
                DiscoveredAgent agent = upsert(evidence);
                if (agent == null) continue;
                agent.lastEndpointKey = endpoint.endpointKey;
                agent.availabilityStatus = "ONLINE";
                agent.missingSnapshotCount = 0;
                discoveredIds.add(agent.id);
                saveObservation(run.id, agent.id, evidence);
            }
            run.status = DiscoveryRunStatus.SUCCEEDED;
            run.observationsCount = request.observations().size();
            run.discoveredCount = discoveredIds.size();
            run.finishedAt = Instant.now();
            return new AgentDiscoveryDtos.EndpointIngestionResponse(
                    AgentDiscoveryDtos.from(endpoint),
                    AgentDiscoveryDtos.from(runRepository.save(run))
            );
        } catch (RuntimeException exception) {
            run.status = DiscoveryRunStatus.FAILED;
            run.errorMessage = truncate(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(), 1024);
            run.finishedAt = Instant.now();
            runRepository.save(run);
            throw exception;
        }
    }

    /**
     * Normalizes a bounded Microsoft Defender AgentsInfo/Exposure Graph export
     * and routes it through the same endpoint ingestion and evidence redaction
     * pipeline as local collectors.
     */
    @Transactional
    public AgentDiscoveryDtos.EndpointIngestionResponse ingestMicrosoftDefender(
            AgentDiscoveryDtos.MicrosoftDefenderImportRequest request
    ) {
        upsertEndpoint(new AgentDiscoveryDtos.EndpointRegistrationRequest(
                request.endpointKey(), request.hostName(), request.accountName(),
                request.operatingSystem(), "microsoft-defender-adapter/0.1"
        ));
        List<AgentDiscoveryDtos.EndpointEvidenceRequest> normalized = request.agentsInfo().stream()
                .map(item -> normalizeDefenderEvidence(request, item))
                .toList();
        return ingestEndpoint(new AgentDiscoveryDtos.EndpointObservationRequest(request.endpointKey(), normalized));
    }

    @Transactional(readOnly = true)
    public AgentDiscoveryDtos.DiscoverySummary summary() {
        DiscoveryRun lastRun = runRepository.findTopByOrderByStartedAtDesc().orElse(null);
        return new AgentDiscoveryDtos.DiscoverySummary(
                properties.isEnabled(),
                agentRepository.count(),
                agentRepository.countByAssetTypeIn(List.of(DiscoveryAssetType.AGENT, DiscoveryAssetType.AGENT_PROJECT)),
                agentRepository.countByAssetTypeIn(List.of(DiscoveryAssetType.MCP_CONFIGURATION)),
                agentRepository.countByAssetTypeIn(List.of(DiscoveryAssetType.AGENT_SKILL)),
                agentRepository.countByAssetTypeIn(List.of(DiscoveryAssetType.AGENT_PLUGIN)),
                agentRepository.countByRiskLevelIn(List.of(RiskLevel.HIGH, RiskLevel.CRITICAL)),
                agentRepository.countByStatus(DiscoveryAssetStatus.REVIEWING),
                agentRepository.countByStatus(DiscoveryAssetStatus.MANAGED),
                AgentDiscoveryDtos.from(lastRun)
        );
    }

    public String ingestionToken() {
        return properties.getIngestionToken();
    }

    @Transactional
    public AgentDiscoveryDtos.DiscoveredAgentResponse updateStatus(Long id, DiscoveryAssetStatus status) {
        if (status == DiscoveryAssetStatus.MANAGED) {
            return manage(id);
        }
        DiscoveredAgent agent = agentRepository.findById(id).orElseThrow(() -> new BusinessException("发现的智能体资产不存在"));
        if (agent.managedAgentId != null) {
            throw new BusinessException("该资产已纳入智能体目录，不能仅修改候选状态");
        }
        agent.status = status;
        return AgentDiscoveryDtos.from(agent);
    }

    @Transactional
    public AgentDiscoveryDtos.DiscoveredAgentResponse manage(Long id) {
        DiscoveredAgent discovered = agentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException("发现的智能体资产不存在"));
        if (discovered.assetType != DiscoveryAssetType.AGENT
                && discovered.assetType != DiscoveryAssetType.AGENT_PROJECT) {
            throw new BusinessException("只有智能体或智能体项目可以纳入智能体目录");
        }
        ensureManaged(discovered);
        return AgentDiscoveryDtos.from(discovered);
    }

    private void ensureManaged(DiscoveredAgent discovered) {
        if (discovered.assetType != DiscoveryAssetType.AGENT
                && discovered.assetType != DiscoveryAssetType.AGENT_PROJECT) {
            return;
        }
        if (discovered.managedAgentId != null) {
            if (!managedAgentRepository.existsById(discovered.managedAgentId)) {
                throw new BusinessException("发现资产关联的正式智能体不存在，请检查数据完整性");
            }
            discovered.status = DiscoveryAssetStatus.MANAGED;
            return;
        }

        Agent managed = new Agent();
        managed.setCode(nextManagedAgentCode(discovered));
        managed.setName(truncate(preferredManagedName(discovered), 128));
        managed.setDescription(truncate(managedDescription(discovered), 1024));
        managed.setOwnerDepartment("自动发现");
        managed.setOwnerUser("EAP Discovery");
        managed.setCategory("自主发现智能体");
        managed.setLifecycleStatus(AgentLifecycleStatus.ACTIVE);
        managed.setRiskLevel(discovered.riskLevel);
        managed = managedAgentRepository.save(managed);

        discovered.managedAgentId = managed.getId();
        discovered.status = DiscoveryAssetStatus.MANAGED;
    }

    @Transactional
    public void reconcileEndpointSnapshot(String endpointKey, Set<String> observedFingerprints) {
        Instant staleBefore = Instant.now().minusSeconds(86_400);
        Set<String> fingerprints = observedFingerprints == null ? Set.of() : observedFingerprints.stream()
                .map(value -> fingerprint("endpoint", endpointKey, value))
                .collect(java.util.stream.Collectors.toSet());
        for (DiscoveredAgent asset : agentRepository.findAllByLastEndpointKey(endpointKey)) {
            if (fingerprints.contains(asset.fingerprint)) {
                asset.availabilityStatus = "ONLINE";
                asset.missingSnapshotCount = 0;
                continue;
            }
            asset.missingSnapshotCount = (asset.missingSnapshotCount == null ? 0 : asset.missingSnapshotCount) + 1;
            if (asset.missingSnapshotCount >= 3 || asset.lastSeenAt.isBefore(staleBefore)) {
                asset.availabilityStatus = "OFFLINE";
            }
        }
    }

    @Transactional
    public void markEndpointOffline(String endpointKey) {
        agentRepository.findAllByLastEndpointKey(endpointKey).forEach(agent -> {
            agent.availabilityStatus = "OFFLINE";
            agentRepository.save(agent);
        });
    }

    private String nextManagedAgentCode(DiscoveredAgent discovered) {
        String productPart = compactCode(discovered.product);
        if (productPart.isBlank()) productPart = "AGENT";
        String fingerprintPart = compactCode(discovered.fingerprint);
        if (fingerprintPart.isBlank()) fingerprintPart = String.valueOf(discovered.id);
        String base = truncate("DISC-" + productPart + "-" + fingerprintPart, 58);
        String candidate = base;
        int suffix = 2;
        while (managedAgentRepository.existsByCode(candidate)) {
            candidate = truncate(base, 58) + "-" + suffix++;
        }
        return candidate;
    }

    private String compactCode(String value) {
        if (value == null) return "";
        return truncate(value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-"), 18)
                .replaceAll("^-+|-+$", "");
    }

    private String preferredManagedName(DiscoveredAgent discovered) {
        if (discovered.product != null && !discovered.product.isBlank()
                && !"UNKNOWN".equalsIgnoreCase(discovered.product)) {
            return discovered.product;
        }
        return discovered.name;
    }

    private String managedDescription(DiscoveredAgent discovered) {
        List<String> details = new java.util.ArrayList<>();
        addDetail(details, "厂商", discovered.vendor);
        addDetail(details, "产品", discovered.product);
        addDetail(details, "版本", discovered.version);
        addDetail(details, "主机", discovered.hostName);
        addDetail(details, "账户", discovered.accountName);
        addDetail(details, "进程", discovered.processName);
        addDetail(details, "执行路径", discovered.executablePath);
        addDetail(details, "发现来源", discovered.sourceTypes);
        if (discovered.confidence != null) details.add("置信度：" + discovered.confidence + "%");
        return "由 EAP 自主发现并纳入智能体目录。" + String.join("；", details) + "。";
    }

    private void addDetail(List<String> details, String label, String value) {
        if (value != null && !value.isBlank()) details.add(label + "：" + value);
    }

    private AgentDiscoveryDtos.EndpointEvidenceRequest normalizeDefenderEvidence(
            AgentDiscoveryDtos.MicrosoftDefenderImportRequest request,
            Map<String, Object> raw
    ) {
        Map<String, Object> row = raw == null ? Map.of() : raw;
        String sourceIdentity = firstValue(row, "fingerprint", "agentId", "id", "deviceId", "deviceName", "name");
        String name = truncate(firstValue(row, "name", "agentName", "displayName", "product", "agentType"), 256);
        if (name == null || name.isBlank()) name = "Unidentified local AI agent";
        String vendor = truncate(firstValue(row, "vendor", "publisher", "manufacturer"), 128);
        if (vendor == null || vendor.isBlank()) vendor = "Microsoft Defender";
        String product = truncate(firstValue(row, "product", "agentType", "agentName", "name"), 128);
        if (product == null || product.isBlank()) product = "Local AI Agent";
        int mcpCount = integerValue(row, 0, "mcpServerCount", "mcpCount", "mcpServers");
        boolean remoteMcp = booleanValue(row, "remoteMcp", "remoteMCP", "remoteMcpServer");
        boolean localMcp = booleanValue(row, "localMcp", "localMCP", "localMcpServer");
        if (mcpCount == 0 && (remoteMcp || localMcp)) mcpCount = 1;
        DiscoveryAssetType assetType = assetTypeValue(row, mcpCount, product);
        Boolean autoApprove = nullableBoolean(row, "autoApprove", "auto_approve");
        Boolean trustedProcess = nullableBoolean(row, "trustedProcess", "trusted_process");
        boolean credentialReference = booleanValue(row, "credentialReferenceDetected", "credentialReference", "secretDetected");
        RiskLevel requestedRisk = riskValue(row, autoApprove, trustedProcess, credentialReference, mcpCount);
        int confidence = Math.max(0, Math.min(100, integerValue(row, 85, "confidence", "confidenceScore")));
        String deviceName = firstValue(row, "deviceName", "device", "hostName", "host");
        String sourceLocation = firstValue(row, "sourceLocation", "deviceId", "deviceName", "id");
        if (sourceLocation == null || sourceLocation.isBlank()) {
            sourceLocation = "defender:" + request.hostName() + "/" + (sourceIdentity == null ? name : sourceIdentity);
        }
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("sourceSystem", "MICROSOFT_DEFENDER");
        putIfPresent(evidence, "deviceId", firstValue(row, "deviceId", "device_id"));
        putIfPresent(evidence, "deviceName", deviceName);
        putIfPresent(evidence, "relatedProcess", firstValue(row, "relatedProcess", "relatedProcessName", "processName"));
        putIfPresent(evidence, "exposureGraphNodeId", firstValue(row, "exposureGraphNodeId", "exposureNodeId"));
        putIfPresent(evidence, "exposureGraphEdgeCount", integerOrNull(row, "exposureGraphEdgeCount", "exposureEdgeCount"));
        putIfPresent(evidence, "remoteMcpDetected", remoteMcp);
        putIfPresent(evidence, "localMcpDetected", localMcp);
        putIfPresent(evidence, "credentialReferenceDetected", credentialReference);
        putIfPresent(evidence, "autoApproveDetected", Boolean.TRUE.equals(autoApprove));
        String evidenceType = truncate(firstValue(row, "evidenceType"), 128);
        return new AgentDiscoveryDtos.EndpointEvidenceRequest(
                sourceIdentity == null || sourceIdentity.isBlank()
                        ? fingerprint("defender", request.endpointKey(), name, product, sourceLocation)
                        : truncate(sourceIdentity, 128),
                name,
                vendor,
                product,
                truncate(firstValue(row, "version", "agentVersion"), 64),
                assetType,
                DiscoverySourceType.EXTERNAL,
                truncate(sourceLocation, 1024),
                truncate(firstValue(row, "accountName", "account", "userName", "user"), 256),
                truncate(firstValue(row, "processName", "relatedProcessName", "process"), 256),
                truncate(firstValue(row, "executablePath", "processPath", "path"), 1024),
                autoApprove,
                trustedProcess,
                mcpCount,
                confidence,
                requestedRisk,
                evidenceType == null ? "MICROSOFT_DEFENDER_AGENTS_INFO" : evidenceType,
                evidence
        );
    }

    private DiscoveryAssetType assetTypeValue(Map<String, Object> row, int mcpCount, String product) {
        String value = firstValue(row, "assetType", "asset_type", "type");
        if (value != null) {
            String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            try {
                return DiscoveryAssetType.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                if (normalized.contains("MCP")) return DiscoveryAssetType.MCP_CONFIGURATION;
                if (normalized.contains("SKILL")) return DiscoveryAssetType.AGENT_SKILL;
                if (normalized.contains("PLUGIN") || normalized.contains("EXTENSION")) return DiscoveryAssetType.AGENT_PLUGIN;
            }
        }
        return mcpCount > 0 && product.toLowerCase(Locale.ROOT).contains("mcp")
                ? DiscoveryAssetType.MCP_CONFIGURATION : DiscoveryAssetType.AGENT;
    }

    private RiskLevel riskValue(Map<String, Object> row, Boolean autoApprove, Boolean trustedProcess, boolean credentialReference, int mcpCount) {
        String value = firstValue(row, "riskLevel", "risk", "exposureLevel");
        if (value != null) {
            try {
                return RiskLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall back to deterministic EAP derivation for unknown preview values.
            }
        }
        return riskLevel(Boolean.TRUE.equals(autoApprove), trustedProcess, credentialReference, mcpCount, DiscoverySourceType.EXTERNAL);
    }

    private String firstValue(Map<String, Object> row, String... names) {
        for (String name : names) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null) {
                    String value = String.valueOf(entry.getValue());
                    if (!value.isBlank()) return value;
                }
            }
        }
        return null;
    }

    private Object rawValue(Map<String, Object> row, String... names) {
        for (String name : names) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
            }
        }
        return null;
    }

    private int integerValue(Map<String, Object> row, int fallback, String... names) {
        Object value = rawValue(row, names);
        if (value instanceof Number number) return Math.max(0, Math.min(10000, number.intValue()));
        try {
            return value == null ? fallback : Math.max(0, Math.min(10000, Integer.parseInt(String.valueOf(value))));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private Integer integerOrNull(Map<String, Object> row, String... names) {
        return rawValue(row, names) == null ? null : integerValue(row, 0, names);
    }

    private boolean booleanValue(Map<String, Object> row, String... names) {
        Object value = rawValue(row, names);
        if (value instanceof Boolean bool) return bool;
        return value != null && Set.of("true", "1", "yes", "y").contains(String.valueOf(value).trim().toLowerCase(Locale.ROOT));
    }

    private Boolean nullableBoolean(Map<String, Object> row, String... names) {
        return rawValue(row, names) == null ? null : booleanValue(row, names);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private DiscoveredAgent upsert(DiscoveryEvidence item) {
        Instant now = Instant.now();
        Identity identity = identity(item);
        boolean passiveNetworkEvidence = item.sourceType() == DiscoverySourceType.EBPF
                || item.sourceType() == DiscoverySourceType.HOST_OBSERVER;
        java.util.Optional<DiscoveredAgent> fingerprintMatch = agentRepository.findByFingerprint(item.fingerprint());
        java.util.Optional<DiscoveryIdentityLink> identityMatch = fingerprintMatch.isPresent()
                ? java.util.Optional.empty() : identityLinkRepository.findByIdentityKey(identity.key());
        if (passiveNetworkEvidence && fingerprintMatch.isEmpty() && identityMatch.isEmpty()) {
            // Model API traffic is supporting evidence only. It must never invent an agent identity.
            return null;
        }
        DiscoveredAgent agent = fingerprintMatch.orElseGet(() -> identityMatch
                .map(link -> link.discoveredAgent).orElseGet(DiscoveredAgent::new));
        boolean newAsset = agent.id == null;
        if (newAsset || !passiveNetworkEvidence) {
            agent.fingerprint = item.fingerprint();
            agent.name = prefer(item.name(), agent.name);
            agent.vendor = prefer(item.vendor(), agent.vendor);
            agent.product = prefer(item.product(), agent.product);
            agent.version = prefer(item.version(), agent.version);
            agent.assetType = item.assetType();
            agent.processName = prefer(item.processName(), agent.processName);
            agent.executablePath = prefer(item.executablePath(), agent.executablePath);
        }
        agent.confidence = agent.confidence == null ? item.confidence() : Math.max(agent.confidence, item.confidence());
        agent.riskLevel = maxRisk(agent.riskLevel, item.riskLevel());
        agent.sourceTypes = mergeSourceType(agent.sourceTypes, item.sourceType());
        agent.hostName = prefer(item.hostName(), agent.hostName);
        agent.accountName = prefer(item.accountName(), agent.accountName);
        agent.autoApprove = Boolean.TRUE.equals(agent.autoApprove) || Boolean.TRUE.equals(item.autoApprove());
        agent.trustedProcess = mergeTrust(agent.trustedProcess, item.trustedProcess());
        agent.mcpServerCount = Math.max(agent.mcpServerCount == null ? 0 : agent.mcpServerCount, item.mcpServerCount());
        agent.evidenceCount = (agent.evidenceCount == null ? 0 : agent.evidenceCount) + 1;
        agent.firstSeenAt = newAsset ? now : agent.firstSeenAt;
        agent.lastSeenAt = now;
        DiscoveredAgent saved = agentRepository.save(agent);
        if (saved == null) saved = agent;
        if (saved.id == null) {
            DiscoveredAgent flushed = agentRepository.saveAndFlush(saved);
            if (flushed != null) saved = flushed;
        }
        if (saved.id == null) throw new BusinessException("发现资产写入后未获得稳定 ID");
        linkIdentity(saved, item, identity, now);
        ensureManaged(saved);
        return saved;
    }

    private void linkIdentity(DiscoveredAgent agent, DiscoveryEvidence item, Identity identity, Instant now) {
        DiscoveryIdentityLink link = identityLinkRepository.findByIdentityKey(identity.key())
                .orElseGet(DiscoveryIdentityLink::new);
        if (link.discoveredAgent != null && link.discoveredAgent.id != null
                && !link.discoveredAgent.id.equals(agent.id)) {
            // Keep the first canonical owner; the new observation still remains in its own evidence trail.
            return;
        }
        link.identityKey = identity.key();
        link.discoveredAgent = agent;
        link.relationType = identity.relationType();
        link.confidence = link.confidence == null ? identity.confidence() : Math.max(link.confidence, identity.confidence());
        link.identityBasis = truncate(identity.basis(), 1024);
        link.sourceTypes = mergeSourceType(link.sourceTypes, item.sourceType());
        link.firstSeenAt = link.firstSeenAt == null ? now : link.firstSeenAt;
        link.lastSeenAt = now;
        identityLinkRepository.save(link);
    }

    private Identity identity(DiscoveryEvidence item) {
        Map<String, Object> evidence = item.evidence() == null ? Map.of() : item.evidence();
        String explicit = stringEvidence(evidence, "identityKey", "agentIdentity", "workloadUid", "agentUrl", "executablePath");
        String basis = explicit;
        if (basis == null || basis.isBlank()) {
            basis = switch (item.sourceType()) {
                case A2A -> item.sourceLocation();
                case MCP_REGISTRY -> item.name() + "|" + item.version();
                case KUBERNETES_WATCH, KUBERNETES_ADMISSION -> item.sourceLocation();
                case EBPF, HOST_OBSERVER -> item.executablePath() + "|" + stringEvidence(evidence,
                        "workloadUid", "containerId", "tlsServerName", "remoteAddress");
                default -> item.executablePath() == null ? item.sourceLocation() : item.executablePath();
            };
        }
        boolean exact = stringEvidence(evidence, "identityKey", "agentIdentity", "workloadUid", "agentUrl") != null;
        String normalized = exact
                ? normalize(basis) + "|" + normalize(item.hostName())
                : normalize(basis) + "|" + normalize(item.name()) + "|" + normalize(item.product())
                        + "|" + normalize(item.hostName());
        return new Identity(
                fingerprint("identity", normalized),
                basis == null ? normalized : basis,
                exact ? "EXACT" : "HEURISTIC",
                exact ? Math.max(90, item.confidence()) : Math.min(85, item.confidence())
        );
    }

    private String stringEvidence(Map<String, Object> evidence, String... keys) {
        for (String key : keys) {
            Object value = evidence.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private record Identity(String key, String basis, String relationType, int confidence) { }

    private DiscoveryEndpoint upsertEndpoint(AgentDiscoveryDtos.EndpointRegistrationRequest request) {
        DiscoveryEndpoint endpoint = endpointRepository.findByEndpointKey(request.endpointKey())
                .orElseGet(DiscoveryEndpoint::new);
        endpoint.endpointKey = request.endpointKey();
        endpoint.hostName = request.hostName();
        endpoint.accountName = prefer(request.accountName(), endpoint.accountName);
        endpoint.operatingSystem = prefer(request.operatingSystem(), endpoint.operatingSystem);
        endpoint.collectorVersion = prefer(request.collectorVersion(), endpoint.collectorVersion);
        endpoint.status = "ACTIVE";
        touchEndpoint(endpoint);
        return endpointRepository.save(endpoint);
    }

    private void touchEndpoint(DiscoveryEndpoint endpoint) {
        Instant now = Instant.now();
        endpoint.lastSeenAt = now;
        if (endpoint.firstSeenAt == null) {
            endpoint.firstSeenAt = now;
        }
    }

    private DiscoveryEvidence toEvidence(
            DiscoveryEndpoint endpoint,
            AgentDiscoveryDtos.EndpointEvidenceRequest item
    ) {
        RiskLevel derivedRisk = riskLevel(
                Boolean.TRUE.equals(item.autoApprove()),
                item.trustedProcess(),
                hasCredentialReference(item.evidence()),
                item.mcpServerCount(),
                item.sourceType()
        );
        Map<String, Object> details = item.evidence() == null ? Map.of() : item.evidence();
        return new DiscoveryEvidence(
                fingerprint("endpoint", endpoint.endpointKey, item.fingerprint()),
                item.name(),
                item.vendor(),
                item.product(),
                item.version(),
                item.assetType(),
                item.sourceType(),
                item.sourceLocation(),
                endpoint.hostName,
                prefer(item.accountName(), endpoint.accountName),
                item.processName(),
                item.executablePath(),
                item.autoApprove(),
                item.trustedProcess(),
                item.mcpServerCount(),
                item.confidence(),
                maxRisk(item.riskLevel(), derivedRisk),
                item.evidenceType(),
                details
        );
    }

    private boolean hasCredentialReference(Map<String, Object> evidence) {
        if (evidence == null) {
            return false;
        }
        return evidence.entrySet().stream()
                .anyMatch(entry -> entry.getKey() != null
                        && (entry.getKey().toLowerCase().contains("credential")
                        || entry.getKey().toLowerCase().contains("secret")
                        || entry.getKey().toLowerCase().contains("token"))
                        && Boolean.TRUE.equals(entry.getValue()));
    }

    private void saveObservation(Long runId, Long agentId, DiscoveryEvidence evidence) {
        DiscoveryObservation observation = new DiscoveryObservation();
        observation.runId = runId;
        observation.discoveredAgentId = agentId;
        observation.sourceType = evidence.sourceType();
        observation.sourceLocation = truncate(evidence.sourceLocation(), 1024);
        observation.evidenceType = truncate(evidence.evidenceType(), 128);
        observation.evidenceJson = toJson(evidence.evidence());
        observationRepository.save(observation);
    }

    private String toJson(Object value) {
        try {
            return truncate(objectMapper.writeValueAsString(sanitizeEvidence(value)), 32768);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private Object sanitizeEvidence(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new java.util.LinkedHashMap<>();
            map.entrySet().stream().limit(64).forEach(entry -> {
                String key = String.valueOf(entry.getKey());
                String normalized = key.toLowerCase();
                sanitized.put(key, normalized.contains("secret") || normalized.contains("token")
                        || normalized.contains("password") || normalized.contains("apikey") || normalized.contains("api_key")
                        ? "[REDACTED]" : sanitizeEvidence(entry.getValue()));
            });
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream().limit(64).map(this::sanitizeEvidence).toList();
        }
        return value;
    }

    private String mergeSourceType(String current, DiscoverySourceType sourceType) {
        Set<String> values = new LinkedHashSet<>();
        if (current != null && !current.isBlank()) {
            values.addAll(Arrays.asList(current.split(",")));
        }
        values.add(sourceType.name());
        return String.join(",", values);
    }

    private Boolean mergeTrust(Boolean current, Boolean next) {
        if (Boolean.FALSE.equals(current) || Boolean.FALSE.equals(next)) {
            return false;
        }
        if (Boolean.TRUE.equals(current) || Boolean.TRUE.equals(next)) {
            return true;
        }
        return null;
    }

    private RiskLevel maxRisk(RiskLevel current, RiskLevel next) {
        if (current == null) {
            return next;
        }
        return current.ordinal() >= next.ordinal() ? current : next;
    }

    private RiskLevel riskLevel(boolean autoApprove, Boolean trustedProcess, boolean credentialReference, int mcpServerCount, DiscoverySourceType sourceType) {
        if (autoApprove && Boolean.FALSE.equals(trustedProcess)) {
            return RiskLevel.CRITICAL;
        }
        if (autoApprove || Boolean.FALSE.equals(trustedProcess) || credentialReference) {
            return RiskLevel.HIGH;
        }
        if (sourceType == DiscoverySourceType.PROCESS || mcpServerCount > 0) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private String fingerprint(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String joined = String.join("|", Arrays.stream(values).map(value -> value == null ? "" : value).toList());
            return java.util.HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String prefer(String next, String current) {
        return next == null || next.isBlank() ? current : next;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
