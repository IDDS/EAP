package com.eap.platform.agentops;

import com.eap.platform.agent.Agent;
import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.agent.AgentRecordOrigin;
import com.eap.platform.agent.RiskLevel;
import com.eap.platform.common.BusinessException;
import com.eap.platform.config.SecurityProperties;
import com.eap.platform.discovery.DiscoveredAgent;
import com.eap.platform.discovery.DiscoveredAgentRepository;
import com.eap.platform.governance.GovernanceDataAction;
import com.eap.platform.governance.GovernanceDataEvent;
import com.eap.platform.governance.GovernanceDataEventRepository;
import com.eap.platform.knowledge.KnowledgeInteraction;
import com.eap.platform.knowledge.KnowledgeInteractionRepository;
import com.eap.platform.observability.ContentObservation;
import com.eap.platform.observability.ContentObservationRepository;
import com.eap.platform.observability.ToolInvocationEvent;
import com.eap.platform.observability.ToolInvocationEventRepository;
import com.eap.platform.observability.TraceSpanObservation;
import com.eap.platform.observability.TraceSpanObservationRepository;
import com.eap.platform.operations.AgentTask;
import com.eap.platform.operations.AgentTaskRepository;
import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.registry.RuntimeStatus;
import com.eap.platform.telemetry.ModelInvocationEvent;
import com.eap.platform.telemetry.ModelInvocationEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AgentOpsService {
    private static final List<WorkItemStatus> OPEN_STATUSES = List.of(
            WorkItemStatus.OPEN, WorkItemStatus.CLAIMED, WorkItemStatus.IN_PROGRESS, WorkItemStatus.WAITING);

    private final AgentRepository agentRepository;
    private final AgentRuntimeInstanceRepository runtimeRepository;
    private final DiscoveredAgentRepository discoveredRepository;
    private final ModelInvocationEventRepository modelRepository;
    private final TraceSpanObservationRepository spanRepository;
    private final ToolInvocationEventRepository toolRepository;
    private final ContentObservationRepository contentRepository;
    private final GovernanceDataEventRepository governanceRepository;
    private final KnowledgeInteractionRepository knowledgeRepository;
    private final AgentTaskRepository taskRepository;
    private final AgentWorkItemRepository workItemRepository;
    private final WorkItemService workItemService;
    private final SecurityProperties securityProperties;

    public AgentOpsService(
            AgentRepository agentRepository,
            AgentRuntimeInstanceRepository runtimeRepository,
            DiscoveredAgentRepository discoveredRepository,
            ModelInvocationEventRepository modelRepository,
            TraceSpanObservationRepository spanRepository,
            ToolInvocationEventRepository toolRepository,
            ContentObservationRepository contentRepository,
            GovernanceDataEventRepository governanceRepository,
            KnowledgeInteractionRepository knowledgeRepository,
            AgentTaskRepository taskRepository,
            AgentWorkItemRepository workItemRepository,
            WorkItemService workItemService,
            SecurityProperties securityProperties
    ) {
        this.agentRepository = agentRepository;
        this.runtimeRepository = runtimeRepository;
        this.discoveredRepository = discoveredRepository;
        this.modelRepository = modelRepository;
        this.spanRepository = spanRepository;
        this.toolRepository = toolRepository;
        this.contentRepository = contentRepository;
        this.governanceRepository = governanceRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.taskRepository = taskRepository;
        this.workItemRepository = workItemRepository;
        this.workItemService = workItemService;
        this.securityProperties = securityProperties;
    }

    @Transactional
    public AgentOpsDtos.DashboardResponse dashboard(int requestedHours, String organizationScope) {
        int hours = clampHours(requestedHours);
        workItemService.synchronizeSignals();
        Snapshot snapshot = snapshot(hours);
        List<AgentOpsDtos.AgentStateResponse> states = buildStates(snapshot).stream()
                .filter(state -> blank(organizationScope) || contains(state.ownerDepartment(), organizationScope))
                .toList();
        List<AgentWorkItem> openItems = snapshot.openWorkItems().stream()
                .filter(item -> blank(organizationScope) || contains(item.organizationScope, organizationScope))
                .sorted(workItemComparator())
                .toList();
        Instant now = snapshot.now();
        Instant latestMeasuredAt = states.stream().map(AgentOpsDtos.AgentStateResponse::latestObservedAt)
                .filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
        boolean demoDataIncluded = states.stream().anyMatch(state -> state.statusBasis() == EvidenceBasis.DEMO);
        String agentSource = demoDataIncluded ? "Agent 目录（含开发演示数据）" : "Agent 目录";
        String runtimeSource = demoDataIncluded ? "Runtime 心跳（含开发演示数据）" : "Runtime 心跳";

        long p0 = countSeverity(openItems, WorkItemSeverity.P0);
        long p1 = countSeverity(openItems, WorkItemSeverity.P1);
        long p2 = countSeverity(openItems, WorkItemSeverity.P2);
        long p3 = countSeverity(openItems, WorkItemSeverity.P3);
        long overdue = openItems.stream().filter(item -> item.slaDueAt != null && item.slaDueAt.isBefore(now)).count();
        List<AgentOpsDtos.MetricEvidence> metrics = List.of(
                metric("agents.total", "正式 Agent", states.size(), demoDataIncluded ? EvidenceBasis.DEMO : EvidenceBasis.HUMAN_CONFIRMED,
                        agentSource, latestUpdated(states), freshness(latestUpdated(states), now)),
                metric("agents.healthy", "运行健康", countHealth(states, OperationalHealth.HEALTHY),
                        demoDataIncluded ? EvidenceBasis.DEMO : EvidenceBasis.MEASURED,
                        runtimeSource, latestMeasuredAt, freshness(latestMeasuredAt, now)),
                metric("workitems.open", "待处理事项", openItems.size(), EvidenceBasis.DERIVED,
                        "AgentOps 信号聚合", now, "FRESH"),
                metric("agents.coverage_missing", "观测缺失", countCoverage(states, ObservationCoverage.MISSING),
                        demoDataIncluded ? EvidenceBasis.DEMO : EvidenceBasis.DERIVED,
                        "心跳与遥测覆盖计算", latestMeasuredAt, freshness(latestMeasuredAt, now))
        );

        List<AgentOpsDtos.AgentStateResponse> attentionAgents = states.stream()
                .filter(state -> state.riskPosture() != RiskPosture.NORMAL
                        || state.operationalHealth() != OperationalHealth.HEALTHY
                        || state.observationCoverage() != ObservationCoverage.FULL)
                .sorted(Comparator
                        .comparingInt((AgentOpsDtos.AgentStateResponse state) -> state.riskPosture().ordinal()).reversed()
                        .thenComparing(Comparator.comparingInt(
                                (AgentOpsDtos.AgentStateResponse state) -> state.operationalHealth().ordinal()).reversed())
                        .thenComparing(AgentOpsDtos.AgentStateResponse::openWorkItemCount, Comparator.reverseOrder()))
                .limit(8)
                .toList();

        return new AgentOpsDtos.DashboardResponse(
                hours, snapshot.windowStart(), now, ZoneId.systemDefault().getId(), blank(organizationScope) ? "企业全局" : organizationScope,
                states.size(), countHealth(states, OperationalHealth.HEALTHY), countHealth(states, OperationalHealth.DEGRADED),
                countHealth(states, OperationalHealth.OFFLINE), countCoverage(states, ObservationCoverage.MISSING),
                states.stream().filter(state -> state.riskPosture() == RiskPosture.HIGH || state.riskPosture() == RiskPosture.CRITICAL).count(),
                new AgentOpsDtos.WorkItemCounts(openItems.size(), p0, p1, p2, p3, overdue), metrics,
                openItems.stream().limit(12).map(item -> AgentOpsDtos.from(item, now)).toList(),
                attentionAgents, demoDataIncluded, now
        );
    }

    @Transactional(readOnly = true)
    public AgentOpsDtos.PagedAgentResponse listAgents(
            int requestedHours,
            int requestedPage,
            int requestedSize,
            String query,
            String department,
            AgentLifecycleStatus lifecycleStatus,
            OperationalHealth health,
            ObservationCoverage coverage,
            RiskPosture risk
    ) {
        int page = Math.max(0, requestedPage);
        int size = Math.max(1, Math.min(requestedSize, 100));
        List<AgentOpsDtos.AgentStateResponse> filtered = buildStates(snapshot(clampHours(requestedHours))).stream()
                .filter(state -> blank(query) || matchesQuery(state, query))
                .filter(state -> blank(department) || contains(state.ownerDepartment(), department))
                .filter(state -> lifecycleStatus == null || state.lifecycleStatus() == lifecycleStatus)
                .filter(state -> health == null || state.operationalHealth() == health)
                .filter(state -> coverage == null || state.observationCoverage() == coverage)
                .filter(state -> risk == null || state.riskPosture() == risk)
                .sorted(Comparator.comparing(AgentOpsDtos.AgentStateResponse::riskPosture).reversed()
                        .thenComparing(AgentOpsDtos.AgentStateResponse::name))
                .toList();
        int from = Math.min(filtered.size(), page * size);
        int to = Math.min(filtered.size(), from + size);
        int totalPages = filtered.isEmpty() ? 0 : (filtered.size() + size - 1) / size;
        return new AgentOpsDtos.PagedAgentResponse(filtered.subList(from, to), page, size, filtered.size(), totalPages);
    }

    @Transactional(readOnly = true)
    public AgentOpsDtos.AgentStateResponse getAgent(Long id, int requestedHours) {
        return buildStates(snapshot(clampHours(requestedHours))).stream()
                .filter(state -> Objects.equals(state.id(), id))
                .findFirst()
                .orElseThrow(() -> new BusinessException("智能体不存在"));
    }

    private Snapshot snapshot(int hours) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofHours(hours));
        return new Snapshot(
                now, windowStart,
                agentRepository.findAll().stream().filter(this::visibleAgent).toList(),
                runtimeRepository.findAll(), discoveredRepository.findAll(),
                modelRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(windowStart),
                spanRepository.findTop500ByOrderByObservedAtDesc().stream().filter(item -> !item.getObservedAt().isBefore(windowStart)).toList(),
                toolRepository.findTop500ByOrderByObservedAtDesc().stream().filter(item -> !item.getObservedAt().isBefore(windowStart)).toList(),
                contentRepository.findTop500ByOrderByObservedAtDesc().stream().filter(item -> !item.getObservedAt().isBefore(windowStart)).toList(),
                governanceRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(windowStart),
                knowledgeRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(windowStart),
                taskRepository.findAll().stream().filter(item -> item.getUpdatedAt() != null && !item.getUpdatedAt().isBefore(windowStart)).toList(),
                workItemRepository.findAll().stream().filter(item -> OPEN_STATUSES.contains(item.status)).toList()
        );
    }

    private List<AgentOpsDtos.AgentStateResponse> buildStates(Snapshot snapshot) {
        Map<Long, List<AgentRuntimeInstance>> runtimes = group(snapshot.runtimes(), AgentRuntimeInstance::getAgentId);
        Map<Long, List<DiscoveredAgent>> discoveries = group(snapshot.discoveries().stream()
                .filter(item -> item.getManagedAgentId() != null).toList(), DiscoveredAgent::getManagedAgentId);
        Map<Long, List<ModelInvocationEvent>> models = group(snapshot.models().stream()
                .filter(item -> item.getAgentId() != null).toList(), ModelInvocationEvent::getAgentId);
        Map<Long, List<TraceSpanObservation>> spans = group(snapshot.spans().stream()
                .filter(item -> item.getAgentId() != null).toList(), TraceSpanObservation::getAgentId);
        Map<Long, List<ToolInvocationEvent>> tools = group(snapshot.tools().stream()
                .filter(item -> item.getAgentId() != null).toList(), ToolInvocationEvent::getAgentId);
        Map<Long, List<ContentObservation>> content = group(snapshot.content().stream()
                .filter(item -> item.getAgentId() != null).toList(), ContentObservation::getAgentId);
        Map<Long, List<GovernanceDataEvent>> governance = group(snapshot.governance().stream()
                .filter(item -> item.getAgentId() != null).toList(), GovernanceDataEvent::getAgentId);
        Map<Long, List<KnowledgeInteraction>> knowledge = group(snapshot.knowledge().stream()
                .filter(item -> item.getAgentId() != null).toList(), KnowledgeInteraction::getAgentId);
        Map<Long, List<AgentTask>> tasks = group(snapshot.tasks(), AgentTask::getAgentId);
        Map<Long, List<AgentWorkItem>> workItems = group(snapshot.openWorkItems().stream()
                .filter(item -> item.agentId != null).toList(), item -> item.agentId);

        List<AgentOpsDtos.AgentStateResponse> responses = new ArrayList<>();
        for (Agent agent : snapshot.agents()) {
            Long id = agent.getId();
            List<AgentRuntimeInstance> agentRuntimes = runtimes.getOrDefault(id, List.of());
            List<ModelInvocationEvent> agentModels = models.getOrDefault(id, List.of());
            List<TraceSpanObservation> agentSpans = spans.getOrDefault(id, List.of());
            List<ToolInvocationEvent> agentTools = tools.getOrDefault(id, List.of());
            List<ContentObservation> agentContent = content.getOrDefault(id, List.of());
            List<GovernanceDataEvent> agentGovernance = governance.getOrDefault(id, List.of());
            List<KnowledgeInteraction> agentKnowledge = knowledge.getOrDefault(id, List.of());
            List<AgentTask> agentTasks = tasks.getOrDefault(id, List.of());
            List<AgentWorkItem> agentItems = workItems.getOrDefault(id, List.of());
            List<DiscoveredAgent> agentDiscoveries = discoveries.getOrDefault(id, List.of());

            OperationalHealth health = health(agentRuntimes, agentDiscoveries, snapshot.now());
            List<String> signals = coverageSignals(agentRuntimes, agentModels, agentSpans, agentTools,
                    agentContent, agentGovernance, agentKnowledge, agentTasks);
            if (!agentDiscoveries.isEmpty()) signals.add("DISCOVERY_SNAPSHOT");
            int score = signals.size();
            ObservationCoverage coverage = score >= 4 && !agentRuntimes.isEmpty() && !agentModels.isEmpty()
                    ? ObservationCoverage.FULL : score > 0 ? ObservationCoverage.PARTIAL : ObservationCoverage.MISSING;
            OnboardingState onboarding = onboarding(agent, agentDiscoveries);
            Instant latest = latestObserved(agentRuntimes, agentModels, agentSpans, agentTools,
                    agentContent, agentGovernance, agentKnowledge, agentTasks);
            RiskPosture posture = posture(agent, agentItems, agentGovernance);

            responses.add(new AgentOpsDtos.AgentStateResponse(
                    id, agent.getCode(), agent.getName(), agent.getDescription(), agent.getOwnerDepartment(), agent.getOwnerUser(),
                    agent.getCategory(), agent.getLifecycleStatus(), agent.getRiskLevel(), health, onboarding, coverage, posture,
                    score, signals, agentRuntimes.size(), (int) agentRuntimes.stream().filter(item -> item.getStatus() == RuntimeStatus.ONLINE).count(),
                    agentItems.size(), latest, freshness(latest, snapshot.now()),
                    agent.getRecordOrigin() == AgentRecordOrigin.DEMO ? EvidenceBasis.DEMO
                            : score > 0 ? EvidenceBasis.MEASURED : EvidenceBasis.HUMAN_CONFIRMED,
                    agent.getUpdatedAt()
            ));
        }
        return responses;
    }

    private OperationalHealth health(List<AgentRuntimeInstance> runtimes, List<DiscoveredAgent> discoveries, Instant now) {
        if (runtimes.isEmpty()) {
            if (discoveries.stream().anyMatch(item -> "ONLINE".equals(item.getAvailabilityStatus()))) {
                return OperationalHealth.HEALTHY;
            }
            if (!discoveries.isEmpty() && discoveries.stream().allMatch(item -> "OFFLINE".equals(item.getAvailabilityStatus()))) {
                return OperationalHealth.OFFLINE;
            }
            return OperationalHealth.NOT_CONNECTED;
        }
        Instant freshAfter = now.minus(Duration.ofMinutes(10));
        boolean degraded = runtimes.stream().anyMatch(item -> item.getStatus() == RuntimeStatus.DEGRADED
                && item.getLastHeartbeatAt() != null && !item.getLastHeartbeatAt().isBefore(freshAfter));
        if (degraded) return OperationalHealth.DEGRADED;
        boolean online = runtimes.stream().anyMatch(item -> item.getStatus() == RuntimeStatus.ONLINE
                && item.getLastHeartbeatAt() != null && !item.getLastHeartbeatAt().isBefore(freshAfter));
        return online ? OperationalHealth.HEALTHY : OperationalHealth.OFFLINE;
    }

    private OnboardingState onboarding(Agent agent, List<DiscoveredAgent> discoveries) {
        if (blank(agent.getOwnerDepartment()) || blank(agent.getOwnerUser())
                || "待认领".equals(agent.getOwnerDepartment()) || "待认领".equals(agent.getOwnerUser())) {
            return OnboardingState.NEEDS_OWNER;
        }
        return discoveries.isEmpty() ? OnboardingState.MANUAL : OnboardingState.MANAGED;
    }

    private RiskPosture posture(Agent agent, List<AgentWorkItem> items, List<GovernanceDataEvent> governance) {
        if (agent.getRiskLevel() == RiskLevel.CRITICAL
                || items.stream().anyMatch(item -> item.severity == WorkItemSeverity.P0)) return RiskPosture.CRITICAL;
        if (agent.getRiskLevel() == RiskLevel.HIGH
                || items.stream().anyMatch(item -> item.severity == WorkItemSeverity.P1)
                || governance.stream().anyMatch(item -> item.isCompanySecret() || item.getAction() == GovernanceDataAction.BLOCK)) {
            return RiskPosture.HIGH;
        }
        if (agent.getRiskLevel() == RiskLevel.MEDIUM || !items.isEmpty() || !governance.isEmpty()) return RiskPosture.ATTENTION;
        return RiskPosture.NORMAL;
    }

    private List<String> coverageSignals(
            List<AgentRuntimeInstance> runtimes,
            List<ModelInvocationEvent> models,
            List<TraceSpanObservation> spans,
            List<ToolInvocationEvent> tools,
            List<ContentObservation> content,
            List<GovernanceDataEvent> governance,
            List<KnowledgeInteraction> knowledge,
            List<AgentTask> tasks
    ) {
        List<String> signals = new ArrayList<>();
        if (!runtimes.isEmpty()) signals.add("RUNTIME_HEARTBEAT");
        if (!models.isEmpty()) signals.add("MODEL_TELEMETRY");
        if (!spans.isEmpty()) signals.add("TRACE");
        if (!tools.isEmpty()) signals.add("TOOL_MCP");
        if (!knowledge.isEmpty()) signals.add("KNOWLEDGE");
        if (!governance.isEmpty()) signals.add("GOVERNANCE");
        if (!content.isEmpty()) signals.add("CONTENT_METADATA");
        if (!tasks.isEmpty()) signals.add("TASKS");
        return signals;
    }

    private Instant latestObserved(
            List<AgentRuntimeInstance> runtimes,
            List<ModelInvocationEvent> models,
            List<TraceSpanObservation> spans,
            List<ToolInvocationEvent> tools,
            List<ContentObservation> content,
            List<GovernanceDataEvent> governance,
            List<KnowledgeInteraction> knowledge,
            List<AgentTask> tasks
    ) {
        return List.of(
                        runtimes.stream().map(AgentRuntimeInstance::getLastHeartbeatAt).filter(Objects::nonNull).max(Instant::compareTo),
                        models.stream().map(ModelInvocationEvent::getObservedAt).max(Instant::compareTo),
                        spans.stream().map(TraceSpanObservation::getObservedAt).max(Instant::compareTo),
                        tools.stream().map(ToolInvocationEvent::getObservedAt).max(Instant::compareTo),
                        content.stream().map(ContentObservation::getObservedAt).max(Instant::compareTo),
                        governance.stream().map(GovernanceDataEvent::getObservedAt).max(Instant::compareTo),
                        knowledge.stream().map(KnowledgeInteraction::getCreatedAt).max(Instant::compareTo),
                        tasks.stream().map(AgentTask::getUpdatedAt).filter(Objects::nonNull).max(Instant::compareTo)
                ).stream().flatMap(Optional::stream).max(Instant::compareTo).orElse(null);
    }

    private Comparator<AgentWorkItem> workItemComparator() {
        return Comparator.comparing((AgentWorkItem item) -> item.severity)
                .thenComparing(item -> item.slaDueAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> item.firstDetectedAt);
    }

    private long countSeverity(List<AgentWorkItem> items, WorkItemSeverity severity) {
        return items.stream().filter(item -> item.severity == severity).count();
    }

    private long countHealth(List<AgentOpsDtos.AgentStateResponse> states, OperationalHealth health) {
        return states.stream().filter(state -> state.operationalHealth() == health).count();
    }

    private long countCoverage(List<AgentOpsDtos.AgentStateResponse> states, ObservationCoverage coverage) {
        return states.stream().filter(state -> state.observationCoverage() == coverage).count();
    }

    private Instant latestUpdated(List<AgentOpsDtos.AgentStateResponse> states) {
        return states.stream().map(AgentOpsDtos.AgentStateResponse::updatedAt).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
    }

    private AgentOpsDtos.MetricEvidence metric(
            String key, String label, long value, EvidenceBasis basis, String source, Instant measuredAt, String freshness
    ) {
        return new AgentOpsDtos.MetricEvidence(key, label, value, basis, source, measuredAt, freshness);
    }

    private String freshness(Instant observedAt, Instant now) {
        if (observedAt == null) return "NO_DATA";
        Duration age = Duration.between(observedAt, now);
        if (age.compareTo(Duration.ofMinutes(5)) <= 0) return "FRESH";
        if (age.compareTo(Duration.ofHours(1)) <= 0) return "AGING";
        return "STALE";
    }

    private int clampHours(int hours) { return Math.max(1, Math.min(hours, 24 * 30)); }

    private boolean matchesQuery(AgentOpsDtos.AgentStateResponse state, String query) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return List.of(state.name(), state.code(), state.ownerDepartment(), state.ownerUser(), state.category()).stream()
                .filter(Objects::nonNull).map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(value -> value.contains(needle));
    }

    private boolean contains(String value, String expected) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(expected.trim().toLowerCase(Locale.ROOT));
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private boolean visibleAgent(Agent agent) {
        return securityProperties.isDevelopmentMode()
                || agent.getRecordOrigin() != AgentRecordOrigin.DEMO;
    }

    private <T> Map<Long, List<T>> group(List<T> values, Function<T, Long> classifier) {
        return values.stream().collect(Collectors.groupingBy(classifier));
    }

    private record Snapshot(
            Instant now,
            Instant windowStart,
            List<Agent> agents,
            List<AgentRuntimeInstance> runtimes,
            List<DiscoveredAgent> discoveries,
            List<ModelInvocationEvent> models,
            List<TraceSpanObservation> spans,
            List<ToolInvocationEvent> tools,
            List<ContentObservation> content,
            List<GovernanceDataEvent> governance,
            List<KnowledgeInteraction> knowledge,
            List<AgentTask> tasks,
            List<AgentWorkItem> openWorkItems
    ) {}
}
