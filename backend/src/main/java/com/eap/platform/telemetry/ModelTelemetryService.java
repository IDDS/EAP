package com.eap.platform.telemetry;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.discovery.DiscoveryEndpointRepository;
import com.eap.platform.governance.GovernanceGatewayDirective;
import com.eap.platform.governance.GovernanceDataEvent;
import com.eap.platform.governance.GovernanceDataEventRepository;
import com.eap.platform.governance.GovernancePreflightDecision;
import com.eap.platform.governance.GovernancePreflightDecisionRepository;
import com.eap.platform.operations.AgentTaskService;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.registry.RuntimeStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModelTelemetryService {
    private final ModelInvocationEventRepository eventRepository;
    private final AgentRepository agentRepository;
    private final DiscoveryEndpointRepository endpointRepository;
    private final AgentRuntimeInstanceRepository runtimeRepository;
    private final AgentTaskService taskService;
    private final TelemetryProperties properties;
    private final GovernancePreflightDecisionRepository governanceDecisionRepository;
    private final GovernanceDataEventRepository governanceDataEventRepository;

    public ModelTelemetryService(
            ModelInvocationEventRepository eventRepository,
            AgentRepository agentRepository,
            DiscoveryEndpointRepository endpointRepository,
            AgentRuntimeInstanceRepository runtimeRepository,
            AgentTaskService taskService,
            TelemetryProperties properties,
            GovernancePreflightDecisionRepository governanceDecisionRepository,
            GovernanceDataEventRepository governanceDataEventRepository
    ) {
        this.eventRepository = eventRepository;
        this.agentRepository = agentRepository;
        this.endpointRepository = endpointRepository;
        this.runtimeRepository = runtimeRepository;
        this.taskService = taskService;
        this.properties = properties;
        this.governanceDecisionRepository = governanceDecisionRepository;
        this.governanceDataEventRepository = governanceDataEventRepository;
    }

    public String ingestionToken() {
        return properties.getIngestionToken();
    }

    @Transactional
    public ModelTelemetryDtos.ModelInvocationResponse record(ModelTelemetryDtos.ModelInvocationRequest request) {
        return record(request, ModelObservationSource.TELEMETRY_API, true);
    }

    @Transactional
    public ModelTelemetryDtos.ModelInvocationResponse recordGateway(ModelTelemetryDtos.ModelInvocationRequest request) {
        return record(request, ModelObservationSource.GATEWAY, true);
    }

    @Transactional
    public ModelTelemetryDtos.ModelInvocationResponse recordPassive(
            ModelTelemetryDtos.ModelInvocationRequest request,
            ModelObservationSource source
    ) {
        return record(request, source == null ? ModelObservationSource.OTLP : source, false);
    }

    private ModelTelemetryDtos.ModelInvocationResponse record(
            ModelTelemetryDtos.ModelInvocationRequest request,
            ModelObservationSource source,
            boolean enforceGovernance
    ) {
        ModelInvocationEvent existing = eventRepository.findByEventKey(request.eventKey()).orElse(null);
        if (existing != null) {
            return ModelTelemetryDtos.from(existing);
        }
        validateAttribution(request);
        if (enforceGovernance) {
            validateGovernanceDecision(request);
        }
        ModelInvocationEvent event = new ModelInvocationEvent();
        event.eventKey = request.eventKey();
        event.traceId = blankToNull(request.traceId());
        event.spanId = blankToNull(request.spanId());
        event.agentId = request.agentId();
        event.endpointKey = blankToNull(request.endpointKey());
        event.runtimeInstanceKey = blankToNull(request.runtimeInstanceKey());
        event.taskId = request.taskId();
        event.governanceDecisionKey = blankToNull(request.governanceDecisionKey());
        event.observationSource = source;
        event.governanceCovered = event.governanceDecisionKey != null;
        event.usagePoolKey = blankToNull(request.usagePoolKey());
        event.provider = request.provider();
        event.modelName = request.modelName();
        event.status = request.status();
        event.inputTokens = request.inputTokens();
        event.outputTokens = request.outputTokens();
        event.cachedInputTokens = request.cachedInputTokens();
        event.reasoningTokens = request.reasoningTokens();
        event.latencyMs = request.latencyMs();
        event.moneyCost = request.moneyCost() == null ? BigDecimal.ZERO : request.moneyCost();
        event.currency = request.currency() == null || request.currency().isBlank() ? "USD" : request.currency().trim().toUpperCase(java.util.Locale.ROOT);
        event.toolCallCount = request.toolCallCount();
        event.mcpServerCount = request.mcpServerCount();
        event.errorType = blankToNull(request.errorType());
        event.observedAt = request.observedAt() == null ? Instant.now() : request.observedAt();
        event = eventRepository.save(event);
        taskService.recordTelemetryUsage(event.taskId, event.inputTokens + event.outputTokens, event.moneyCost, event.latencyMs);
        return ModelTelemetryDtos.from(event);
    }

    @Transactional(readOnly = true)
    public com.eap.platform.common.HistoryPage<ModelTelemetryDtos.ModelInvocationResponse> page(com.eap.platform.common.ObservationHistoryQuery query) {
        var pageable=query.pageable();
        var selected=query.enumStatus(ModelInvocationStatus.class);
        var spec=query.<ModelInvocationEvent>specification("status", selected, "eventKey", "traceId", "provider", "modelName").and(query.observationContext());
        return com.eap.platform.common.HistoryPage.from(eventRepository.findAll(spec,pageable).map(ModelTelemetryDtos::from));
    }

    @Transactional(readOnly = true)
    public List<ModelTelemetryDtos.ModelInvocationResponse> list(int limit) {
        return list(limit, null, null, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<ModelTelemetryDtos.ModelInvocationResponse> list(
            int limit,
            Long agentId,
            String endpointKey,
            String runtimeInstanceKey,
            String traceId,
            ModelInvocationStatus status,
            Instant from,
            Instant to
    ) {
        return list(limit, agentId, endpointKey, runtimeInstanceKey, traceId, status, from, to, 0);
    }

    @Transactional(readOnly = true)
    public List<ModelTelemetryDtos.ModelInvocationResponse> list(
            int limit,
            Long agentId,
            String endpointKey,
            String runtimeInstanceKey,
            String traceId,
            ModelInvocationStatus status,
            Instant from,
            Instant to,
            int page
    ) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        Specification<ModelInvocationEvent> specification = Specification.where(null);
        if (agentId != null) specification = specification.and((root, query, cb) -> cb.equal(root.get("agentId"), agentId));
        if (blankToNull(endpointKey) != null) specification = specification.and((root, query, cb) -> cb.equal(root.get("endpointKey"), endpointKey.trim()));
        if (blankToNull(runtimeInstanceKey) != null) specification = specification.and((root, query, cb) -> cb.equal(root.get("runtimeInstanceKey"), runtimeInstanceKey.trim()));
        if (blankToNull(traceId) != null) specification = specification.and((root, query, cb) -> cb.equal(root.get("traceId"), traceId.trim()));
        if (status != null) specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
        if (from != null) specification = specification.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("observedAt"), from));
        if (to != null) specification = specification.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("observedAt"), to));
        return eventRepository.findAll(specification,
                        PageRequest.of(page, boundedLimit, Sort.by(Sort.Direction.DESC, "observedAt", "id")))
                .map(ModelTelemetryDtos::from).getContent();
    }

    @Transactional(readOnly = true)
    public List<String> currencies(int windowHours) {
        return eventRepository.findCurrenciesSince(Instant.now().minus(windowHours, ChronoUnit.HOURS)).stream()
                .map(this::currencyCode).distinct().sorted().toList();
    }

    private String currencyCode(String value) {
        return value == null || value.isBlank() ? "USD" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public ModelTelemetryDtos.PhysicalTelemetrySummary summary(int windowHours) {
        return summary(windowHours, null);
    }

    @Transactional(readOnly = true)
    public ModelTelemetryDtos.PhysicalTelemetrySummary summary(int windowHours, String currency) {
        Instant since = Instant.now().minus(windowHours, ChronoUnit.HOURS);
        List<ModelInvocationEvent> events = eventRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(since).stream()
                .filter(event -> currency == null || currencyCode(event.currency).equals(currencyCode(currency))).toList();
        Map<Long, String> agentNames = new LinkedHashMap<>();
        agentRepository.findAll().forEach(agent -> agentNames.put(agent.getId(), agent.getName()));

        long succeeded = events.stream().filter(event -> event.status == ModelInvocationStatus.SUCCEEDED).count();
        long failed = events.stream().filter(event -> event.status == ModelInvocationStatus.FAILED).count();
        long inputTokens = events.stream().mapToLong(event -> event.inputTokens).sum();
        long outputTokens = events.stream().mapToLong(event -> event.outputTokens).sum();
        long cachedTokens = events.stream().mapToLong(event -> event.cachedInputTokens).sum();
        long reasoningTokens = events.stream().mapToLong(event -> event.reasoningTokens).sum();
        long latencyTotal = events.stream().mapToLong(event -> event.latencyMs).sum();
        BigDecimal moneyCost = events.stream().map(event -> event.moneyCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        long completed = succeeded + failed;
        double successRate = completed == 0 ? 0.0 : (double) succeeded / completed;
        long averageLatency = events.isEmpty() ? 0L : latencyTotal / events.size();

        List<Long> latencies = events.stream().map(event -> event.latencyMs).sorted().toList();
        long p95Latency = percentile95(latencies);

        Map<String, UsageAccumulator> subjects = new LinkedHashMap<>();
        Map<String, UsageAccumulator> models = new LinkedHashMap<>();
        for (ModelInvocationEvent event : events) {
            String subjectKey = event.agentId != null ? "agent:" + event.agentId : "endpoint:" + event.endpointKey;
            String subjectName = event.agentId != null
                    ? agentNames.getOrDefault(event.agentId, "Agent " + event.agentId)
                    : event.endpointKey;
            subjects.computeIfAbsent(subjectKey, ignored -> new UsageAccumulator(
                    subjectKey, subjectName, event.agentId, event.endpointKey, null, null
            )).add(event);

            String modelKey = event.provider + "\u0000" + event.modelName;
            models.computeIfAbsent(modelKey, ignored -> new UsageAccumulator(
                    modelKey, null, null, null, event.provider, event.modelName
            )).add(event);
        }

        List<ModelTelemetryDtos.SubjectUsage> subjectUsage = subjects.values().stream()
                .sorted(Comparator.comparingLong(UsageAccumulator::totalTokens).reversed())
                .map(UsageAccumulator::toSubjectUsage)
                .toList();
        List<ModelTelemetryDtos.ModelUsage> modelUsage = models.values().stream()
                .sorted(Comparator.comparingLong(UsageAccumulator::totalTokens).reversed())
                .map(UsageAccumulator::toModelUsage)
                .toList();

        var endpoints = endpointRepository.findAll();
        long activeEndpoints = endpoints.stream().filter(endpoint -> endpoint.getLastSeenAt() != null && !endpoint.getLastSeenAt().isBefore(since)).count();
        var runtimes = runtimeRepository.findAll();
        long onlineRuntimes = runtimes.stream().filter(runtime -> runtime.getStatus() == RuntimeStatus.ONLINE).count();
        long degradedRuntimes = runtimes.stream().filter(runtime -> runtime.getStatus() == RuntimeStatus.DEGRADED).count();
        long offlineRuntimes = runtimes.stream().filter(runtime -> runtime.getStatus() == RuntimeStatus.OFFLINE).count();

        return new ModelTelemetryDtos.PhysicalTelemetrySummary(
                windowHours, events.size(), succeeded, failed, successRate,
                inputTokens, outputTokens, cachedTokens, reasoningTokens, inputTokens + outputTokens,
                moneyCost, averageLatency, p95Latency,
                endpoints.size(), activeEndpoints, onlineRuntimes, degradedRuntimes, offlineRuntimes,
                events.isEmpty() ? null : events.get(0).observedAt, subjectUsage, modelUsage
        );
    }

    private void validateAttribution(ModelTelemetryDtos.ModelInvocationRequest request) {
        if (request.agentId() == null && (request.endpointKey() == null || request.endpointKey().isBlank())) {
            throw new BusinessException("模型调用事件必须关联 Agent 或发现端点");
        }
        if (request.agentId() != null && !agentRepository.existsById(request.agentId())) {
            throw new BusinessException("智能体不存在");
        }
        if (request.endpointKey() != null && !request.endpointKey().isBlank()
                && endpointRepository.findByEndpointKey(request.endpointKey()).isEmpty()) {
            throw new BusinessException("发现端点不存在");
        }
        if (request.runtimeInstanceKey() != null && !request.runtimeInstanceKey().isBlank()) {
            var runtime = runtimeRepository.findByInstanceKey(request.runtimeInstanceKey())
                    .orElseThrow(() -> new BusinessException("运行实例不存在"));
            if (request.agentId() != null && !request.agentId().equals(runtime.getAgentId())) {
                throw new BusinessException("运行实例与智能体不匹配");
            }
        }
    }

    private void validateGovernanceDecision(ModelTelemetryDtos.ModelInvocationRequest request) {
        if (!properties.isRequireGovernancePreflight()) {
            return;
        }
        if (request.governanceDecisionKey() == null || request.governanceDecisionKey().isBlank()) {
            throw new BusinessException("模型调用必须携带治理预检决策 Key");
        }
        GovernancePreflightDecision decision = governanceDecisionRepository.findByDecisionKey(request.governanceDecisionKey())
                .orElseThrow(() -> new BusinessException("治理预检决策不存在或已失效"));
        Instant decidedAt = decision.getDecidedAt();
        if (decidedAt == null || decidedAt.isBefore(Instant.now().minusSeconds(properties.getGovernanceDecisionTtlSeconds()))) {
            throw new BusinessException("治理预检决策已过期，请重新预检");
        }
        GovernanceDataEvent dataEvent = governanceDataEventRepository.findById(decision.getDataEventId())
                .orElseThrow(() -> new BusinessException("治理预检关联事件不存在"));
        if (!sameNullable(request.agentId(), dataEvent.getAgentId())
                || !sameText(request.endpointKey(), dataEvent.getEndpointKey())) {
            throw new BusinessException("模型调用归属与治理预检不一致");
        }
        if (decision.getProvider() != null && !decision.getProvider().equalsIgnoreCase(request.provider())) {
            throw new BusinessException("模型供应商与治理预检不一致");
        }
        if (decision.getModelName() != null && !decision.getModelName().equalsIgnoreCase(request.modelName())) {
            throw new BusinessException("模型名称与治理预检不一致");
        }
        if (!decision.isRequestAllowed()
                || decision.getGatewayDirective() == GovernanceGatewayDirective.BLOCK
                || decision.getGatewayDirective() == GovernanceGatewayDirective.REDACT_AND_RECHECK
                || decision.getGatewayDirective() == GovernanceGatewayDirective.REQUEST_APPROVAL) {
            throw new BusinessException("治理预检未允许此次模型调用：" + decision.getGatewayDirective());
        }
    }

    private boolean sameNullable(Long left, Long right) {
        return left == null ? right == null : left.equals(right);
    }

    private boolean sameText(String left, String right) {
        return left == null ? right == null : right != null && left.equalsIgnoreCase(right);
    }

    private long percentile95(List<Long> values) {
        if (values.isEmpty()) {
            return 0L;
        }
        int index = Math.max(0, (int) Math.ceil(values.size() * 0.95) - 1);
        return values.get(index);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class UsageAccumulator {
        private final String key;
        private final String name;
        private final Long agentId;
        private final String endpointKey;
        private final String provider;
        private final String modelName;
        private long calls;
        private long succeeded;
        private long failed;
        private long inputTokens;
        private long outputTokens;
        private long latencyTotal;
        private BigDecimal moneyCost = BigDecimal.ZERO;
        private Instant lastEventAt;

        private UsageAccumulator(String key, String name, Long agentId, String endpointKey, String provider, String modelName) {
            this.key = key;
            this.name = name;
            this.agentId = agentId;
            this.endpointKey = endpointKey;
            this.provider = provider;
            this.modelName = modelName;
        }

        void add(ModelInvocationEvent event) {
            calls++;
            succeeded += event.status == ModelInvocationStatus.SUCCEEDED ? 1 : 0;
            failed += event.status == ModelInvocationStatus.FAILED ? 1 : 0;
            inputTokens += event.inputTokens;
            outputTokens += event.outputTokens;
            latencyTotal += event.latencyMs;
            moneyCost = moneyCost.add(event.moneyCost);
            if (lastEventAt == null || event.observedAt.isAfter(lastEventAt)) {
                lastEventAt = event.observedAt;
            }
        }

        long totalTokens() {
            return inputTokens + outputTokens;
        }

        ModelTelemetryDtos.SubjectUsage toSubjectUsage() {
            long completed = succeeded + failed;
            return new ModelTelemetryDtos.SubjectUsage(
                    key, name, agentId, endpointKey, calls, succeeded, failed,
                    completed == 0 ? 0.0 : (double) succeeded / completed,
                    inputTokens, outputTokens, totalTokens(), moneyCost,
                    calls == 0 ? 0L : latencyTotal / calls, lastEventAt
            );
        }

        ModelTelemetryDtos.ModelUsage toModelUsage() {
            return new ModelTelemetryDtos.ModelUsage(
                    provider, modelName, calls, totalTokens(), moneyCost,
                    calls == 0 ? 0L : latencyTotal / calls
            );
        }
    }
}
