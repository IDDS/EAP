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
    private final ManagementValueAttributionRepository attributionRepository;
    private final AgentRepository agentRepository;
    private final DiscoveryEndpointRepository endpointRepository;
    private final AgentTaskService taskService;
    private final ModelTelemetryService telemetryService;
    private final KnowledgeService knowledgeService;
    private final ManagementProperties properties;

    public ManagementService(
            ManagementValueEventRepository valueRepository,
            ManagementValueAttributionRepository attributionRepository,
            AgentRepository agentRepository,
            DiscoveryEndpointRepository endpointRepository,
            AgentTaskService taskService,
            ModelTelemetryService telemetryService,
            KnowledgeService knowledgeService,
            ManagementProperties properties
    ) {
        this.valueRepository = valueRepository;
        this.attributionRepository = attributionRepository;
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
        if (existing == null && !blank(request.sourceSystem()) && !blank(request.sourceRecordKey())) {
            existing = valueRepository.findBySourceSystemAndSourceRecordKey(request.sourceSystem().trim(), request.sourceRecordKey().trim()).orElse(null);
        }
        if (existing != null) {
            return ManagementDtos.from(existing, attributionRepository.findByValueEventId(existing.id));
        }
        List<ManagementDtos.AgentContributionRequest> contributions = request.agentContributions() == null ? List.of() : request.agentContributions();
        validateAttribution(request.agentId(), request.endpointKey(), contributions);
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
        event.currency = request.currency() == null || request.currency().isBlank() ? "USD" : request.currency().trim().toUpperCase(java.util.Locale.ROOT);
        event.metricName = normalize(request.metricName(), 128);
        event.metricUnit = normalize(request.metricUnit(), 32);
        event.baselineValue = request.baselineValue();
        event.currentValue = request.currentValue();
        event.improvementDirection = normalize(request.improvementDirection(), 32);
        event.sourceType = firstNonBlank(normalize(request.sourceType(), 32), "MANUAL").toUpperCase();
        event.sourceSystem = normalize(request.sourceSystem(), 128);
        event.sourceRecordKey = normalize(request.sourceRecordKey(), 128);
        event.confidence = request.confidence() == null ? BigDecimal.ONE : request.confidence();
        event.measurementWindowStart = request.measurementWindowStart();
        event.measurementWindowEnd = request.measurementWindowEnd();
        if ((event.baselineValue == null) != (event.currentValue == null)) {
            throw new BusinessException("业务指标必须同时提供基线值和当前值");
        }
        if (event.baselineValue != null && event.baselineValue.signum() == 0) {
            throw new BusinessException("业务指标基线值不能为 0");
        }
        if (!java.util.Set.of("MANUAL", "BUSINESS_SYSTEM", "DERIVED").contains(event.sourceType)) {
            throw new BusinessException("不支持的业务价值数据来源");
        }
        if ((event.sourceSystem == null) != (event.sourceRecordKey == null)) {
            throw new BusinessException("来源系统和来源记录标识必须同时提供");
        }
        if ("BUSINESS_SYSTEM".equals(event.sourceType) && event.sourceSystem == null) {
            throw new BusinessException("业务系统导入必须提供来源系统和记录标识");
        }
        if (event.confidence.signum() < 0 || event.confidence.compareTo(BigDecimal.ONE) > 0) {
            throw new BusinessException("可信度必须在 0 到 1 之间");
        }
        if ((event.measurementWindowStart == null) != (event.measurementWindowEnd == null)) {
            throw new BusinessException("统计窗口必须同时提供开始和结束时间");
        }
        if (event.measurementWindowStart != null && !event.measurementWindowEnd.isAfter(event.measurementWindowStart)) {
            throw new BusinessException("统计窗口结束时间必须晚于开始时间");
        }
        event.observedAt = request.observedAt() == null ? Instant.now() : request.observedAt();
        ManagementValueEvent saved = valueRepository.save(event);
        List<ManagementValueAttribution> savedAttributions = saveAttributions(saved.id, contributions);
        return ManagementDtos.from(saved, savedAttributions);
    }

    @Transactional(readOnly = true)
    public com.eap.platform.common.HistoryPage<ManagementDtos.ValueEventResponse> pageValues(com.eap.platform.common.EventHistoryQuery query) {
        if(query.status != null && !query.status.isBlank()) throw new com.eap.platform.common.BusinessException("价值记录不支持状态筛选");
        var pageable = query.pageable();
        var spec = query.<ManagementValueEvent>specification(null, null, "eventKey", "description", "validatedBy", "valueType", "sourceSystem", "sourceRecordKey")
            .and(query.context(false));
        if(query.getCurrency()!=null && !query.getCurrency().isBlank())
            spec=spec.and((root,q,cb)->cb.equal(cb.upper(cb.trim(root.get("currency"))),query.getCurrency().trim().toUpperCase(java.util.Locale.ROOT)));
        if(query.getAgentId()!=null) spec=spec.and((root,q,cb)->{
            var sub=q.subquery(Long.class);
            var contribution=sub.from(ManagementValueAttribution.class);
            sub.select(contribution.get("valueEventId")).where(cb.equal(contribution.get("agentId"),query.getAgentId()), cb.equal(contribution.get("valueEventId"),root.get("id")));
            return cb.or(cb.equal(root.get("agentId"),query.getAgentId()),cb.exists(sub));
        });
        var page=valueRepository.findAll(spec,pageable);
        var attributions=attributionsByEvent(page.getContent());
        return com.eap.platform.common.HistoryPage.from(page.map(value->ManagementDtos.from(value,attributions.getOrDefault(value.id,List.of()))));
    }

    @Transactional(readOnly = true)
    public List<ManagementDtos.ValueEventResponse> listValues(int limit) {
        List<ManagementValueEvent> values = valueRepository.findTop500ByOrderByObservedAtDesc().stream().limit(limit).toList();
        Map<Long, List<ManagementValueAttribution>> attributions = attributionsByEvent(values);
        return values.stream().map(value -> ManagementDtos.from(value, attributions.getOrDefault(value.id, List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public ManagementDtos.ManagementSummary summary(int windowHours) {
        return summary(windowHours, null);
    }

    @Transactional(readOnly = true)
    public ManagementDtos.ManagementSummary summary(int windowHours, String requestedCurrency) {
        Instant since = Instant.now().minus(windowHours, ChronoUnit.HOURS);
        List<ManagementValueEvent> allValues = valueRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(since);
        java.util.SortedSet<String> currencies = new java.util.TreeSet<>(telemetryService.currencies(windowHours));
        allValues.forEach(value -> currencies.add(currencyCode(value.currency)));
        String currency = requestedCurrency != null && !requestedCurrency.isBlank() ? currencyCode(requestedCurrency)
                : currencies.size() == 1 ? currencies.first() : currencies.isEmpty() ? "USD" : null;
        boolean mixedCurrencies = currency == null;
        ModelTelemetryDtos.PhysicalTelemetrySummary telemetry = telemetryService.summary(windowHours, currency);
        KnowledgeDtos.KnowledgeSummary knowledge = knowledgeService.summary(windowHours);
        List<AgentTaskDtos.TaskResponse> tasks = taskService.list().stream()
                .filter(task -> task.createdAt() != null && !task.createdAt().isBefore(since))
                .toList();
        List<ManagementValueEvent> values = allValues.stream()
                .filter(value -> currency == null || currency.equals(currencyCode(value.currency))).toList();
        Map<Long, List<ManagementValueAttribution>> attributions = attributionsByEvent(values);

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
            List<ManagementValueAttribution> contributions = attributions.getOrDefault(value.id, List.of());
            if (!contributions.isEmpty()) {
                contributions.forEach(item -> {
                    String key = "agent:" + item.agentId;
                    usage.computeIfAbsent(key, ignored -> new ManagementUsageAccumulator(
                            key, agentNames.getOrDefault(item.agentId, "Agent " + item.agentId), item.agentId, null
                    )).addValue(value, item.contributionWeight);
                });
            } else {
                String key = value.agentId != null ? "agent:" + value.agentId : "endpoint:" + value.endpointKey;
                usage.computeIfAbsent(key, ignored -> new ManagementUsageAccumulator(
                        key,
                        value.agentId != null ? agentNames.getOrDefault(value.agentId, "Agent " + value.agentId) : value.endpointKey,
                        value.agentId,
                        value.endpointKey
                )).addValue(value, BigDecimal.ONE);
            }
        });

        BigDecimal totalCost = telemetry.moneyCost();
        BigDecimal valueAmount = values.stream().map(value -> value.benefitMoney).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netBenefit = valueAmount.subtract(totalCost);
        List<BigDecimal> measuredRates = values.stream().map(ManagementDtos::improvementRate).filter(java.util.Objects::nonNull).toList();
        double averageImprovementRate = measuredRates.isEmpty() ? 0.0 : measuredRates.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
        long efficiencyImpactEvents = values.stream().filter(value -> isEfficiencyType(value.valueType)).count();
        long businessImpactEvents = values.stream().filter(value -> isBusinessImpactType(value.valueType)).count();
        long businessSystemValueEvents = values.stream().filter(value -> "BUSINESS_SYSTEM".equalsIgnoreCase(value.sourceType)).count();
        long multiAgentValueEvents = values.stream().filter(value -> !attributions.getOrDefault(value.id, List.of()).isEmpty()).count();
        double averageConfidence = values.isEmpty() ? 0.0 : values.stream()
                .map(value -> value.confidence == null ? BigDecimal.ONE : value.confidence)
                .mapToDouble(BigDecimal::doubleValue).average().orElse(0.0);
        return new ManagementDtos.ManagementSummary(
                windowHours, currency, List.copyOf(currencies), mixedCurrencies,
                tasks.size(),
                tasks.stream().filter(task -> task.status() == AgentTaskStatus.SUCCEEDED).count(),
                taskSuccessRate(tasks),
                telemetry.totalCalls(),
                telemetry.totalTokens(),
                mixedCurrencies ? null : totalCost,
                mixedCurrencies ? null : valueAmount,
                mixedCurrencies ? null : netBenefit,
                mixedCurrencies ? null : roi(valueAmount, totalCost),
                knowledge.adoptedInteractions(),
                knowledge.reusedInteractions(),
                knowledge.adoptionRate(),
                knowledge.reuseRate(),
                measuredRates.size(),
                averageImprovementRate,
                efficiencyImpactEvents,
                businessImpactEvents,
                businessSystemValueEvents,
                multiAgentValueEvents,
                averageConfidence,
                !values.isEmpty(),
                values.isEmpty() ? null : values.get(0).observedAt,
                usage.values().stream().sorted(Comparator.comparingLong(ManagementUsageAccumulator::totalTokens).reversed()).map(item -> item.toResponse(!mixedCurrencies)).toList(),
                tasks.stream().filter(task -> Boolean.TRUE.equals(task.problemResolved())).count(),
                tasks.stream().filter(task -> Boolean.FALSE.equals(task.problemResolved())).count(),
                tasks.stream().filter(task -> task.problemResolved() == null).count()
        );
    }

    private void validateAttribution(Long agentId, String endpointKey, List<ManagementDtos.AgentContributionRequest> contributions) {
        if (agentId == null && (endpointKey == null || endpointKey.isBlank()) && contributions.isEmpty()) {
            throw new BusinessException("业务价值事件必须关联 Agent 或发现端点");
        }
        if (agentId != null && !agentRepository.existsById(agentId)) {
            throw new BusinessException("智能体不存在");
        }
        if (endpointKey != null && !endpointKey.isBlank() && endpointRepository.findByEndpointKey(endpointKey).isEmpty()) {
            throw new BusinessException("发现端点不存在");
        }
        java.util.Set<Long> agentIds = new java.util.LinkedHashSet<>();
        BigDecimal totalWeight = BigDecimal.ZERO;
        for (ManagementDtos.AgentContributionRequest contribution : contributions) {
            if (contribution.agentId() == null || contribution.contributionWeight() == null) {
                throw new BusinessException("贡献归因必须提供智能体和权重");
            }
            if (!agentIds.add(contribution.agentId())) throw new BusinessException("同一智能体不能重复归因");
            if (!agentRepository.existsById(contribution.agentId())) throw new BusinessException("贡献归因中的智能体不存在");
            totalWeight = totalWeight.add(contribution.contributionWeight());
        }
        if (!contributions.isEmpty() && totalWeight.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.000001")) > 0) {
            throw new BusinessException("多智能体贡献权重之和必须为 1");
        }
    }

    private List<ManagementValueAttribution> saveAttributions(
            Long valueEventId,
            List<ManagementDtos.AgentContributionRequest> contributions
    ) {
        if (contributions.isEmpty()) return List.of();
        return attributionRepository.saveAll(contributions.stream().map(item -> {
            ManagementValueAttribution attribution = new ManagementValueAttribution();
            attribution.valueEventId = valueEventId;
            attribution.agentId = item.agentId();
            attribution.contributionWeight = item.contributionWeight();
            attribution.attributionMethod = firstNonBlank(normalize(item.attributionMethod(), 32), "BUSINESS_CONFIRMED").toUpperCase();
            attribution.evidenceReference = normalize(item.evidenceReference(), 256);
            return attribution;
        }).toList());
    }

    private Map<Long, List<ManagementValueAttribution>> attributionsByEvent(List<ManagementValueEvent> values) {
        if (values.isEmpty()) return Map.of();
        return attributionRepository.findByValueEventIdIn(values.stream().map(value -> value.id).toList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(item -> item.valueEventId, LinkedHashMap::new, java.util.stream.Collectors.toList()));
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

    private String currencyCode(String value) {
        return value == null || value.isBlank() ? "USD" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!blank(value)) return value.trim();
        }
        return null;
    }

    private String normalize(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }

    private boolean isEfficiencyType(String value) {
        return "EFFICIENCY_GAIN".equalsIgnoreCase(value)
                || "LABOR_SAVING".equalsIgnoreCase(value)
                || "COST_REDUCTION".equalsIgnoreCase(value);
    }

    private boolean isBusinessImpactType(String value) {
        return "REVENUE".equalsIgnoreCase(value)
                || "LOSS_AVOIDANCE".equalsIgnoreCase(value)
                || "QUALITY_IMPROVEMENT".equalsIgnoreCase(value);
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
        private long measuredImpactEvents;
        private BigDecimal improvementRateTotal = BigDecimal.ZERO;
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

        void addValue(ManagementValueEvent value, BigDecimal weight) {
            validatedValue = validatedValue.add(value.benefitMoney.multiply(weight));
            BigDecimal rate = ManagementDtos.improvementRate(value);
            if (rate != null) {
                measuredImpactEvents++;
                improvementRateTotal = improvementRateTotal.add(rate);
            }
        }

        long totalTokens() {
            return totalTokens;
        }

        ManagementDtos.AgentManagementUsage toResponse(boolean monetaryComparable) {
            BigDecimal netBenefit = validatedValue.subtract(totalCost);
            BigDecimal roi = totalCost.signum() == 0 ? BigDecimal.ZERO.setScale(4) : netBenefit.divide(totalCost, 4, RoundingMode.HALF_UP);
            return new ManagementDtos.AgentManagementUsage(
                    key, name, agentId, endpointKey, tasks, succeededTasks, modelCalls, knowledgeInteractions,
                    totalTokens, monetaryComparable ? totalCost : null, monetaryComparable ? validatedValue : null,
                    monetaryComparable ? netBenefit : null, monetaryComparable ? roi : null,
                    knowledgeInteractions == 0 ? 0.0 : (double) adopted / knowledgeInteractions,
                    knowledgeInteractions == 0 ? 0.0 : (double) reused / knowledgeInteractions,
                    measuredImpactEvents,
                    measuredImpactEvents == 0 ? 0.0 : improvementRateTotal.divide(BigDecimal.valueOf(measuredImpactEvents), 4, RoundingMode.HALF_UP).doubleValue()
            );
        }
    }
}
