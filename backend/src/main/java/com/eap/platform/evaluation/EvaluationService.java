package com.eap.platform.evaluation;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.observability.ContentCaptureMode;
import com.eap.platform.observability.ObservationProperties;
import com.eap.platform.observability.SensitiveContentSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class EvaluationService {
    private static final int MAX_JSON_BYTES = 64 * 1024;

    private final EvaluatorDefinitionRepository evaluatorRepository;
    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationResultRepository resultRepository;
    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;
    private final ObservationProperties observationProperties;
    private final SensitiveContentSanitizer sensitiveContentSanitizer;

    public EvaluationService(
            EvaluatorDefinitionRepository evaluatorRepository,
            EvaluationDatasetRepository datasetRepository,
            EvaluationResultRepository resultRepository,
            AgentRepository agentRepository,
            ObjectMapper objectMapper,
            ObservationProperties observationProperties,
            SensitiveContentSanitizer sensitiveContentSanitizer
    ) {
        this.evaluatorRepository = evaluatorRepository;
        this.datasetRepository = datasetRepository;
        this.resultRepository = resultRepository;
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
        this.observationProperties = observationProperties;
        this.sensitiveContentSanitizer = sensitiveContentSanitizer;
    }

    @Transactional
    public EvaluationDtos.EvaluatorResponse createEvaluator(EvaluationDtos.EvaluatorCreateRequest request) {
        String key = normalizeKey(request.evaluatorKey(), 128);
        if (evaluatorRepository.findByEvaluatorKey(key).isPresent()) {
            throw new BusinessException("评估器标识已存在");
        }
        EvaluatorDefinition item = new EvaluatorDefinition();
        item.evaluatorKey = key;
        item.name = normalize(request.name(), 256);
        item.description = normalize(request.description(), 2048);
        item.evaluatorType = request.evaluatorType();
        item.level = request.level();
        item.implementationKey = normalizeKey(request.implementationKey(), 128);
        item.version = normalize(request.version(), 64);
        item.threshold = request.threshold() == null ? new BigDecimal("0.500000") : request.threshold();
        item.enabled = true;
        return EvaluationDtos.EvaluatorResponse.from(evaluatorRepository.save(item));
    }

    @Transactional(readOnly = true)
    public List<EvaluationDtos.EvaluatorResponse> listEvaluators() {
        return evaluatorRepository.findAllByOrderByEvaluatorKeyAsc().stream()
                .map(EvaluationDtos.EvaluatorResponse::from).toList();
    }

    @Transactional
    public EvaluationDtos.DatasetResponse createDataset(EvaluationDtos.DatasetCreateRequest request) {
        String key = normalizeKey(request.datasetKey(), 128);
        if (datasetRepository.findByDatasetKey(key).isPresent()) {
            throw new BusinessException("评估数据集标识已存在");
        }
        if (request.itemCount() != null && request.itemCount() < 0) {
            throw new BusinessException("评估数据集条目数不能为负数");
        }
        EvaluationDataset item = new EvaluationDataset();
        item.datasetKey = key;
        item.name = normalize(request.name(), 256);
        item.description = normalize(request.description(), 2048);
        item.sourceType = firstNonBlank(normalize(request.sourceType(), 32), "MANUAL").toUpperCase(Locale.ROOT);
        item.version = firstNonBlank(normalize(request.version(), 64), "v1");
        item.itemCount = request.itemCount() == null ? 0 : request.itemCount();
        return EvaluationDtos.DatasetResponse.from(datasetRepository.save(item));
    }

    @Transactional(readOnly = true)
    public List<EvaluationDtos.DatasetResponse> listDatasets() {
        return datasetRepository.findAllByOrderByDatasetKeyAsc().stream()
                .map(EvaluationDtos.DatasetResponse::from).toList();
    }

    @Transactional
    public EvaluationDtos.EvaluationResultResponse evaluate(EvaluationDtos.EvaluationRequest request) {
        EvaluatorDefinition evaluator = evaluatorRepository.findByEvaluatorKey(normalizeKey(request.evaluatorKey(), 128))
                .orElseThrow(() -> new BusinessException("评估器不存在"));
        if (!evaluator.enabled) {
            throw new BusinessException("评估器未启用");
        }
        if (request.evaluatorVersion() != null && !request.evaluatorVersion().isBlank()
                && !evaluator.version.equals(request.evaluatorVersion().trim())) {
            throw new BusinessException("评估器版本不匹配");
        }
        if (request.agentId() != null && !agentRepository.existsById(request.agentId())) {
            throw new BusinessException("智能体不存在");
        }
        EvaluationDataset dataset = null;
        if (request.datasetKey() != null && !request.datasetKey().isBlank()) {
            dataset = datasetRepository.findByDatasetKey(normalizeKey(request.datasetKey(), 128))
                    .orElseThrow(() -> new BusinessException("评估数据集不存在"));
        }
        validateJson(request.input(), "评估输入");
        validateJson(request.actual(), "评估实际结果");
        if (request.expected() != null) validateJson(request.expected(), "评估期望结果");

        String traceId = normalize(request.traceId(), 128);
        String spanId = normalize(request.spanId(), 128);
        String persistedInput = persistObservation(request.input());
        String persistedExpected = request.expected() == null ? null : persistObservation(request.expected());
        String persistedActual = persistObservation(request.actual());
        String requestFingerprint = requestFingerprint(evaluator.id, evaluator.version,
                dataset == null ? null : dataset.id, request.agentId(), traceId, spanId,
                contentFingerprint(request.input()), contentFingerprint(request.expected()), contentFingerprint(request.actual()));
        if (request.resultKey() != null && !request.resultKey().isBlank()) {
            EvaluationResult existing = resultRepository.findByResultKey(request.resultKey().trim()).orElse(null);
            if (existing != null) {
                if (!sameRequest(existing, requestFingerprint, evaluator, dataset, request.agentId(), traceId, spanId)) {
                    throw new BusinessException("resultKey 已用于不同的评估请求");
                }
                return toResponse(existing, evaluator, dataset);
            }
        }

        Score score = score(evaluator.implementationKey, request.input(), request.expected(), request.actual());
        EvaluationResult result = new EvaluationResult();
        result.resultKey = firstNonBlank(normalize(request.resultKey(), 128), UUID.randomUUID().toString());
        result.evaluatorId = evaluator.id;
        result.evaluatorVersion = evaluator.version;
        result.requestFingerprint = requestFingerprint;
        result.datasetId = dataset == null ? null : dataset.id;
        result.agentId = request.agentId();
        result.traceId = traceId;
        result.spanId = spanId;
        result.level = evaluator.level;
        result.status = EvaluationStatus.COMPLETED;
        result.score = score.score();
        result.passed = score.score().compareTo(evaluator.threshold) >= 0;
        result.confidence = confidence(request.actual());
        result.rationale = score.rationale();
        result.inputJson = persistedInput;
        result.expectedJson = persistedExpected;
        result.actualJson = persistedActual;
        result.evidenceJson = score.evidence().toString();
        result.evaluatedAt = request.evaluatedAt() == null ? Instant.now() : request.evaluatedAt();
        EvaluationResult saved = resultRepository.save(result);
        return toResponse(saved, evaluator, dataset);
    }

    @Transactional(readOnly = true)
    public List<EvaluationDtos.EvaluationResultResponse> listResults(Long agentId, String traceId, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        List<EvaluationResult> results;
        if (agentId != null) {
            results = resultRepository.findTop500ByAgentIdOrderByEvaluatedAtDesc(agentId);
        } else if (traceId != null && !traceId.isBlank()) {
            results = resultRepository.findTop500ByTraceIdOrderByEvaluatedAtDesc(traceId.trim());
        } else {
            results = resultRepository.findTop500ByOrderByEvaluatedAtDesc();
        }
        Map<Long, EvaluatorDefinition> evaluators = evaluatorRepository.findAllById(
                        results.stream().map(item -> item.evaluatorId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(item -> item.id, item -> item));
        Map<Long, EvaluationDataset> datasets = datasetRepository.findAllById(
                        results.stream().map(item -> item.datasetId).filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(item -> item.id, item -> item));
        return results.stream().limit(boundedLimit)
                .map(item -> toResponse(item, evaluators.get(item.evaluatorId),
                item.datasetId == null ? null : datasets.get(item.datasetId))).toList();
    }

    @Transactional(readOnly = true)
    public EvaluationDtos.EvaluationSummaryResponse summary(int requestedHours) {
        int hours = Math.max(1, Math.min(requestedHours, 720));
        List<EvaluationResult> results = resultRepository
                .findByEvaluatedAtGreaterThanEqualOrderByEvaluatedAtDesc(Instant.now().minusSeconds(hours * 3600L));
        Map<Long, EvaluatorDefinition> evaluatorById = evaluatorRepository.findAllById(
                        results.stream().map(item -> item.evaluatorId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(item -> item.id, item -> item));
        int total = results.size();
        int passed = (int) results.stream().filter(item -> item.passed).count();
        BigDecimal averageScore = average(results.stream().map(item -> item.score).toList());
        BigDecimal averageConfidence = average(results.stream().map(item -> item.confidence).toList());
        Map<Long, List<EvaluationResult>> grouped = results.stream()
                .collect(java.util.stream.Collectors.groupingBy(item -> item.evaluatorId));
        List<EvaluationDtos.EvaluatorSummary> evaluatorSummaries = grouped.entrySet().stream()
                .map(entry -> {
                    List<EvaluationResult> items = entry.getValue();
                    EvaluatorDefinition evaluator = evaluatorById.get(entry.getKey());
                    int evaluatorPassed = (int) items.stream().filter(item -> item.passed).count();
                    return new EvaluationDtos.EvaluatorSummary(
                            evaluator == null ? "UNKNOWN" : evaluator.evaluatorKey,
                            evaluator == null ? "未知评估器" : evaluator.name,
                            items.size(), evaluatorPassed, items.size() - evaluatorPassed,
                            rate(evaluatorPassed, items.size()), average(items.stream().map(item -> item.score).toList())
                    );
                })
                .sorted(java.util.Comparator.comparingInt(EvaluationDtos.EvaluatorSummary::failed).reversed()
                        .thenComparing(EvaluationDtos.EvaluatorSummary::evaluatorKey))
                .toList();
        return new EvaluationDtos.EvaluationSummaryResponse(hours, total, passed, total - passed,
                rate(passed, total), averageScore, averageConfidence, evaluatorSummaries);
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) return BigDecimal.ZERO.setScale(6);
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(int numerator, int denominator) {
        if (denominator == 0) return BigDecimal.ZERO.setScale(6);
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private EvaluationDtos.EvaluationResultResponse toResponse(
            EvaluationResult result, EvaluatorDefinition evaluator, EvaluationDataset dataset
    ) {
        JsonNode evidence = parseJson(result.evidenceJson);
        return new EvaluationDtos.EvaluationResultResponse(
                result.id, result.resultKey,
                evaluator == null ? null : evaluator.evaluatorKey,
                result.evaluatorVersion == null ? (evaluator == null ? null : evaluator.version) : result.evaluatorVersion,
                dataset == null ? null : dataset.datasetKey,
                result.agentId, result.traceId, result.spanId, result.level, result.status,
                result.score, result.passed, result.confidence, result.rationale, evidence,
                result.evaluatedAt, result.createdAt
        );
    }

    private Score score(String implementationKey, JsonNode input, JsonNode expected, JsonNode actual) {
        return switch (implementationKey.toUpperCase(Locale.ROOT)) {
            case "TOOL_ARGUMENT_VALIDITY" -> toolArgumentValidity(input, actual);
            case "TOOL_STATUS" -> toolStatus(actual);
            case "MODEL_CALL_STATUS" -> modelCallStatus(actual);
            case "PII_POLICY" -> booleanPolicy(actual, "containsPii", false, "检测到个人敏感信息");
            case "SENSITIVE_APPROVAL" -> booleanPolicy(actual, "approved", true, "敏感操作缺少审批");
            case "COST_BUDGET" -> costBudget(input, actual);
            case "ACTION_COMPLETION" -> taskCompletion(actual);
            case "RAG_CONTEXT_COVERAGE" -> ragContextCoverage(actual);
            case "EXACT_MATCH" -> exactMatch(expected, actual);
            default -> throw new BusinessException("不支持的确定性评估器实现：" + implementationKey);
        };
    }

    private Score modelCallStatus(JsonNode actual) {
        String outcome = actual.path("outcome").asText("");
        if (!"SUCCEEDED".equals(outcome) && !"FAILED".equals(outcome)) {
            throw new BusinessException("模型调用终态未知，不能评分");
        }
        boolean succeeded = "SUCCEEDED".equals(outcome);
        return new Score(succeeded ? BigDecimal.ONE : BigDecimal.ZERO,
                (succeeded ? "模型调用成功。" : "模型调用失败。") + "仅评估调用终态，业务任务完成度和回答质量未知。",
                evidence("outcome", outcome, "scope", "MODEL_CALL_ONLY", "taskCompletion", "UNKNOWN"));
    }

    private BigDecimal confidence(JsonNode actual) {
        return actual != null && actual.path("heuristic").asBoolean(false)
                ? new BigDecimal("0.600000") : BigDecimal.ONE.setScale(6);
    }

    private Score ragContextCoverage(JsonNode actual) {
        String status = actual.path("status").asText("").toUpperCase(Locale.ROOT);
        if ("FAILED".equals(status) || "ERROR".equals(status) || "CANCELLED".equals(status)) {
            return new Score(BigDecimal.ZERO, "RAG 交互未成功完成。", evidence("status", status));
        }
        int inputSources = nonNegativeInt(actual.path("inputSourceCount"));
        int outputSources = nonNegativeInt(actual.path("outputSourceCount"));
        int inputRefs = nonNegativeInt(actual.path("inputRefsCount"));
        int outputRefs = nonNegativeInt(actual.path("outputRefsCount"));
        int overlap = nonNegativeInt(actual.path("referenceOverlapCount"));
        boolean retrieved = inputSources > 0 || inputRefs > 0;
        boolean evidenced = outputSources > 0 || outputRefs > 0;
        double available = retrieved ? 1.0 : 0.0;
        double coverage;
        if (inputRefs > 0) {
            coverage = Math.min(1.0, (double) overlap / inputRefs);
        } else if (inputSources > 0) {
            coverage = Math.min(1.0, (double) outputSources / inputSources);
        } else {
            coverage = evidenced ? 0.5 : 0.0;
        }
        double adoption = actual.path("adopted").asBoolean(false) ? 1.0 : 0.0;
        int qualityScore = actual.path("qualityScore").asInt(-1);
        double quality = qualityScore >= 0 ? Math.min(1.0, qualityScore / 100.0) : (evidenced ? 1.0 : 0.0);
        BigDecimal score = BigDecimal.valueOf(available * 0.25 + coverage * 0.35 + adoption * 0.15 + quality * 0.25)
                .setScale(6, RoundingMode.HALF_UP);
        return new Score(score,
                score.compareTo(new BigDecimal("0.500000")) >= 0 ? "RAG 已产生可追踪的上下文证据。" : "RAG 上下文覆盖不足，请检查检索结果与回答引用。",
                evidence("retrieved", retrieved, "evidenced", evidenced, "coverage", coverage,
                        "inputSources", inputSources, "outputSources", outputSources,
                        "inputRefs", inputRefs, "outputRefs", outputRefs, "overlap", overlap));
    }

    private int nonNegativeInt(JsonNode value) {
        return value == null || !value.isNumber() ? 0 : Math.max(0, value.asInt());
    }

    private Score toolArgumentValidity(JsonNode input, JsonNode actual) {
        JsonNode required = input.path("requiredArguments");
        JsonNode arguments = actual.path("arguments");
        if (!required.isArray()) {
            return new Score(BigDecimal.ONE, "未提供 requiredArguments，按无需必填参数处理。", evidence("required", 0, "missing", 0));
        }
        int total = required.size();
        int missing = 0;
        List<String> missingNames = new ArrayList<>();
        for (JsonNode name : required) {
            String key = name.asText();
            if (!arguments.isObject() || !arguments.has(key) || arguments.get(key).isNull()) {
                missing++;
                missingNames.add(key);
            }
        }
        BigDecimal score = total == 0 ? BigDecimal.ONE : ratio(total - missing, total);
        return new Score(score, missing == 0 ? "工具必填参数完整。" : "工具缺少必填参数：" + String.join(", ", missingNames),
                evidence("required", total, "missing", missing, "missingNames", missingNames));
    }

    private Score toolStatus(JsonNode actual) {
        String status = actual.path("status").asText("").toUpperCase(Locale.ROOT);
        boolean passed = List.of("SUCCEEDED", "SUCCESS", "OK", "COMPLETED").contains(status);
        return new Score(passed ? BigDecimal.ONE : BigDecimal.ZERO,
                passed ? "工具调用成功。" : "工具调用状态为 " + (status.isBlank() ? "UNKNOWN" : status) + "。",
                evidence("status", status, "success", passed));
    }

    private Score taskCompletion(JsonNode actual) {
        Score result = booleanPolicy(actual, "completed", true, "任务未完成");
        ObjectNode evidence = result.evidence();
        // Preserve only bounded aggregate evidence, never arbitrary source attributes or content.
        for (String key : List.of("spanCount", "failedSpanCount", "toolCount", "failedToolCount", "modelCount", "sumSpanDurationMs")) {
            JsonNode value = actual.path(key);
            if (value.isIntegralNumber() && value.canConvertToLong()) evidence.put(key, Math.max(0L, value.longValue()));
        }
        if ("EXPLICIT".equals(actual.path("inferenceMode").asText())) evidence.put("inferenceMode", "EXPLICIT");
        if ("RECEIVED_SPANS".equals(actual.path("evidenceScope").asText())) evidence.put("evidenceScope", "RECEIVED_SPANS");
        return new Score(result.score(), "任务完成信号判定；不代表独立验证业务效果。" + result.rationale(), evidence);
    }

    private Score booleanPolicy(JsonNode actual, String field, boolean expected, String failure) {
        JsonNode raw = actual.get(field);
        if (raw == null || !raw.isBoolean()) {
            return new Score(BigDecimal.ZERO, "未提供布尔策略字段 " + field + "，按不通过处理。",
                    evidence(field, null, "expected", expected, "missing", true));
        }
        boolean value = raw.asBoolean();
        boolean passed = value == expected;
        return new Score(passed ? BigDecimal.ONE : BigDecimal.ZERO,
                passed ? "策略条件满足。" : failure + "。",
                evidence(field, value, "expected", expected));
    }

    private Score costBudget(JsonNode input, JsonNode actual) {
        BigDecimal budget = decimal(input.path("maxCost"));
        BigDecimal cost = decimal(actual.path("cost"));
        if (budget == null || budget.signum() <= 0 || cost == null || cost.signum() < 0) {
            throw new BusinessException("COST_BUDGET 需要 input.maxCost 和 actual.cost，且必须为非负数字");
        }
        BigDecimal score = cost.compareTo(budget) <= 0
                ? BigDecimal.ONE : BigDecimal.ONE.subtract(cost.subtract(budget).divide(budget, 6, RoundingMode.HALF_UP));
        score = score.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(6, RoundingMode.HALF_UP);
        return new Score(score, cost.compareTo(budget) <= 0 ? "成本未超过预算。" : "成本超过预算。",
                evidence("cost", cost, "maxCost", budget));
    }

    private Score exactMatch(JsonNode expected, JsonNode actual) {
        if (expected == null) throw new BusinessException("EXACT_MATCH 需要 expected");
        boolean matches = expected.equals(actual);
        return new Score(matches ? BigDecimal.ONE : BigDecimal.ZERO,
                matches ? "实际结果与期望结果一致。" : "实际结果与期望结果不一致。",
                evidence("matches", matches));
    }

    private ObjectNode evidence(Object... values) {
        ObjectNode node = objectMapper.createObjectNode();
        for (int i = 0; i + 1 < values.length; i += 2) {
            Object value = values[i + 1];
            if (value == null) node.putNull(String.valueOf(values[i]));
            else if (value instanceof Boolean bool) node.put(String.valueOf(values[i]), bool);
            else if (value instanceof Number number) node.put(String.valueOf(values[i]), number.doubleValue());
            else if (value instanceof Iterable<?> iterable) {
                ArrayNode array = node.putArray(String.valueOf(values[i]));
                iterable.forEach(item -> array.add(String.valueOf(item)));
            } else node.put(String.valueOf(values[i]), String.valueOf(value));
        }
        return node;
    }

    private BigDecimal decimal(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || !value.isNumber()) return null;
        return value.decimalValue();
    }

    private BigDecimal ratio(int numerator, int denominator) {
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private void validateJson(JsonNode value, String name) {
        if (value == null || value.isMissingNode()) throw new BusinessException(name + "不能为空");
        if (value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new BusinessException(name + "超过 64KB 限制");
        }
    }

    private String persistObservation(JsonNode value) {
        if (value == null || observationProperties.getCaptureMode() == ContentCaptureMode.OFF) return null;
        String serialized = value.toString();
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
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
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

    private boolean sameRequest(
            EvaluationResult existing, String fingerprint, EvaluatorDefinition evaluator, EvaluationDataset dataset,
            Long agentId, String traceId, String spanId
    ) {
        String existingFingerprint = existing.requestFingerprint;
        if (existingFingerprint == null) return false;
        return Objects.equals(existingFingerprint, fingerprint)
                && Objects.equals(existing.evaluatorId, evaluator.id)
                && Objects.equals(existing.evaluatorVersion, evaluator.version)
                && Objects.equals(existing.datasetId, dataset == null ? null : dataset.id)
                && Objects.equals(existing.agentId, agentId)
                && Objects.equals(existing.traceId, traceId)
                && Objects.equals(existing.spanId, spanId);
    }

    private String requestFingerprint(
            Long evaluatorId, String evaluatorVersion, Long datasetId, Long agentId,
            String traceId, String spanId, String inputFingerprint, String expectedFingerprint, String actualFingerprint
    ) {
        ObjectNode payload = objectMapper.createObjectNode();
        putNullable(payload, "evaluatorId", evaluatorId);
        putNullable(payload, "evaluatorVersion", evaluatorVersion);
        putNullable(payload, "datasetId", datasetId);
        putNullable(payload, "agentId", agentId);
        putNullable(payload, "traceId", traceId);
        putNullable(payload, "spanId", spanId);
        putNullable(payload, "inputFingerprint", inputFingerprint);
        putNullable(payload, "expectedFingerprint", expectedFingerprint);
        putNullable(payload, "actualFingerprint", actualFingerprint);
        return sha256(payload.toString());
    }

    private String contentFingerprint(JsonNode value) {
        return value == null ? null : sha256(value.toString());
    }

    private void putNullable(ObjectNode payload, String key, Object value) {
        if (value == null) payload.putNull(key);
        else if (value instanceof Number number) payload.put(key, number.longValue());
        else payload.put(key, String.valueOf(value));
    }

    private record Score(BigDecimal score, String rationale, ObjectNode evidence) {
    }
}
