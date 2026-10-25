package com.eap.platform.knowledge;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.discovery.DiscoveryEndpointRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeService {
    private final KnowledgeInteractionRepository interactionRepository;
    private final KnowledgeTraceStepRepository stepRepository;
    private final AgentRepository agentRepository;
    private final DiscoveryEndpointRepository endpointRepository;
    private final KnowledgeProperties properties;
    private final ObjectMapper objectMapper;

    public KnowledgeService(
            KnowledgeInteractionRepository interactionRepository,
            KnowledgeTraceStepRepository stepRepository,
            AgentRepository agentRepository,
            DiscoveryEndpointRepository endpointRepository,
            KnowledgeProperties properties,
            ObjectMapper objectMapper
    ) {
        this.interactionRepository = interactionRepository;
        this.stepRepository = stepRepository;
        this.agentRepository = agentRepository;
        this.endpointRepository = endpointRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String ingestionToken() {
        return properties.getIngestionToken();
    }

    @Transactional
    public KnowledgeDtos.KnowledgeInteractionResponse record(KnowledgeDtos.KnowledgeInteractionRequest request) {
        KnowledgeInteraction existing = interactionRepository.findByInteractionKey(request.interactionKey()).orElse(null);
        if (existing != null) {
            return toResponse(existing);
        }
        validateAttribution(request.agentId(), request.endpointKey());
        KnowledgeInteraction interaction = new KnowledgeInteraction();
        interaction.interactionKey = request.interactionKey();
        interaction.traceId = blankToNull(request.traceId());
        interaction.agentId = request.agentId();
        interaction.endpointKey = blankToNull(request.endpointKey());
        interaction.taskId = request.taskId();
        interaction.inputType = request.inputType();
        interaction.inputSourceCount = request.inputSourceCount();
        interaction.inputTokenCount = request.inputTokenCount();
        interaction.inputSummary = sanitizeText(request.inputSummary(), 2048);
        interaction.inputRefsJson = toJson(request.inputKnowledgeRefs());
        interaction.outputType = blankToNull(request.outputType());
        interaction.outputSourceCount = request.outputSourceCount();
        interaction.outputTokenCount = request.outputTokenCount();
        interaction.outputSummary = sanitizeText(request.outputSummary(), 2048);
        interaction.outputRefsJson = toJson(request.outputKnowledgeRefs());
        interaction.status = request.status();
        interaction.adopted = Boolean.TRUE.equals(request.adopted());
        interaction.reused = Boolean.TRUE.equals(request.reused());
        interaction.qualityScore = request.qualityScore();
        interaction.startedAt = request.startedAt();
        interaction.completedAt = request.completedAt();
        List<KnowledgeDtos.KnowledgeStepRequest> steps = request.processSteps() == null ? List.of() : request.processSteps();
        interaction.processStepCount = steps.size();
        interaction.processTokenCount = steps.stream().mapToLong(step -> step.inputTokens() + step.outputTokens()).sum();
        interaction.processLatencyMs = steps.stream().mapToLong(KnowledgeDtos.KnowledgeStepRequest::latencyMs).sum();
        interaction = interactionRepository.save(interaction);
        for (KnowledgeDtos.KnowledgeStepRequest item : steps) {
            KnowledgeTraceStep step = new KnowledgeTraceStep();
            step.interactionId = interaction.id;
            step.stepOrder = item.stepOrder();
            step.stepType = item.stepType();
            step.name = item.name();
            step.status = item.status();
            step.sourceCount = item.sourceCount();
            step.inputTokens = item.inputTokens();
            step.outputTokens = item.outputTokens();
            step.latencyMs = item.latencyMs();
            step.refsJson = toJson(item.knowledgeRefs());
            step.metadataJson = toJson(item.metadata());
            stepRepository.save(step);
        }
        return toResponse(interaction);
    }

    @Transactional
    public KnowledgeDtos.KnowledgeInteractionResponse updateOutcome(Long id, KnowledgeDtos.OutcomeRequest request) {
        KnowledgeInteraction interaction = interactionRepository.findById(id)
                .orElseThrow(() -> new BusinessException("知识交互不存在"));
        interaction.adopted = request.adopted();
        interaction.reused = request.reused();
        interaction.qualityScore = request.qualityScore();
        return toResponse(interaction);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDtos.KnowledgeInteractionResponse> list(int limit) {
        return list(limit, null, 0);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDtos.KnowledgeInteractionResponse> list(int limit, Long agentId, int page) {
        PageRequest pageable = PageRequest.of(page, limit);
        List<KnowledgeInteraction> rows = agentId == null
                ? interactionRepository.findAllByOrderByCreatedAtDescIdDesc(pageable)
                : interactionRepository.findByAgentIdOrderByCreatedAtDescIdDesc(agentId, pageable);
        return rows.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeDtos.KnowledgeInteractionResponse get(Long id) {
        return toResponse(interactionRepository.findById(id)
                .orElseThrow(() -> new BusinessException("知识交互不存在")));
    }

    @Transactional(readOnly = true)
    public KnowledgeDtos.KnowledgeSummary summary(int windowHours) {
        Instant since = Instant.now().minus(windowHours, ChronoUnit.HOURS);
        List<KnowledgeInteraction> interactions = interactionRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(since);
        Map<Long, String> agentNames = new LinkedHashMap<>();
        agentRepository.findAll().forEach(agent -> agentNames.put(agent.getId(), agent.getName()));
        long succeeded = interactions.stream().filter(item -> item.status == KnowledgeInteractionStatus.SUCCEEDED).count();
        long failed = interactions.stream().filter(item -> item.status == KnowledgeInteractionStatus.FAILED).count();
        long adopted = interactions.stream().filter(item -> Boolean.TRUE.equals(item.adopted)).count();
        long reused = interactions.stream().filter(item -> Boolean.TRUE.equals(item.reused)).count();
        long qualityCount = interactions.stream().filter(item -> item.qualityScore != null).count();
        double quality = qualityCount == 0 ? 0.0 : interactions.stream().filter(item -> item.qualityScore != null).mapToInt(item -> item.qualityScore).average().orElse(0.0);
        long completed = succeeded + failed;
        Map<String, KnowledgeUsageAccumulator> usage = new LinkedHashMap<>();
        interactions.forEach(item -> {
            String key = item.agentId != null ? "agent:" + item.agentId : "endpoint:" + item.endpointKey;
            String name = item.agentId != null ? agentNames.getOrDefault(item.agentId, "Agent " + item.agentId) : item.endpointKey;
            usage.computeIfAbsent(key, ignored -> new KnowledgeUsageAccumulator(key, name, item.agentId, item.endpointKey)).add(item);
        });
        return new KnowledgeDtos.KnowledgeSummary(
                windowHours, interactions.size(), succeeded, failed,
                completed == 0 ? 0.0 : (double) succeeded / completed,
                interactions.stream().mapToLong(item -> item.inputTokenCount).sum(),
                interactions.stream().mapToLong(item -> item.outputTokenCount).sum(),
                interactions.stream().mapToLong(item -> item.inputSourceCount).sum(),
                interactions.stream().mapToLong(item -> item.outputSourceCount).sum(),
                adopted, reused,
                interactions.isEmpty() ? 0.0 : (double) adopted / interactions.size(),
                interactions.isEmpty() ? 0.0 : (double) reused / interactions.size(),
                quality,
                interactions.isEmpty() ? 0L : interactions.stream().mapToLong(item -> item.processLatencyMs).sum() / interactions.size(),
                interactions.isEmpty() ? null : interactions.get(0).createdAt,
                usage.values().stream().sorted(Comparator.comparingLong(KnowledgeUsageAccumulator::interactions).reversed()).map(KnowledgeUsageAccumulator::toResponse).toList()
        );
    }

    private KnowledgeDtos.KnowledgeInteractionResponse toResponse(KnowledgeInteraction interaction) {
        boolean otlp = interaction.interactionKey != null && interaction.interactionKey.startsWith("otlp-knowledge-");
        return new KnowledgeDtos.KnowledgeInteractionResponse(
                interaction.id, interaction.interactionKey, interaction.traceId, interaction.agentId,
                interaction.endpointKey, interaction.taskId, interaction.inputType, interaction.inputSourceCount,
                interaction.inputTokenCount, otlp ? null : interaction.inputSummary, publicRefs(interaction.inputRefsJson, otlp),
                interaction.outputType, interaction.outputSourceCount, interaction.outputTokenCount,
                otlp ? null : interaction.outputSummary, publicRefs(interaction.outputRefsJson, otlp), interaction.status,
                interaction.adopted, interaction.reused, interaction.qualityScore, interaction.processStepCount,
                interaction.processTokenCount, interaction.processLatencyMs, interaction.startedAt,
                interaction.completedAt, interaction.createdAt,
                stepRepository.findByInteractionIdOrderByStepOrderAsc(interaction.id).stream().map(step -> toStepResponse(step, otlp)).toList()
        );
    }

    private KnowledgeDtos.KnowledgeStepResponse toStepResponse(KnowledgeTraceStep step, boolean otlp) {
        return new KnowledgeDtos.KnowledgeStepResponse(
                step.id, step.stepOrder, step.stepType, step.name, step.status, step.sourceCount,
                step.inputTokens, step.outputTokens, step.latencyMs, publicRefs(step.refsJson, otlp), step.observedAt
        );
    }

    private List<String> publicRefs(String value, boolean otlp) {
        var refs = fromJsonList(value);
        return otlp ? refs.stream().filter(java.util.Objects::nonNull).map(com.eap.platform.observability.TraceMetadata::reference).toList() : refs;
    }

    private List<String> fromJsonList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return Arrays.asList(objectMapper.readValue(value, String[].class));
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private void validateAttribution(Long agentId, String endpointKey) {
        if (agentId == null && (endpointKey == null || endpointKey.isBlank())) {
            throw new BusinessException("知识交互必须关联 Agent 或发现端点");
        }
        if (agentId != null && !agentRepository.existsById(agentId)) {
            throw new BusinessException("智能体不存在");
        }
        if (endpointKey != null && !endpointKey.isBlank() && endpointRepository.findByEndpointKey(endpointKey).isEmpty()) {
            throw new BusinessException("发现端点不存在");
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(sanitizeValue(value));
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private Object sanitizeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            map.entrySet().stream().limit(64).forEach(entry -> {
                String key = String.valueOf(entry.getKey());
                String normalized = key.toLowerCase();
                sanitized.put(key, normalized.contains("secret") || normalized.contains("token")
                        || normalized.contains("password") || normalized.contains("apikey") || normalized.contains("api_key")
                        ? "[REDACTED]" : sanitizeValue(entry.getValue()));
            });
            return sanitized;
        }
        if (value instanceof List<?> list) {
            return list.stream().limit(100).map(this::sanitizeValue).toList();
        }
        if (value instanceof String string) {
            return sanitizeText(string, 512);
        }
        return value;
    }

    private String sanitizeText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String sanitized = value
                .replaceAll("(?i)(sk-[a-z0-9]{12,})", "[REDACTED_KEY]")
                .replaceAll("(?i)(api[_-]?key|token|secret|password)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static final class KnowledgeUsageAccumulator {
        private final String key;
        private final String name;
        private final Long agentId;
        private final String endpointKey;
        private long interactions;
        private long inputTokens;
        private long outputTokens;
        private long adopted;
        private long reused;
        private long qualityCount;
        private long qualityTotal;
        private Instant lastInteractionAt;

        private KnowledgeUsageAccumulator(String key, String name, Long agentId, String endpointKey) {
            this.key = key;
            this.name = name;
            this.agentId = agentId;
            this.endpointKey = endpointKey;
        }

        void add(KnowledgeInteraction item) {
            interactions++;
            inputTokens += item.inputTokenCount;
            outputTokens += item.outputTokenCount;
            adopted += Boolean.TRUE.equals(item.adopted) ? 1 : 0;
            reused += Boolean.TRUE.equals(item.reused) ? 1 : 0;
            if (item.qualityScore != null) {
                qualityCount++;
                qualityTotal += item.qualityScore;
            }
            if (lastInteractionAt == null || item.createdAt.isAfter(lastInteractionAt)) {
                lastInteractionAt = item.createdAt;
            }
        }

        long interactions() {
            return interactions;
        }

        KnowledgeDtos.KnowledgeAgentUsage toResponse() {
            return new KnowledgeDtos.KnowledgeAgentUsage(
                    key, name, agentId, endpointKey, interactions, inputTokens, outputTokens, adopted, reused,
                    interactions == 0 ? 0.0 : (double) adopted / interactions,
                    interactions == 0 ? 0.0 : (double) reused / interactions,
                    qualityCount == 0 ? 0.0 : (double) qualityTotal / qualityCount,
                    lastInteractionAt
            );
        }
    }
}
