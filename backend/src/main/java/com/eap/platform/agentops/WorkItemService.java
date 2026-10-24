package com.eap.platform.agentops;

import com.eap.platform.agent.Agent;
import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.AgentRecordOrigin;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.agent.RiskLevel;
import com.eap.platform.audit.AuditLog;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.common.TraceContext;
import com.eap.platform.config.SecurityProperties;
import com.eap.platform.evaluation.EvaluationResult;
import com.eap.platform.evaluation.EvaluationResultRepository;
import com.eap.platform.evaluation.EvaluationStatus;
import com.eap.platform.evaluation.EvaluatorDefinition;
import com.eap.platform.evaluation.EvaluatorDefinitionRepository;
import com.eap.platform.discovery.DiscoveredAgent;
import com.eap.platform.discovery.DiscoveredAgentRepository;
import com.eap.platform.discovery.DiscoveryAssetStatus;
import com.eap.platform.discovery.DiscoveryEndpoint;
import com.eap.platform.discovery.DiscoveryEndpointRepository;
import com.eap.platform.governance.GovernanceApprovalStatus;
import com.eap.platform.governance.GovernanceDataEvent;
import com.eap.platform.governance.GovernanceDataEventRepository;
import com.eap.platform.governance.GovernancePreflightDecision;
import com.eap.platform.governance.GovernancePreflightDecisionRepository;
import com.eap.platform.management.ManagementValueConnector;
import com.eap.platform.management.ManagementValueConnectorRepository;
import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.registry.RuntimeStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class WorkItemService {
    private static final int SIGNAL_BATCH_SIZE = 500;
    private static final Set<WorkItemStatus> OPEN_STATUSES = EnumSet.of(
            WorkItemStatus.OPEN, WorkItemStatus.CLAIMED, WorkItemStatus.IN_PROGRESS, WorkItemStatus.WAITING);
    private static final Set<WorkItemType> AUTO_SIGNAL_TYPES = EnumSet.of(
            WorkItemType.DISCOVERY_REVIEW, WorkItemType.OWNER_ASSIGNMENT, WorkItemType.IDENTITY_REVIEW,
            WorkItemType.PROBE_OFFLINE, WorkItemType.RUNTIME_DEGRADED, WorkItemType.RUNTIME_OFFLINE,
            WorkItemType.SECURITY_RISK, WorkItemType.GOVERNANCE_APPROVAL, WorkItemType.CONNECTOR_FAILURE,
            WorkItemType.OBSERVABILITY_GAP, WorkItemType.EVALUATION_FAILURE);

    private final AgentWorkItemRepository repository;
    private final AgentWorkItemEventRepository eventRepository;
    private final AgentRepository agentRepository;
    private final DiscoveredAgentRepository discoveredRepository;
    private final DiscoveryEndpointRepository endpointRepository;
    private final AgentRuntimeInstanceRepository runtimeRepository;
    private final GovernancePreflightDecisionRepository governanceDecisionRepository;
    private final GovernanceDataEventRepository governanceEventRepository;
    private final ManagementValueConnectorRepository connectorRepository;
    private final EvaluationResultRepository evaluationResultRepository;
    private final EvaluatorDefinitionRepository evaluatorDefinitionRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final SecurityProperties securityProperties;
    private final AgentOpsSignalSyncStateRepository syncStateRepository;
    private final com.eap.platform.registry.RuntimeLeaseProperties leaseProperties;

    public WorkItemService(
            AgentWorkItemRepository repository,
            AgentWorkItemEventRepository eventRepository,
            AgentRepository agentRepository,
            DiscoveredAgentRepository discoveredRepository,
            DiscoveryEndpointRepository endpointRepository,
            AgentRuntimeInstanceRepository runtimeRepository,
            GovernancePreflightDecisionRepository governanceDecisionRepository,
            GovernanceDataEventRepository governanceEventRepository,
            ManagementValueConnectorRepository connectorRepository,
            EvaluationResultRepository evaluationResultRepository,
            EvaluatorDefinitionRepository evaluatorDefinitionRepository,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            SecurityProperties securityProperties,
            AgentOpsSignalSyncStateRepository syncStateRepository,
            com.eap.platform.registry.RuntimeLeaseProperties leaseProperties
    ) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.agentRepository = agentRepository;
        this.discoveredRepository = discoveredRepository;
        this.endpointRepository = endpointRepository;
        this.runtimeRepository = runtimeRepository;
        this.governanceDecisionRepository = governanceDecisionRepository;
        this.governanceEventRepository = governanceEventRepository;
        this.connectorRepository = connectorRepository;
        this.evaluationResultRepository = evaluationResultRepository;
        this.evaluatorDefinitionRepository = evaluatorDefinitionRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.securityProperties = securityProperties;
        this.syncStateRepository = syncStateRepository;
        this.leaseProperties = leaseProperties;
    }

    @Transactional
    public int synchronizeSignals() {
        // Serialize manual/scheduled synchronization; publish freshness only with a successful commit.
        AgentOpsSignalSyncState syncState = syncStateRepository.lockState()
                .orElseThrow(() -> new IllegalStateException("AgentOps 同步状态尚未初始化"));
        Instant now = Instant.now();
        int[] created = {0};
        Set<String> detectedKeys = new HashSet<>();
        List<Agent> visibleAgents = agentRepository.findAll().stream().filter(this::visibleAgent).toList();
        Set<Long> visibleAgentIds = visibleAgents.stream().map(Agent::getId).collect(java.util.stream.Collectors.toSet());
        Set<Long> discoveryObservedAgentIds = new HashSet<>();
        Map<String, DiscoveryEndpoint> endpoints = new HashMap<>();
        endpointRepository.findAll().forEach(endpoint -> endpoints.put(endpoint.getEndpointKey(), endpoint));

        for (DiscoveredAgent asset : discoveredRepository.findAll()) {
            if (asset.getManagedAgentId() != null && !visibleAgentIds.contains(asset.getManagedAgentId())) continue;
            if (asset.getManagedAgentId() != null) discoveryObservedAgentIds.add(asset.getManagedAgentId());
            if (asset.getRiskLevel() == RiskLevel.HIGH || asset.getRiskLevel() == RiskLevel.CRITICAL) {
                created[0] += registerSignal(detectedKeys, new Signal(
                        "SECURITY:" + asset.getId(), WorkItemType.SECURITY_RISK,
                        "高风险发现资产：" + asset.getName(),
                        "资产已自动纳管；请跟进风险信号，告警不阻断纳管。",
                        severityForRisk(asset.getRiskLevel(), WorkItemSeverity.P1), asset.getManagedAgentId(),
                        asset.getLastEndpointKey(), null, "DISCOVERED_AGENT", String.valueOf(asset.getId()),
                        scope(endpoints.get(asset.getLastEndpointKey())), EvidenceBasis.MEASURED,
                        Map.of("riskLevel", asset.getRiskLevel().name(), "confidence", asset.getConfidence(),
                                "product", Objects.toString(asset.getProduct(), "")), now
                ));
            }
            if (asset.getManagedAgentId() != null && "OFFLINE".equals(asset.getAvailabilityStatus())) {
                created[0] += registerSignal(detectedKeys, new Signal(
                        "DISCOVERED_OFFLINE:" + asset.getId(), WorkItemType.RUNTIME_OFFLINE,
                        "发现的 Agent 已离线：" + asset.getName(),
                        "连续 3 次全量扫描未见；保留纳管记录，再次发现后自动恢复。",
                        WorkItemSeverity.P1, asset.getManagedAgentId(), asset.getLastEndpointKey(), null,
                        "DISCOVERED_AGENT", String.valueOf(asset.getId()),
                        scope(endpoints.get(asset.getLastEndpointKey())), EvidenceBasis.MEASURED,
                        Map.of("availabilityStatus", asset.getAvailabilityStatus(),
                                "lastSeenAt", Objects.toString(asset.getLastSeenAt(), "")), now
                ));
            }
            if (asset.getStatus() == DiscoveryAssetStatus.DISCOVERED || asset.getStatus() == DiscoveryAssetStatus.REVIEWING) {
                WorkItemSeverity severity = severityForRisk(asset.getRiskLevel(), WorkItemSeverity.P3);
                created[0] += registerSignal(detectedKeys, new Signal(
                        "DISCOVERY:" + asset.getId(), WorkItemType.DISCOVERY_REVIEW,
                        "复核发现资产：" + asset.getName(),
                        "确认资产身份、责任归属和是否纳入正式 Agent 目录。",
                        severity, asset.getManagedAgentId(), asset.getLastEndpointKey(), null,
                        "DISCOVERED_AGENT", String.valueOf(asset.getId()),
                        scope(endpoints.get(asset.getLastEndpointKey())), EvidenceBasis.DERIVED,
                        Map.of("status", asset.getStatus().name(), "confidence", asset.getConfidence(),
                                "product", Objects.toString(asset.getProduct(), ""), "lastSeenAt", asset.getLastSeenAt()), now
                ));
            }
        }

        Map<Long, List<AgentRuntimeInstance>> runtimesByAgent = new HashMap<>();
        for (AgentRuntimeInstance runtime : runtimeRepository.findAll()) {
            if (!visibleAgentIds.contains(runtime.getAgentId())) continue;
            runtimesByAgent.computeIfAbsent(runtime.getAgentId(), ignored -> new ArrayList<>()).add(runtime);
            RuntimeStatus effectiveStatus = leaseProperties.effectiveStatus(runtime, now.minusSeconds(leaseProperties.getHeartbeatTtlSeconds()));
            if (effectiveStatus == RuntimeStatus.DEGRADED || effectiveStatus == RuntimeStatus.OFFLINE) {
                boolean offline = effectiveStatus == RuntimeStatus.OFFLINE;
                created[0] += registerSignal(detectedKeys, new Signal(
                        "RUNTIME:" + runtime.getInstanceKey(),
                        offline ? WorkItemType.RUNTIME_OFFLINE : WorkItemType.RUNTIME_DEGRADED,
                        (offline ? "运行实例离线：" : "运行实例降级：") + runtime.getInstanceKey(),
                        "检查实例心跳、负载、网络和最近任务失败情况。",
                        offline ? WorkItemSeverity.P1 : WorkItemSeverity.P2,
                        runtime.getAgentId(), null, runtime.getInstanceKey(), "RUNTIME_INSTANCE", runtime.getInstanceKey(),
                        agentScope(runtime.getAgentId()), EvidenceBasis.MEASURED,
                        Map.of("status", effectiveStatus.name(), "endpoint", runtime.getEndpoint(),
                                "lastHeartbeatAt", Objects.toString(runtime.getLastHeartbeatAt(), "")), now
                ));
            }
        }

        for (Agent agent : visibleAgents) {
            if (isUnassigned(agent)) {
                created[0] += registerSignal(detectedKeys, new Signal(
                        "OWNER:" + agent.getId(), WorkItemType.OWNER_ASSIGNMENT,
                        "为 Agent 指定责任人：" + agent.getName(),
                        "正式 Agent 尚未完成业务责任人与部门认领。",
                        WorkItemSeverity.P3, agent.getId(), null, null, "AGENT", String.valueOf(agent.getId()),
                        agent.getOwnerDepartment(), EvidenceBasis.HUMAN_CONFIRMED,
                        Map.of("ownerDepartment", Objects.toString(agent.getOwnerDepartment(), ""),
                                "ownerUser", Objects.toString(agent.getOwnerUser(), "")), now
                ));
            }
            if (agent.getLifecycleStatus() == AgentLifecycleStatus.ACTIVE
                    && runtimesByAgent.getOrDefault(agent.getId(), List.of()).isEmpty()
                    && !discoveryObservedAgentIds.contains(agent.getId())) {
                created[0] += registerSignal(detectedKeys, new Signal(
                        "OBSERVABILITY:" + agent.getId(), WorkItemType.OBSERVABILITY_GAP,
                        "补齐 Agent 运行观测：" + agent.getName(),
                        "Agent 已启用，但尚未关联任何运行实例心跳。",
                        WorkItemSeverity.P2, agent.getId(), null, null, "AGENT", String.valueOf(agent.getId()),
                        agent.getOwnerDepartment(), EvidenceBasis.DERIVED,
                        Map.of("lifecycleStatus", agent.getLifecycleStatus().name(), "runtimeCount", 0), now
                ));
            }
        }

        for (DiscoveryEndpoint endpoint : endpointRepository.findAll()) {
            if (endpoint.isFingerprintReviewRequired() || "PENDING_REVIEW".equals(endpoint.getStatus())) {
                created[0] += registerSignal(detectedKeys, new Signal(
                        "FINGERPRINT:" + endpoint.getEndpointKey(), WorkItemType.IDENTITY_REVIEW,
                        "复核探针机器指纹：" + endpoint.getHostName(),
                        "机器指纹发生变化，需确认主机身份后恢复可信采集。",
                        WorkItemSeverity.P1, null, endpoint.getEndpointKey(), null,
                        "DISCOVERY_ENDPOINT", endpoint.getEndpointKey(), scope(endpoint), EvidenceBasis.MEASURED,
                        Map.of("status", endpoint.getStatus(), "lastSeenAt", endpoint.getLastSeenAt()), now
                ));
            } else if ("OFFLINE".equals(endpoint.getStatus())) {
                created[0] += registerSignal(detectedKeys, new Signal(
                        "PROBE:" + endpoint.getEndpointKey(), WorkItemType.PROBE_OFFLINE,
                        "发现探针离线：" + endpoint.getHostName(),
                        "检查探针服务、网络、机器凭证和离线队列。",
                        WorkItemSeverity.P1, null, endpoint.getEndpointKey(), null,
                        "DISCOVERY_ENDPOINT", endpoint.getEndpointKey(), scope(endpoint), EvidenceBasis.MEASURED,
                        Map.of("status", endpoint.getStatus(), "lastSeenAt", endpoint.getLastSeenAt(),
                                "lastError", Objects.toString(endpoint.getLastError(), "")), now
                ));
            }
        }

        // Freeze each cycle's ceiling; new arrivals cannot starve updates to older records.
        if (syncState.approvalUpperBound == 0) syncState.approvalUpperBound = governanceDecisionRepository.signalUpperBound();
        List<GovernancePreflightDecision> approvals = governanceDecisionRepository.scanSignals(
                GovernanceApprovalStatus.PENDING, syncState.approvalCursor, syncState.approvalUpperBound,
                org.springframework.data.domain.PageRequest.of(0, SIGNAL_BATCH_SIZE));
        for (GovernancePreflightDecision decision : approvals) {
            syncState.approvalCursor = decision.getId();
            GovernanceDataEvent event = governanceEventRepository.findById(decision.getDataEventId()).orElse(null);
            if (event != null && event.getAgentId() != null && !visibleAgentIds.contains(event.getAgentId())) continue;
            WorkItemSeverity severity = event != null && event.isCompanySecret() ? WorkItemSeverity.P0 : WorkItemSeverity.P1;
            created[0] += registerSignal(detectedKeys, new Signal(
                    "GOVERNANCE:" + decision.getDecisionKey(), WorkItemType.GOVERNANCE_APPROVAL,
                    "处理模型调用治理审批",
                    "核对数据分类、策略命中和业务必要性后决定放行或拒绝。",
                    severity, event == null ? null : event.getAgentId(), event == null ? null : event.getEndpointKey(), null,
                    "GOVERNANCE_DECISION", decision.getDecisionKey(),
                    event == null ? null : endpointScope(event.getEndpointKey()), EvidenceBasis.MEASURED,
                    Map.of("decisionKey", decision.getDecisionKey(), "provider", Objects.toString(decision.getProvider(), ""),
                            "model", Objects.toString(decision.getModelName(), ""),
                            "companySecret", event != null && event.isCompanySecret()), now
            ));
        }
        if (approvals.size() < SIGNAL_BATCH_SIZE || syncState.approvalCursor >= syncState.approvalUpperBound) {
            syncState.approvalCursor = 0;
            syncState.approvalUpperBound = 0;
        }

        for (ManagementValueConnector connector : connectorRepository.findAll()) {
            if (!Boolean.TRUE.equals(connector.getEnabled())) continue;
            if (connector.getDefaultAgentId() != null && !visibleAgentIds.contains(connector.getDefaultAgentId())) continue;
            if ("ERROR".equals(connector.getStatus()) || "DEGRADED".equals(connector.getStatus())) {
                created[0] += registerSignal(detectedKeys, new Signal(
                        "CONNECTOR:" + connector.getConnectorKey(), WorkItemType.CONNECTOR_FAILURE,
                        "修复业务连接器：" + connector.getName(),
                        Objects.toString(connector.getLastError(), "业务价值连接器同步失败。"),
                        WorkItemSeverity.P2, connector.getDefaultAgentId(), connector.getDefaultEndpointKey(), null,
                        "MANAGEMENT_CONNECTOR", connector.getConnectorKey(),
                        connector.getDefaultAgentId() == null ? endpointScope(connector.getDefaultEndpointKey()) : agentScope(connector.getDefaultAgentId()),
                        EvidenceBasis.EXTERNAL_RECONCILED,
                        Map.of("sourceSystem", connector.getSourceSystem(), "status", connector.getStatus(),
                                "lastSyncCompletedAt", Objects.toString(connector.getLastSyncCompletedAt(), "")), now
                ));
            }
        }

        if (syncState.evaluationUpperBound == 0) syncState.evaluationUpperBound = evaluationResultRepository.signalUpperBound();
        List<EvaluationResult> evaluationBatch = evaluationResultRepository.scanSignals(
                EvaluationStatus.COMPLETED, syncState.evaluationCursor, syncState.evaluationUpperBound,
                org.springframework.data.domain.PageRequest.of(0, SIGNAL_BATCH_SIZE));
        if (!evaluationBatch.isEmpty()) syncState.evaluationCursor = evaluationBatch.get(evaluationBatch.size() - 1).getId();
        List<EvaluationResult> failedEvaluations = evaluationBatch.stream()
                .filter(item -> item.getAgentId() == null || visibleAgentIds.contains(item.getAgentId()))
                .toList();
        Map<Long, EvaluatorDefinition> evaluatorById = evaluatorDefinitionRepository.findAllById(
                        failedEvaluations.stream().map(EvaluationResult::getEvaluatorId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(EvaluatorDefinition::getId, item -> item));
        for (EvaluationResult result : failedEvaluations) {
            EvaluatorDefinition evaluator = evaluatorById.get(result.getEvaluatorId());
            if (evaluator == null) continue;
            String target = blank(result.getTraceId()) ? String.valueOf(result.getId()) : result.getTraceId();
            String itemKey = "EVALUATION:" + evaluator.getEvaluatorKey() + ":" + target;
            // A cycle revisits old failures. Do not replace newer evidence for the same trace.
            AgentWorkItem previous = repository.findByItemKey(itemKey).orElse(null);
            if (previous != null && !Objects.equals(previous.sourceId, result.getResultKey())) {
                EvaluationResult priorResult = evaluationResultRepository.findByResultKey(previous.sourceId).orElse(null);
                if (priorResult != null && (priorResult.getEvaluatedAt().isAfter(result.getEvaluatedAt())
                        || priorResult.getEvaluatedAt().equals(result.getEvaluatedAt())
                        && priorResult.getId() > result.getId())) {
                    detectedKeys.add(itemKey);
                    continue;
                }
            }
            WorkItemSeverity severity = evaluationSeverity(evaluator.getEvaluatorKey());
            created[0] += registerSignal(detectedKeys, new Signal(
                    itemKey,
                    WorkItemType.EVALUATION_FAILURE,
                    "评估未通过：" + evaluator.getName(),
                    blank(result.getRationale()) ? "请检查关联 Trace、Span 和 Agent 运行轨迹。" : result.getRationale(),
                    severity, result.getAgentId(), null, null, "EVALUATION_RESULT", result.getResultKey(),
                    result.getAgentId() == null ? null : agentScope(result.getAgentId()), EvidenceBasis.DERIVED,
                    Map.of("evaluatorKey", evaluator.getEvaluatorKey(), "evaluatorVersion", result.getEvaluatorVersion(),
                            "score", result.getScore(), "confidence", result.getConfidence(),
                            "traceId", Objects.toString(result.getTraceId(), ""),
                            "spanId", Objects.toString(result.getSpanId(), ""),
                            "rationale", Objects.toString(result.getRationale(), "")), now
            ));
        }
        if (evaluationBatch.size() < SIGNAL_BATCH_SIZE || syncState.evaluationCursor >= syncState.evaluationUpperBound) {
            syncState.evaluationCursor = 0;
            syncState.evaluationUpperBound = 0;
        }
        resolveClearedSignals(detectedKeys, now);
        syncState.lastCompletedAt = Instant.now();
        syncState.lastCreatedCount = created[0];
        return created[0];
    }

    @Transactional(readOnly = true)
    public List<AgentOpsDtos.WorkItemResponse> list(
            WorkItemStatus status,
            WorkItemSeverity severity,
            Long agentId,
            String organizationScope,
            int limit
    ) {
        Instant now = Instant.now();
        org.springframework.data.jpa.domain.Specification<AgentWorkItem> specification = (root, criteria, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (status != null) predicates.add(builder.equal(root.get("status"), status));
            if (severity != null) predicates.add(builder.equal(root.get("severity"), severity));
            if (agentId != null) predicates.add(builder.equal(root.get("agentId"), agentId));
            if (!blank(organizationScope)) {
                String escaped = organizationScope.trim().toLowerCase(java.util.Locale.ROOT)
                        .replace("!", "!!").replace("%", "!%").replace("_", "!_");
                predicates.add(builder.like(builder.lower(root.get("organizationScope")), "%" + escaped + "%", '!'));
            }
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        return repository.findAll(specification, org.springframework.data.domain.PageRequest.of(0,
                        Math.max(1, Math.min(limit, 500)), org.springframework.data.domain.Sort.by("severity", "slaDueAt", "id")))
                .stream()
                .map(item -> AgentOpsDtos.from(item, now))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AgentOpsDtos.WorkItemEventResponse> events(Long workItemId) {
        if (!repository.existsById(workItemId)) throw new BusinessException("运营事项不存在");
        return eventRepository.findByWorkItemIdOrderByCreatedAtAsc(workItemId).stream()
                .map(AgentOpsDtos::from)
                .toList();
    }

    @Transactional
    public AgentOpsDtos.WorkItemResponse transition(Long id, AgentOpsDtos.WorkItemTransitionRequest request) {
        AgentWorkItem item = repository.findById(id).orElseThrow(() -> new BusinessException("运营事项不存在"));
        WorkItemStatus before = item.status;
        validateTransition(item, request);
        String actor = currentActor(request.actor());
        item.status = request.status();
        if (!blank(request.assignee())) item.assignee = request.assignee().trim();
        if (!blank(request.assignedTeam())) item.assignedTeam = request.assignedTeam().trim();
        if (request.status() == WorkItemStatus.RESOLVED) {
            item.resolution = request.reason().trim();
            item.resolutionEvidenceJson = blankToNull(request.resolutionEvidenceJson());
            item.resolvedAt = Instant.now();
        } else if (request.status() == WorkItemStatus.CLOSED) {
            item.closedAt = Instant.now();
        }
        // Detect a stale transition before inserting events whose FK would lock the same item.
        // The item, event and audit remain one transaction; a conflict never retries the action.
        repository.saveAndFlush(item);
        appendEvent(item, "STATUS_CHANGED", actor, before, item.status, request.reason(), request.resolutionEvidenceJson());
        writeAudit(item, actor, before, item.status, request.reason());
        return AgentOpsDtos.from(item, Instant.now());
    }

    private int registerSignal(Set<String> detectedKeys, Signal signal) {
        detectedKeys.add(signal.itemKey());
        AgentWorkItem existing = repository.findByItemKey(signal.itemKey()).orElse(null);
        if (existing != null) {
            WorkItemStatus before = existing.status;
            existing.lastDetectedAt = signal.detectedAt();
            existing.itemType = signal.itemType();
            existing.title = signal.title();
            existing.description = signal.description();
            existing.agentId = signal.agentId();
            existing.endpointKey = signal.endpointKey();
            existing.runtimeInstanceKey = signal.runtimeInstanceKey();
            existing.sourceType = signal.sourceType();
            existing.sourceId = signal.sourceId();
            existing.evidenceJson = json(signal.evidence());
            existing.evidenceBasis = signal.evidenceBasis();
            existing.organizationScope = signal.organizationScope();
            if (signal.severity().ordinal() < existing.severity.ordinal()) existing.severity = signal.severity();
            if (existing.status == WorkItemStatus.RESOLVED || existing.status == WorkItemStatus.CLOSED) {
                existing.status = WorkItemStatus.OPEN;
                existing.occurrenceCount = (existing.occurrenceCount == null ? 1 : existing.occurrenceCount) + 1;
                existing.slaDueAt = signal.detectedAt().plus(sla(existing.severity));
                existing.resolution = null;
                existing.resolutionEvidenceJson = null;
                existing.resolvedAt = null;
                existing.closedAt = null;
                repository.save(existing);
                appendEvent(existing, "REOPENED_FROM_SIGNAL", "system", before, WorkItemStatus.OPEN,
                        "源信号再次出现", existing.evidenceJson);
                writeAudit(existing, "system", before, WorkItemStatus.OPEN, "源信号再次出现");
                return 0;
            }
            repository.save(existing);
            return 0;
        }
        AgentWorkItem item = new AgentWorkItem();
        item.itemKey = signal.itemKey();
        item.itemType = signal.itemType();
        item.title = signal.title();
        item.description = signal.description();
        item.severity = signal.severity();
        item.status = WorkItemStatus.OPEN;
        item.agentId = signal.agentId();
        item.endpointKey = signal.endpointKey();
        item.runtimeInstanceKey = signal.runtimeInstanceKey();
        item.sourceType = signal.sourceType();
        item.sourceId = signal.sourceId();
        item.organizationScope = signal.organizationScope();
        item.evidenceBasis = signal.evidenceBasis();
        item.evidenceJson = json(signal.evidence());
        item.firstDetectedAt = signal.detectedAt();
        item.lastDetectedAt = signal.detectedAt();
        item.slaDueAt = signal.detectedAt().plus(sla(signal.severity()));
        item = repository.save(item);
        appendEvent(item, "CREATED_FROM_SIGNAL", "system", null, WorkItemStatus.OPEN,
                "由 " + signal.sourceType() + " 信号创建", item.evidenceJson);
        return 1;
    }

    private void resolveClearedSignals(Set<String> detectedKeys, Instant now) {
        repository.findByStatusIn(List.copyOf(OPEN_STATUSES)).stream()
                .filter(item -> AUTO_SIGNAL_TYPES.contains(item.itemType))
                .filter(item -> OPEN_STATUSES.contains(item.status))
                .filter(item -> !detectedKeys.contains(item.itemKey))
                .filter(item -> !hasActiveSourceOutsideWindow(item))
                .forEach(item -> {
                    WorkItemStatus before = item.status;
                    item.status = WorkItemStatus.RESOLVED;
                    item.resolution = "源信号已恢复或不再存在";
                    item.resolvedAt = now;
                    repository.save(item);
                    appendEvent(item, "AUTO_RESOLVED_SIGNAL_CLEARED", "system", before, WorkItemStatus.RESOLVED,
                            item.resolution, null);
                    writeAudit(item, "system", before, WorkItemStatus.RESOLVED, item.resolution);
                });
    }

    private boolean hasActiveSourceOutsideWindow(AgentWorkItem item) {
        // Falling outside a bounded scan is not evidence that the source recovered.
        if (item.itemType == WorkItemType.GOVERNANCE_APPROVAL) {
            return governanceDecisionRepository.findByDecisionKey(item.sourceId)
                    .map(decision -> decision.getApprovalStatus() == GovernanceApprovalStatus.PENDING)
                    .orElse(false);
        }
        if (item.itemType == WorkItemType.EVALUATION_FAILURE) {
            return evaluationResultRepository.findByResultKey(item.sourceId)
                    .map(result -> result.getStatus() == EvaluationStatus.COMPLETED && !result.isPassed())
                    .orElse(false);
        }
        return false;
    }

    private void validateTransition(AgentWorkItem item, AgentOpsDtos.WorkItemTransitionRequest request) {
        if (item.status == WorkItemStatus.CLOSED) throw new BusinessException("已关闭事项不能再次变更");
        Set<WorkItemStatus> allowed = switch (item.status) {
            case OPEN -> EnumSet.of(WorkItemStatus.CLAIMED, WorkItemStatus.IN_PROGRESS);
            case CLAIMED -> EnumSet.of(WorkItemStatus.IN_PROGRESS, WorkItemStatus.WAITING, WorkItemStatus.RESOLVED);
            case IN_PROGRESS -> EnumSet.of(WorkItemStatus.WAITING, WorkItemStatus.RESOLVED);
            case WAITING -> EnumSet.of(WorkItemStatus.IN_PROGRESS, WorkItemStatus.RESOLVED);
            case RESOLVED -> EnumSet.of(WorkItemStatus.CLOSED);
            case CLOSED -> EnumSet.noneOf(WorkItemStatus.class);
        };
        if (!allowed.contains(request.status())) {
            throw new BusinessException("不允许从 " + item.status + " 直接变更为 " + request.status());
        }
        if ((request.status() == WorkItemStatus.CLAIMED || request.status() == WorkItemStatus.IN_PROGRESS)
                && blank(request.assignee()) && blank(item.assignee)) {
            throw new BusinessException("认领或处理中事项必须指定负责人");
        }
        if (EnumSet.of(WorkItemStatus.WAITING, WorkItemStatus.RESOLVED, WorkItemStatus.CLOSED).contains(request.status())
                && blank(request.reason())) {
            throw new BusinessException("等待、解决或关闭事项必须填写原因");
        }
        if (request.status() == WorkItemStatus.CLOSED && item.status != WorkItemStatus.RESOLVED) {
            throw new BusinessException("事项必须先解决再关闭");
        }
    }

    private void appendEvent(
            AgentWorkItem item,
            String eventType,
            String actor,
            WorkItemStatus from,
            WorkItemStatus to,
            String note,
            String evidence
    ) {
        AgentWorkItemEvent event = new AgentWorkItemEvent();
        event.workItemId = item.id;
        event.eventType = eventType;
        event.actor = actor;
        event.fromStatus = from == null ? null : from.name();
        event.toStatus = to == null ? null : to.name();
        event.note = blankToNull(note);
        event.evidenceJson = blankToNull(evidence);
        eventRepository.save(event);
    }

    private void writeAudit(AgentWorkItem item, String actor, WorkItemStatus before, WorkItemStatus after, String reason) {
        AuditLog audit = new AuditLog();
        audit.setActor(actor);
        audit.setAction("TRANSITION_AGENTOPS_WORK_ITEM");
        audit.setResourceType("AGENT_WORK_ITEM");
        audit.setResourceId(String.valueOf(item.id));
        audit.setBeforeJson(json(Map.of("status", before.name())));
        audit.setAfterJson(json(Map.of("status", after.name(), "reason", Objects.toString(reason, ""))));
        audit.setResult("SUCCESS");
        audit.setTraceId(TraceContext.traceId());
        audit.setCreatedAt(Instant.now());
        auditLogRepository.save(audit);
    }

    private WorkItemSeverity severityForRisk(RiskLevel risk, WorkItemSeverity fallback) {
        if (risk == RiskLevel.CRITICAL) return WorkItemSeverity.P0;
        if (risk == RiskLevel.HIGH) return WorkItemSeverity.P1;
        if (risk == RiskLevel.MEDIUM) return WorkItemSeverity.P2;
        return fallback;
    }

    private WorkItemSeverity evaluationSeverity(String evaluatorKey) {
        String key = evaluatorKey == null ? "" : evaluatorKey.toUpperCase();
        return key.contains("PII") || key.contains("INJECTION") || key.contains("SENSITIVE") || key.contains("UNSAFE")
                ? WorkItemSeverity.P1 : WorkItemSeverity.P2;
    }

    private Duration sla(WorkItemSeverity severity) {
        return switch (severity) {
            case P0 -> Duration.ofMinutes(15);
            case P1 -> Duration.ofMinutes(30);
            case P2 -> Duration.ofHours(4);
            case P3 -> Duration.ofDays(1);
        };
    }

    private String currentActor(String fallback) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) return authentication.getName();
        return blank(fallback) ? "local-operator" : fallback.trim();
    }

    private boolean isUnassigned(Agent agent) {
        return blank(agent.getOwnerUser()) || "待认领".equals(agent.getOwnerUser())
                || blank(agent.getOwnerDepartment()) || "待认领".equals(agent.getOwnerDepartment());
    }

    private String agentScope(Long agentId) {
        if (agentId == null) return null;
        return agentRepository.findById(agentId).map(Agent::getOwnerDepartment).orElse(null);
    }

    private String endpointScope(String endpointKey) {
        if (blank(endpointKey)) return null;
        return endpointRepository.findByEndpointKey(endpointKey).map(this::scope).orElse(null);
    }

    private String scope(DiscoveryEndpoint endpoint) {
        if (endpoint == null) return null;
        return Stream.of(endpoint.getDepartment(), endpoint.getSiteName(), endpoint.getEnvironmentName())
                .filter(value -> !blank(value))
                .reduce((left, right) -> left + " / " + right)
                .orElse(null);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }

    private boolean containsIgnoreCase(String value, String expected) {
        return value != null && value.toLowerCase().contains(expected.trim().toLowerCase());
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String blankToNull(String value) { return blank(value) ? null : value.trim(); }

    private boolean visibleAgent(Agent agent) {
        return securityProperties.isDevelopmentMode() || agent.getRecordOrigin() != AgentRecordOrigin.DEMO;
    }

    private record Signal(
            String itemKey,
            WorkItemType itemType,
            String title,
            String description,
            WorkItemSeverity severity,
            Long agentId,
            String endpointKey,
            String runtimeInstanceKey,
            String sourceType,
            String sourceId,
            String organizationScope,
            EvidenceBasis evidenceBasis,
            Map<String, Object> evidence,
            Instant detectedAt
    ) {}
}
