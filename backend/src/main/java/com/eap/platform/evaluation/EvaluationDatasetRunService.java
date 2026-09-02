package com.eap.platform.evaluation;

import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class EvaluationDatasetRunService {
    private static final int MAX_ITEMS = 500;

    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationDatasetItemRepository itemRepository;
    private final EvaluatorDefinitionRepository evaluatorRepository;
    private final EvaluationResultRepository resultRepository;
    private final EvaluationService evaluationService;
    private final ObjectMapper objectMapper;

    public EvaluationDatasetRunService(
            EvaluationDatasetRepository datasetRepository,
            EvaluationDatasetItemRepository itemRepository,
            EvaluatorDefinitionRepository evaluatorRepository,
            EvaluationResultRepository resultRepository,
            EvaluationService evaluationService,
            ObjectMapper objectMapper
    ) {
        this.datasetRepository = datasetRepository;
        this.itemRepository = itemRepository;
        this.evaluatorRepository = evaluatorRepository;
        this.resultRepository = resultRepository;
        this.evaluationService = evaluationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public EvaluationDtos.EvaluationDatasetCalibrationResponse run(String datasetKey, int requestedLimit) {
        EvaluationDataset dataset = dataset(datasetKey);
        List<EvaluationDatasetItem> items = items(dataset, requestedLimit);
        return calculate(dataset, items, true, requestedLimit);
    }

    @Transactional(readOnly = true)
    public EvaluationDtos.EvaluationDatasetCalibrationResponse calibration(String datasetKey, int requestedLimit) {
        EvaluationDataset dataset = dataset(datasetKey);
        List<EvaluationDatasetItem> items = items(dataset, requestedLimit);
        return calculate(dataset, items, false, requestedLimit);
    }

    private EvaluationDtos.EvaluationDatasetCalibrationResponse calculate(
            EvaluationDataset dataset, List<EvaluationDatasetItem> items, boolean execute, int requestedLimit
    ) {
        Metrics overall = new Metrics();
        Map<String, Metrics> byEvaluator = new LinkedHashMap<>();
        for (EvaluationDatasetItem item : items) {
            String evaluatorKey = normalizeKey(item.evaluatorKey);
            String version = item.evaluatorVersion == null || item.evaluatorVersion.isBlank()
                    ? "" : item.evaluatorVersion.trim();
            Metrics metrics = byEvaluator.computeIfAbsent(evaluatorKey + "@" + version,
                    ignored -> new Metrics(evaluatorKey, version));
            overall.totalItems++;
            metrics.totalItems++;
            if (item.groundTruthLabel == null || item.groundTruthLabel.isBlank()) {
                overall.unannotatedItems++;
                metrics.unannotatedItems++;
            } else if ("ABSTAIN".equalsIgnoreCase(item.groundTruthLabel)) {
                overall.abstainedItems++;
                metrics.abstainedItems++;
            }

            EvaluatorDefinition evaluator = evaluatorRepository.findByEvaluatorKey(evaluatorKey).orElse(null);
            if (evaluator == null || !evaluator.enabled || (!version.isBlank() && !version.equals(evaluator.version))) {
                overall.skippedItems++;
                metrics.skippedItems++;
                continue;
            }
            JsonNode input = parseUsableJson(item.inputJson);
            JsonNode expected = parseUsableJson(item.expectedJson);
            JsonNode actual = parseUsableJson(item.actualJson);
            if (input == null || actual == null) {
                overall.skippedItems++;
                metrics.skippedItems++;
                continue;
            }

            EvaluationDtos.EvaluationResultResponse result;
            try {
                if (execute) {
                    result = evaluationService.evaluate(new EvaluationDtos.EvaluationRequest(
                            resultKey(dataset.datasetKey, item.itemKey, evaluator.version), evaluatorKey, evaluator.version,
                            dataset.datasetKey, item.agentId, item.traceId, item.spanId, input, expected, actual, null));
                } else {
                    result = resultRepository.findByResultKey(resultKey(dataset.datasetKey, item.itemKey, evaluator.version))
                            .map(saved -> new EvaluationDtos.EvaluationResultResponse(
                                    saved.id, saved.resultKey, evaluator.evaluatorKey, saved.evaluatorVersion,
                                    dataset.datasetKey, saved.agentId, saved.traceId, saved.spanId, saved.level,
                                    saved.status, saved.score, saved.passed, saved.confidence, saved.rationale,
                                    parseUsableJson(saved.evidenceJson), saved.evaluatedAt, saved.createdAt))
                            .orElse(null);
                }
            } catch (BusinessException exception) {
                overall.failedItems++;
                metrics.failedItems++;
                continue;
            }
            if (result == null) {
                overall.skippedItems++;
                metrics.skippedItems++;
                continue;
            }
            overall.evaluatedItems++;
            metrics.evaluatedItems++;
            if ("PASS".equalsIgnoreCase(item.groundTruthLabel)) {
                if (result.passed()) {
                    overall.truePositive++;
                    metrics.truePositive++;
                } else {
                    overall.falseNegative++;
                    metrics.falseNegative++;
                }
            } else if ("FAIL".equalsIgnoreCase(item.groundTruthLabel)) {
                if (result.passed()) {
                    overall.falsePositive++;
                    metrics.falsePositive++;
                } else {
                    overall.trueNegative++;
                    metrics.trueNegative++;
                }
            }
        }
        return new EvaluationDtos.EvaluationDatasetCalibrationResponse(
                dataset.datasetKey, dataset.version, Math.max(1, Math.min(requestedLimit, MAX_ITEMS)),
                overall.totalItems, overall.evaluatedItems, overall.skippedItems, overall.failedItems,
                overall.unannotatedItems, overall.abstainedItems, overall.truePositive, overall.falsePositive,
                overall.trueNegative, overall.falseNegative, precision(overall), recall(overall), f1(overall),
                accuracy(overall), Instant.now(), byEvaluator.values().stream().map(this::toResponse).toList());
    }

    private EvaluationDtos.EvaluatorCalibration toResponse(Metrics metrics) {
        return new EvaluationDtos.EvaluatorCalibration(
                metrics.evaluatorKey, metrics.evaluatorVersion, metrics.totalItems, metrics.evaluatedItems,
                metrics.skippedItems, metrics.failedItems, metrics.unannotatedItems, metrics.abstainedItems,
                metrics.truePositive, metrics.falsePositive, metrics.trueNegative, metrics.falseNegative,
                precision(metrics), recall(metrics), f1(metrics), accuracy(metrics));
    }

    private BigDecimal precision(Metrics metrics) {
        return ratio(metrics.truePositive, metrics.truePositive + metrics.falsePositive);
    }

    private BigDecimal recall(Metrics metrics) {
        return ratio(metrics.truePositive, metrics.truePositive + metrics.falseNegative);
    }

    private BigDecimal f1(Metrics metrics) {
        BigDecimal precision = precision(metrics);
        BigDecimal recall = recall(metrics);
        if (precision.signum() == 0 && recall.signum() == 0) return BigDecimal.ZERO.setScale(6);
        return precision.multiply(recall).multiply(BigDecimal.valueOf(2))
                .divide(precision.add(recall), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal accuracy(Metrics metrics) {
        return ratio(metrics.truePositive + metrics.trueNegative,
                metrics.truePositive + metrics.trueNegative + metrics.falsePositive + metrics.falseNegative);
    }

    private BigDecimal ratio(int numerator, int denominator) {
        return denominator == 0 ? BigDecimal.ZERO.setScale(6)
                : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
    }

    private EvaluationDataset dataset(String datasetKey) {
        String normalized = normalizeKey(datasetKey);
        return datasetRepository.findByDatasetKey(normalized)
                .orElseThrow(() -> new BusinessException("评估数据集不存在"));
    }

    private List<EvaluationDatasetItem> items(EvaluationDataset dataset, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, MAX_ITEMS));
        return itemRepository.findAllByDatasetIdOrderByCreatedAtAsc(dataset.id).stream().limit(limit).toList();
    }

    private JsonNode parseUsableJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode value = objectMapper.readTree(json);
            if (value != null && value.isObject()) {
                String captureMode = value.path("captureMode").asText("").toUpperCase(Locale.ROOT);
                if ("HASH_ONLY".equals(captureMode) || "OFF".equals(captureMode)) return null;
                if ("REDACTED".equals(captureMode)) {
                    JsonNode safeContent = value.get("content");
                    if (safeContent == null || !safeContent.isTextual()) return null;
                    JsonNode sanitized = objectMapper.readTree(safeContent.asText());
                    return sanitized == null || sanitized.isMissingNode() ? null : sanitized;
                }
            }
            return value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resultKey(String datasetKey, String itemKey, String version) {
        String source = datasetKey + "|" + itemKey + "|" + version;
        return "dataset-run-" + sha256(source).substring(0, 56);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static final class Metrics {
        private final String evaluatorKey;
        private final String evaluatorVersion;
        private int totalItems;
        private int evaluatedItems;
        private int skippedItems;
        private int failedItems;
        private int unannotatedItems;
        private int abstainedItems;
        private int truePositive;
        private int falsePositive;
        private int trueNegative;
        private int falseNegative;

        private Metrics() {
            this("ALL", "");
        }

        private Metrics(String evaluatorKey, String evaluatorVersion) {
            this.evaluatorKey = evaluatorKey;
            this.evaluatorVersion = evaluatorVersion;
        }
    }
}
