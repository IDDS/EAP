package com.eap.platform.governance;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.discovery.DiscoveryEndpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GovernanceDataService {
    private final GovernanceDataEventRepository eventRepository;
    private final AgentRepository agentRepository;
    private final DiscoveryEndpointRepository endpointRepository;
    private final GovernanceDataProperties properties;

    public GovernanceDataService(
            GovernanceDataEventRepository eventRepository,
            AgentRepository agentRepository,
            DiscoveryEndpointRepository endpointRepository,
            GovernanceDataProperties properties
    ) {
        this.eventRepository = eventRepository;
        this.agentRepository = agentRepository;
        this.endpointRepository = endpointRepository;
        this.properties = properties;
    }

    public String ingestionToken() {
        return properties.getIngestionToken();
    }

    public String approvalToken() {
        return properties.getApprovalToken();
    }

    @Transactional
    public GovernanceDataDtos.DataEventResponse record(GovernanceDataDtos.DataEventRequest request) {
        GovernanceDataEvent existing = eventRepository.findByEventKey(request.eventKey()).orElse(null);
        if (existing != null) {
            return GovernanceDataDtos.from(existing);
        }
        validateAttribution(request.agentId(), request.endpointKey());
        GovernanceDataEvent event = new GovernanceDataEvent();
        event.eventKey = request.eventKey();
        event.agentId = request.agentId();
        event.endpointKey = blankToNull(request.endpointKey());
        event.taskId = request.taskId();
        event.traceId = blankToNull(request.traceId());
        event.direction = request.direction();
        event.dataCategory = normalize(request.dataCategory(), 128);
        event.sensitivity = request.sensitivity();
        event.action = request.action();
        event.internalData = request.internalData() || request.sensitivity() != GovernanceDataSensitivity.PUBLIC;
        event.companySecret = request.companySecret() || request.sensitivity() == GovernanceDataSensitivity.SECRET
                || request.sensitivity() == GovernanceDataSensitivity.RESTRICTED;
        event.tokenCount = request.tokenCount();
        event.payloadBytes = request.payloadBytes();
        event.policyId = request.policyId();
        event.policyRule = normalize(request.policyRule(), 256);
        event.observedAt = request.observedAt() == null ? Instant.now() : request.observedAt();
        return GovernanceDataDtos.from(eventRepository.save(event));
    }

    @Transactional(readOnly = true)
    public com.eap.platform.common.HistoryPage<GovernanceDataDtos.DataEventResponse> page(com.eap.platform.common.EventHistoryQuery query) {
        GovernanceDataAction action = null;
        if(query.status != null && !query.status.isBlank()) {
            try { action = GovernanceDataAction.valueOf(query.status); }
            catch(IllegalArgumentException ex) { throw new com.eap.platform.common.BusinessException("无效的治理动作"); }
        }
        var pageable = query.pageable();
        var spec = query.<GovernanceDataEvent>specification("action", action, "eventKey", "traceId", "dataCategory", "endpointKey")
            .and(query.context(true));
        return com.eap.platform.common.HistoryPage.from(eventRepository.findAll(spec, pageable).map(GovernanceDataDtos::from));
    }

    @Transactional(readOnly = true)
    public List<GovernanceDataDtos.DataEventResponse> list(int limit) {
        return list(limit, null, 0);
    }

    @Transactional(readOnly = true)
    public List<GovernanceDataDtos.DataEventResponse> list(int limit, Long agentId, int page) {
        PageRequest pageable = PageRequest.of(page, limit);
        List<GovernanceDataEvent> rows = agentId == null
                ? eventRepository.findAllByOrderByObservedAtDescIdDesc(pageable)
                : eventRepository.findByAgentIdOrderByObservedAtDescIdDesc(agentId, pageable);
        return rows.stream().map(GovernanceDataDtos::from).toList();
    }

    @Transactional(readOnly = true)
    public GovernanceDataDtos.GovernanceSummary summary(int windowHours) {
        Instant since = Instant.now().minus(windowHours, ChronoUnit.HOURS);
        List<GovernanceDataEvent> events = eventRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(since);
        Map<String, SubjectAccumulator> usage = new LinkedHashMap<>();
        Map<Long, String> agentNames = new LinkedHashMap<>();
        agentRepository.findAll().forEach(agent -> agentNames.put(agent.getId(), agent.getName()));
        events.forEach(event -> {
            String key = event.agentId != null ? "agent:" + event.agentId : "endpoint:" + event.endpointKey;
            usage.computeIfAbsent(key, ignored -> new SubjectAccumulator(
                    key,
                    event.agentId != null ? agentNames.getOrDefault(event.agentId, "Agent " + event.agentId) : event.endpointKey,
                    event.agentId,
                    event.endpointKey
            )).add(event);
        });
        long modelInputs = events.stream().filter(event -> event.direction == GovernanceDataDirection.INPUT_TO_MODEL).count();
        long internal = events.stream().filter(event -> event.direction == GovernanceDataDirection.INPUT_TO_MODEL
                && Boolean.TRUE.equals(event.internalData)).count();
        long secrets = events.stream().filter(event -> event.direction == GovernanceDataDirection.INPUT_TO_MODEL
                && Boolean.TRUE.equals(event.companySecret)).count();
        return new GovernanceDataDtos.GovernanceSummary(
                windowHours,
                events.size(),
                modelInputs,
                internal,
                secrets,
                countAction(events, GovernanceDataAction.ALLOW),
                countAction(events, GovernanceDataAction.WARN),
                countAction(events, GovernanceDataAction.REDACT),
                countAction(events, GovernanceDataAction.BLOCK),
                countAction(events, GovernanceDataAction.APPROVAL_REQUIRED),
                rate(internal, modelInputs),
                rate(secrets, internal),
                rate(countAction(events, GovernanceDataAction.BLOCK), modelInputs),
                events.stream().mapToLong(event -> event.tokenCount).sum(),
                events.stream().mapToLong(event -> event.payloadBytes).sum(),
                events.isEmpty() ? null : events.get(0).observedAt,
                usage.values().stream().sorted(Comparator.comparingLong(SubjectAccumulator::events).reversed()).map(SubjectAccumulator::toResponse).toList()
        );
    }

    private long countAction(List<GovernanceDataEvent> events, GovernanceDataAction action) {
        return events.stream().filter(event -> event.action == action).count();
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }

    private void validateAttribution(Long agentId, String endpointKey) {
        if (agentId == null && (endpointKey == null || endpointKey.isBlank())) {
            throw new BusinessException("治理数据事件必须关联 Agent 或发现端点");
        }
        if (agentId != null && !agentRepository.existsById(agentId)) {
            throw new BusinessException("智能体不存在");
        }
        if (endpointKey != null && !endpointKey.isBlank() && endpointRepository.findByEndpointKey(endpointKey).isEmpty()) {
            throw new BusinessException("发现端点不存在");
        }
    }

    private String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static final class SubjectAccumulator {
        private final String key;
        private final String name;
        private final Long agentId;
        private final String endpointKey;
        private long events;
        private long modelInputs;
        private long internalEvents;
        private long companySecretEvents;
        private long blockedEvents;
        private long redactedEvents;
        private long tokenCount;

        private SubjectAccumulator(String key, String name, Long agentId, String endpointKey) {
            this.key = key;
            this.name = name;
            this.agentId = agentId;
            this.endpointKey = endpointKey;
        }

        void add(GovernanceDataEvent event) {
            events++;
            modelInputs += event.direction == GovernanceDataDirection.INPUT_TO_MODEL ? 1 : 0;
            internalEvents += event.direction == GovernanceDataDirection.INPUT_TO_MODEL && Boolean.TRUE.equals(event.internalData) ? 1 : 0;
            companySecretEvents += event.direction == GovernanceDataDirection.INPUT_TO_MODEL && Boolean.TRUE.equals(event.companySecret) ? 1 : 0;
            blockedEvents += event.action == GovernanceDataAction.BLOCK ? 1 : 0;
            redactedEvents += event.action == GovernanceDataAction.REDACT ? 1 : 0;
            tokenCount += event.tokenCount;
        }

        long events() {
            return events;
        }

        GovernanceDataDtos.SubjectUsage toResponse() {
            return new GovernanceDataDtos.SubjectUsage(key, name, agentId, endpointKey, events, modelInputs,
                    internalEvents, companySecretEvents, blockedEvents, redactedEvents, tokenCount);
        }
    }
}
