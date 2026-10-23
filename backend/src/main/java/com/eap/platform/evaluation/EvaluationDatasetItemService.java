package com.eap.platform.evaluation;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.audit.AuditLog;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.common.TraceContext;
import com.eap.platform.observability.ContentCaptureMode;
import com.eap.platform.observability.ObservationProperties;
import com.eap.platform.observability.SensitiveContentSanitizer;
import com.eap.platform.observability.TraceSpanObservationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class EvaluationDatasetItemService {
    private static final int MAX_JSON_BYTES = 64 * 1024;
    private static final Set<String> LABELS = Set.of("PASS", "FAIL", "ABSTAIN");

    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationDatasetItemRepository itemRepository;
    private final EvaluatorDefinitionRepository evaluatorRepository;
    private final AgentRepository agentRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final ObservationProperties observationProperties;
    private final SensitiveContentSanitizer sensitiveContentSanitizer;
    private final TraceSpanObservationRepository traceSpanRepository;

    public EvaluationDatasetItemService(
            EvaluationDatasetRepository datasetRepository,
            EvaluationDatasetItemRepository itemRepository,
            EvaluatorDefinitionRepository evaluatorRepository,
            AgentRepository agentRepository,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            ObservationProperties observationProperties,
            SensitiveContentSanitizer sensitiveContentSanitizer,
            TraceSpanObservationRepository traceSpanRepository
    ) {
        this.datasetRepository = datasetRepository;
        this.itemRepository = itemRepository;
        this.evaluatorRepository = evaluatorRepository;
        this.agentRepository = agentRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.observationProperties = observationProperties;
        this.sensitiveContentSanitizer = sensitiveContentSanitizer;
        this.traceSpanRepository = traceSpanRepository;
    }

    @Transactional
    public EvaluationDtos.DatasetItemResponse create(String datasetKey, EvaluationDtos.DatasetItemCreateRequest request) {
        EvaluationDataset dataset = dataset(datasetKey);
        String itemKey = normalize(request.itemKey(), 128);
        if (itemRepository.findByDatasetIdAndItemKey(dataset.id, itemKey).isPresent()) {
            throw new BusinessException("数据集样本标识已存在");
        }
        EvaluatorDefinition evaluator = evaluatorRepository.findByEvaluatorKey(normalizeKey(request.evaluatorKey(), 128))
                .orElseThrow(() -> new BusinessException("评估器不存在"));
        if (request.agentId() != null && !agentRepository.existsById(request.agentId())) {
            throw new BusinessException("智能体不存在");
        }
        validateJson(request.input(), "样本输入");
        validateJson(request.actual(), "样本实际结果");
        if (request.expected() != null) validateJson(request.expected(), "样本期望结果");

        EvaluationDatasetItem item = new EvaluationDatasetItem();
        item.datasetId = dataset.id;
        item.itemKey = itemKey == null || itemKey.isBlank() ? UUID.randomUUID().toString() : itemKey;
        item.evaluatorKey = evaluator.evaluatorKey;
        item.evaluatorVersion = firstNonBlank(normalize(request.evaluatorVersion(), 64), evaluator.version);
        item.agentId = request.agentId();
        item.traceId = normalize(request.traceId(), 128);
        item.spanId = normalize(request.spanId(), 128);
        if (request.verifySourceReference()) freezeSourceReference(item);
        item.inputJson = persistObservation(request.input());
        item.expectedJson = request.expected() == null ? null : persistObservation(request.expected());
        item.actualJson = persistObservation(request.actual());
        EvaluationDatasetItem saved = itemRepository.save(item);
        dataset.itemCount = Math.max(dataset.itemCount + 1, Math.toIntExact(itemRepository.countByDatasetId(dataset.id)));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<EvaluationDtos.DatasetItemResponse> list(String datasetKey, int requestedLimit) {
        EvaluationDataset dataset = dataset(datasetKey);
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        return itemRepository.findByDatasetIdOrderByCreatedAtAscIdAsc(dataset.id, PageRequest.of(0, limit)).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public EvaluationDtos.DatasetItemResponse annotate(
            String datasetKey, String itemKey, EvaluationDtos.DatasetItemAnnotationRequest request
    ) {
        return annotate(datasetKey, itemKey, request, null);
    }

    @Transactional
    public EvaluationDtos.DatasetItemResponse annotate(
            String datasetKey, String itemKey, EvaluationDtos.DatasetItemAnnotationRequest request, String authenticatedActor
    ) {
        EvaluationDataset dataset = dataset(datasetKey);
        EvaluationDatasetItem item = itemRepository.findByDatasetIdAndItemKey(dataset.id, normalize(itemKey, 128))
                .orElseThrow(() -> new BusinessException("数据集样本不存在"));
        String beforeLabel = item.groundTruthLabel;
        BigDecimal beforeScore = item.groundTruthScore;
        String label = normalizeKey(request.groundTruthLabel(), 16);
        if (!LABELS.contains(label)) {
            throw new BusinessException("标注结果必须为 PASS、FAIL 或 ABSTAIN");
        }
        BigDecimal score = request.groundTruthScore();
        if (score == null) {
            score = switch (label) {
                case "PASS" -> BigDecimal.ONE.setScale(6);
                case "FAIL" -> BigDecimal.ZERO.setScale(6);
                default -> new BigDecimal("0.500000");
            };
        }
        item.groundTruthLabel = label;
        item.groundTruthScore = score;
        item.annotationRationale = normalize(request.rationale(), 2048);
        item.annotatedBy = normalize(firstNonBlank(authenticatedActor, request.annotatedBy(), "控制台用户"), 128);
        item.annotatedAt = Instant.now();
        EvaluationDatasetItem saved = itemRepository.save(item);
        writeAnnotationAudit(saved, beforeLabel, beforeScore);
        return toResponse(saved);
    }

    private EvaluationDataset dataset(String datasetKey) {
        return datasetRepository.findByDatasetKey(normalizeKey(datasetKey, 128))
                .orElseThrow(() -> new BusinessException("评估数据集不存在"));
    }

    private EvaluationDtos.DatasetItemResponse toResponse(EvaluationDatasetItem item) {
        return new EvaluationDtos.DatasetItemResponse(
                item.id, item.itemKey, item.evaluatorKey, item.evaluatorVersion, item.agentId,
                item.traceId, item.spanId, parseJson(item.inputJson), parseNullableJson(item.expectedJson),
                parseJson(item.actualJson), item.groundTruthLabel, item.groundTruthScore,
                item.annotationRationale, item.annotatedBy, item.annotatedAt, item.createdAt, item.updatedAt,
                new EvaluationDtos.DatasetSourceReference(
                        item.sourceObservationId == null ? "MANUAL_UNVERIFIED" : "OBSERVATION_MATCHED",
                        item.sourceObservationId, item.sourceEndpointKey, item.sourceObservedAt, item.sourceCheckedAt)
        );
    }

    private void freezeSourceReference(EvaluationDatasetItem item) {
        if (item.agentId == null || item.traceId == null || item.traceId.isBlank()
                || item.spanId == null || item.spanId.isBlank()) {
            throw new BusinessException("核对观测引用需要同时填写 Agent ID、Trace ID 和 Span ID");
        }
        var observation = traceSpanRepository.findByTraceIdAndSpanId(item.traceId, item.spanId)
                .orElseThrow(() -> new BusinessException("未找到对应观测记录，无法核对来源引用"));
        // MySQL collation can match approximate keys. Never bind a different Java string.
        if (!item.traceId.equals(observation.getTraceId()) || !item.spanId.equals(observation.getSpanId())
                || !item.agentId.equals(observation.getAgentId())) {
            throw new BusinessException("观测记录与 Agent/Trace/Span 引用不一致，无法保存核对快照");
        }
        item.sourceObservationId = observation.getId();
        item.sourceEndpointKey = observation.getEndpointKey();
        item.sourceObservedAt = observation.getObservedAt();
        item.sourceCheckedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    private JsonNode parseNullableJson(String json) {
        return json == null ? null : parseJson(json);
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private void validateJson(JsonNode value, String name) {
        if (value == null || value.isMissingNode()) throw new BusinessException(name + "不能为空");
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new BusinessException(name + "超过 64KB 限制");
        }
    }

    private String persistObservation(JsonNode value) {
        String serialized = value.toString();
        if (observationProperties.getCaptureMode() == ContentCaptureMode.OFF) return null;
        if (observationProperties.getCaptureMode() == ContentCaptureMode.HASH_ONLY) {
            ObjectNode fingerprint = objectMapper.createObjectNode();
            fingerprint.put("captureMode", ContentCaptureMode.HASH_ONLY.name());
            fingerprint.put("sha256", sha256(serialized));
            fingerprint.put("bytes", serialized.getBytes(StandardCharsets.UTF_8).length);
            fingerprint.put("jsonType", value.isObject() ? "object" : value.isArray() ? "array" : value.getNodeType().name().toLowerCase(Locale.ROOT));
            return fingerprint.toString();
        }
        SensitiveContentSanitizer.SanitizedContent sanitized = sensitiveContentSanitizer.sanitize(serialized);
        ObjectNode redacted = objectMapper.createObjectNode();
        redacted.put("captureMode", ContentCaptureMode.REDACTED.name());
        redacted.put("sensitivity", sanitized.sensitivity());
        redacted.put("dataCategory", sanitized.dataCategory());
        redacted.put("withheld", sanitized.safeContent() == null);
        if (sanitized.safeContent() != null) redacted.put("content", sanitized.safeContent());
        return redacted.toString();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String normalize(String value, int max) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private String normalizeKey(String value, int max) {
        String normalized = normalize(value, max);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String firstNonBlank(String first, String second, String fallback) {
        return first != null && !first.isBlank() ? first : firstNonBlank(second, fallback);
    }

    private void writeAnnotationAudit(EvaluationDatasetItem item, String beforeLabel, BigDecimal beforeScore) {
        AuditLog audit = new AuditLog();
        audit.setActor(firstNonBlank(item.annotatedBy, "system"));
        audit.setAction("ANNOTATE_EVALUATION_DATASET_ITEM");
        audit.setResourceType("EVALUATION_DATASET_ITEM");
        audit.setResourceId(String.valueOf(item.id));
        ObjectNode before = objectMapper.createObjectNode();
        if (beforeLabel == null) before.putNull("groundTruthLabel"); else before.put("groundTruthLabel", beforeLabel);
        if (beforeScore == null) before.putNull("groundTruthScore"); else before.put("groundTruthScore", beforeScore);
        ObjectNode after = objectMapper.createObjectNode();
        after.put("groundTruthLabel", item.groundTruthLabel);
        after.put("groundTruthScore", item.groundTruthScore);
        audit.setBeforeJson(before.toString());
        audit.setAfterJson(after.toString());
        audit.setResult("SUCCESS");
        audit.setTraceId(TraceContext.traceId());
        audit.setCreatedAt(Instant.now());
        auditLogRepository.save(audit);
    }
}
