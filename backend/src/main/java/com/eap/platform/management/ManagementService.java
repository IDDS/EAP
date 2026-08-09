package com.eap.platform.management;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.discovery.DiscoveryEndpointRepository;
import com.eap.platform.knowledge.KnowledgeDtos;
import com.eap.platform.knowledge.KnowledgeService;
import com.eap.platform.operations.AgentTaskDtos;
import com.eap.platform.operations.AgentTaskService;
import com.eap.platform.operations.AgentTaskStatus;
import com.eap.platform.telemetry.ModelTelemetryDtos;
import com.eap.platform.telemetry.ModelTelemetryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ManagementService {
    private final ManagementValueEventRepository valueRepository;
    private final AgentRepository agentRepository;
    private final DiscoveryEndpointRepository endpointRepository;
    private final AgentTaskService taskService;
    private final ModelTelemetryService telemetryService;
    private final KnowledgeService knowledgeService;
    private final ManagementProperties properties;

    public ManagementService(
            ManagementValueEventRepository valueRepository,
            AgentRepository agentRepository,
            DiscoveryEndpointRepository endpointRepository,
            AgentTaskService taskService,
            ModelTelemetryService telemetryService,
            KnowledgeService knowledgeService,
            ManagementProperties properties
    ) {
        this.valueRepository = valueRepository;
        this.agentRepository = agentRepository;
        this.endpointRepository = endpointRepository;
        this.taskService = taskService;
        this.telemetryService = telemetryService;
        this.knowledgeService = knowledgeService;
        this.properties = properties;
    }

    public String ingestionToken() {
        return properties.getIngestionToken();
    }

    @Transactional
    public ManagementDtos.ValueEventResponse recordValue(ManagementDtos.ValueEventRequest request) {
        ManagementValueEvent existing = valueRepository.findByEventKey(request.eventKey()).orElse(null);
        if (existing != null) {
            return ManagementDtos.from(existing);
        }
        validateAttribution(request.agentId(), request.endpointKey());
        ManagementValueEvent event = new ManagementValueEvent();
        event.eventKey = request.eventKey();
        event.agentId = request.agentId();
        event.endpointKey = blankToNull(request.endpointKey());
        event.taskId = request.taskId();
        event.knowledgeInteractionId = request.knowledgeInteractionId();
        event.valueType = request.valueType();
        event.description = sanitizeDescription(request.description());
        event.validatedBy = request.validatedBy();
        event.benefitMoney = request.benefitMoney() == null ? BigDecimal.ZERO : request.benefitMoney();
        event.currency = request.currency() == null || request.currency().isBlank() ? "USD" : request.currency().toUpperCase();
        event.observedAt = request.observedAt() == null ? Instant.now() : request.observedAt();
        return ManagementDtos.from(valueRepository.save(event));
    }

    @Transactional(readOnly = true)
    public List<ManagementDtos.ValueEventResponse> listValues(int limit) {
        return valueRepository.findTop500ByOrderByObservedAtDesc().stream().limit(limit).map(ManagementDtos::from).toList();
    }

    @Transactional(readOnly = true)
    public ManagementDtos.ManagementSummary summary(int windowHours) {
        Instant since = Instant.now().minus(windowHours, ChronoUnit.HOURS);
        ModelTelemetryDtos.PhysicalTelemetrySummary telemetry = telemetryService.summary(windowHours);
        KnowledgeDtos.KnowledgeSummary knowledge = knowledgeService.summary(windowHours);
        List<AgentTaskDtos.TaskResponse> tasks = taskService.list().stream()
                .filter(task -> task.createdAt() != null && !task.createdAt().isBefore(since))
                .toList();
        List<ManagementValueEvent> values = valueRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(since);

        Map<Long, String> agentNames = new LinkedHashMap<>();
        agentRepository.findAll().forEach(agent -> agentNames.put(agent.getId(), agent.getName()));
        Map<String, ManagementUsageAccumulator> usage = new LinkedHashMap<>();
        telemetry.subjectUsage().forEach(item -> usage.computeIfAbsent(item.subjectKey(), ignored -> new ManagementUsageAccumulator(
                item.subjectKey(), item.subjectName(), item.agentId(), item.endpointKey()
        )).addTelemetry(item));
        knowledge.agentUsage().forEach(item -> usage.computeIfAbsent(item.subjectKey(), ignored -> new ManagementUsageAccumulator(
                item.subjectKey(), item.subjectName(), item.agentId(), item.endpointKey()
        )).addKnowledge(item));
        tasks.forEach(task -> {
            String key = "agent:" + task.agentId();
            usage.computeIfAbsent(key, ignored -> new ManagementUsageAccumulator(
                    key, agentNames.getOrDefault(task.agentId(), "Agent " + task.agentId()), task.agentId(), null
            )).addTask(task);
        });
        values.forEach(value -> {
            String key = value.agentId != null ? "agent:" + value.agentId : "endpoint:" + value.endpointKey;
            usage.computeIfAbsent(key, ignored -> new ManagementUsageAccumulator(
                    key,
                    value.agentId != null ? agentNames.getOrDefault(value.agentId, "Agent " + value.agentId) : value.endpointKey,
                    value.agentId,
                    value.endpointKey
            )).addValue(value);
        });

        BigDecimal totalCost = telemetry.moneyCost();
        BigDecimal valueAmount = values.stream().map(value -> value.benefitMoney).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netBenefit = valueAmount.subtract(totalCost);
        return new ManagementDtos.ManagementSummary(
                windowHours,
                tasks.size(),
                tasks.stream().filter(task -> task.status() == AgentTaskStatus.SUCCEEDED).count(),
                taskSuccessRate(tasks),
                telemetry.totalCalls(),
                telemetry.totalTokens(),
                totalCost,
                valueAmount,
                netBenefit,
                roi(valueAmount, totalCost),
                knowledge.adoptedInteractions(),
                knowledge.reusedInteractions(),
                knowledge.adoptionRate(),
                knowledge.reuseRate(),
                !values.isEmpty(),
                values.isEmpty() ? null : values.get(0).observedAt,
                usage.values().stream().sorted(Comparator.comparingLong(ManagementUsageAccumulator::totalTokens).reversed()).map(ManagementUsageAccumulator::toResponse).toList()
        );
    }

    private void validateAttribution(Long agentId, String endpointKey) {
        if (agentId == null && (endpointKey == null || endpointKey.isBlank())) {
            throw new BusinessException("业务价值事件必须关联 Agent 或发现端点");
        }
        if (agentId != null && !agentRepository.existsById(agentId)) {
            throw new BusinessException("智能体不存在");
        }
        if (endpointKey != null && !endpointKey.isBlank() && endpointRepository.findByEndpointKey(endpointKey).isEmpty()) {
            throw new BusinessException("发现端点不存在");
        }
    }

    private double taskSuccessRate(List<AgentTaskDtos.TaskResponse> tasks) {
        long completed = tasks.stream().filter(task -> task.status() == AgentTaskStatus.SUCCEEDED || task.status() == AgentTaskStatus.FAILED).count();
        long succeeded = tasks.stream().filter(task -> task.status() == AgentTaskStatus.SUCCEEDED).count();
        return completed == 0 ? 0.0 : (double) succeeded / completed;
    }

    private BigDecimal roi(BigDecimal value, BigDecimal cost) {
        if (cost == null || cost.signum() == 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        return value.subtract(cost).divide(cost, 4, RoundingMode.HALF_UP);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String sanitizeDescription(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String sanitized = value
                .replaceAll("(?i)(api[_-]?key|token|secret|password)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]")
                .replaceAll("(?i)bearer\\s+[a-z0-9._-]+", "Bearer [REDACTED]");
        return sanitized.length() > 2048 ? sanitized.substring(0, 2048) : sanitized;
    }

    private static final class ManagementUsageAccumulator {
        private final String key;
        private final String name;
        private final Long agentId;
        private final String endpointKey;
        private long tasks;
        private long succeededTasks;
        private long modelCalls;
        private long knowledgeInteractions;
        private long totalTokens;
        private BigDecimal totalCost = BigDecimal.ZERO;
        private BigDecimal validatedValue = BigDecimal.ZERO;
        private long adopted;
        private long reused;

        private ManagementUsageAccumulator(String key, String name, Long agentId, String endpointKey) {
            this.key = key;
            this.name = name;
            this.agentId = agentId;
            this.endpointKey = endpointKey;
        }

        void addTelemetry(ModelTelemetryDtos.SubjectUsage usage) {
            modelCalls += usage.calls();
            totalTokens += usage.totalTokens();
            totalCost = totalCost.add(usage.moneyCost());
        }

        void addKnowledge(KnowledgeDtos.KnowledgeAgentUsage usage) {
            knowledgeInteractions += usage.interactions();
            adopted += usage.adoptedInteractions();
            reused += usage.reusedInteractions();
        }

        void addTask(AgentTaskDtos.TaskResponse task) {
            tasks++;
            succeededTasks += task.status() == AgentTaskStatus.SUCCEEDED ? 1 : 0;
        }

        void addValue(ManagementValueEvent value) {
            validatedValue = validatedValue.add(value.benefitMoney);
        }

        long totalTokens() {
            return totalTokens;
        }

        ManagementDtos.AgentManagementUsage toResponse() {
            BigDecimal netBenefit = validatedValue.subtract(totalCost);
            BigDecimal roi = totalCost.signum() == 0 ? BigDecimal.ZERO.setScale(4) : netBenefit.divide(totalCost, 4, RoundingMode.HALF_UP);
            return new ManagementDtos.AgentManagementUsage(
                    key, name, agentId, endpointKey, tasks, succeededTasks, modelCalls, knowledgeInteractions,
                    totalTokens, totalCost, validatedValue, netBenefit, roi,
                    knowledgeInteractions == 0 ? 0.0 : (double) adopted / knowledgeInteractions,
                    knowledgeInteractions == 0 ? 0.0 : (double) reused / knowledgeInteractions
            );
        }
    }
}
