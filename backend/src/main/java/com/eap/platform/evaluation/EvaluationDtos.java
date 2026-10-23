package com.eap.platform.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class EvaluationDtos {
    private EvaluationDtos() {
    }

    public record EvaluatorCreateRequest(
            @NotBlank @Size(max = 128) String evaluatorKey,
            @NotBlank @Size(max = 256) String name,
            @Size(max = 2048) String description,
            @NotNull EvaluatorType evaluatorType,
            @NotNull EvaluationLevel level,
            @NotBlank @Size(max = 128) String implementationKey,
            @NotBlank @Size(max = 64) String version,
            @DecimalMin("0.0") @DecimalMax("1.0") @Digits(integer = 1, fraction = 6) BigDecimal threshold
    ) {
    }

    public record EvaluatorResponse(
            Long id,
            String evaluatorKey,
            String name,
            String description,
            EvaluatorType evaluatorType,
            EvaluationLevel level,
            String implementationKey,
            String version,
            BigDecimal threshold,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt
    ) {
        static EvaluatorResponse from(EvaluatorDefinition item) {
            return new EvaluatorResponse(item.id, item.evaluatorKey, item.name, item.description,
                    item.evaluatorType, item.level, item.implementationKey, item.version,
                    item.threshold, item.enabled, item.createdAt, item.updatedAt);
        }
    }

    public record DatasetCreateRequest(
            @NotBlank @Size(max = 128) String datasetKey,
            @NotBlank @Size(max = 256) String name,
            @Size(max = 2048) String description,
            @Size(max = 32) String sourceType,
            @Size(max = 64) String version,
            Integer itemCount
    ) {
    }

    public record DatasetResponse(
            Long id,
            String datasetKey,
            String name,
            String description,
            String sourceType,
            String version,
            int itemCount,
            Instant createdAt,
            Instant updatedAt
    ) {
        static DatasetResponse from(EvaluationDataset item) {
            return new DatasetResponse(item.id, item.datasetKey, item.name, item.description,
                    item.sourceType, item.version, item.itemCount, item.createdAt, item.updatedAt);
        }
    }

    public enum DatasetLookupKind { DATASET, SAMPLE }
    public enum DatasetLookupState { FOUND, NOT_FOUND, KEY_MISMATCH }

    // Presence metadata only: no sample body, labels or caller-provided content.
    // A match is not proof that a particular request committed or that its content matches.
    public record DatasetLookupResponse(
            DatasetLookupKind kind, DatasetLookupState state, String datasetKey,
            String itemKey, Long recordId, Instant createdAt
    ) { }

    public record DatasetItemCreateRequest(
            @NotBlank @Size(max = 128) String itemKey,
            @NotBlank @Size(max = 128) String evaluatorKey,
            @Size(max = 64) String evaluatorVersion,
            Long agentId,
            @Size(max = 128) String traceId,
            @Size(max = 128) String spanId,
            @NotNull JsonNode input,
            JsonNode expected,
            @NotNull JsonNode actual,
            boolean verifySourceReference
    ) {
    }

    public record DatasetItemAnnotationRequest(
            @NotBlank @Size(max = 16) String groundTruthLabel,
            @DecimalMin("0.0") @DecimalMax("1.0") @Digits(integer = 1, fraction = 6) BigDecimal groundTruthScore,
            @Size(max = 128) String annotatedBy,
            @Size(max = 2048) String rationale
    ) {
    }

    public record DatasetItemResponse(
            Long id,
            String itemKey,
            String evaluatorKey,
            String evaluatorVersion,
            Long agentId,
            String traceId,
            String spanId,
            JsonNode input,
            JsonNode expected,
            JsonNode actual,
            String groundTruthLabel,
            BigDecimal groundTruthScore,
            String annotationRationale,
            String annotatedBy,
            Instant annotatedAt,
            Instant createdAt,
            Instant updatedAt,
            DatasetSourceReference sourceReference
    ) {
    }

    /** A snapshot of matching observation metadata, not proof of payload origin or producer identity. */
    public record DatasetSourceReference(
            String state, Long observationId, String endpointKey, Instant observedAt, Instant checkedAt
    ) { }

    public record EvaluationRequest(
            @Size(max = 128) String resultKey,
            @NotBlank @Size(max = 128) String evaluatorKey,
            @Size(max = 64) String evaluatorVersion,
            @Size(max = 128) String datasetKey,
            Long agentId,
            @Size(max = 128) String traceId,
            @Size(max = 128) String spanId,
            @NotNull JsonNode input,
            JsonNode expected,
            @NotNull JsonNode actual,
            Instant evaluatedAt
    ) {
    }

    public record EvaluationResultResponse(
            Long id,
            String resultKey,
            String evaluatorKey,
            String evaluatorVersion,
            String datasetKey,
            Long agentId,
            String traceId,
            String spanId,
            EvaluationLevel level,
            EvaluationStatus status,
            BigDecimal score,
            boolean passed,
            BigDecimal confidence,
            String rationale,
            JsonNode evidence,
            Instant evaluatedAt,
            Instant createdAt
    ) {
    }

    public record EvaluationSyncResponse(
            int scanned,
            int evaluated,
            int skipped,
            int failed
    ) {
    }

    public record EvaluationJobResponse(
            Long id,
            String jobKey,
            int requestedLimit,
            EvaluationJobStatus status,
            int scanned,
            int evaluated,
            int skipped,
            int failed,
            String errorMessage,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt,
            String phase,
            int processed,
            int total
    ) {
        static EvaluationJobResponse from(EvaluationProjectionJob job) {
            return new EvaluationJobResponse(job.id, job.jobKey, job.requestedLimit, job.status,
                    job.scanned, job.evaluated, job.skipped, job.failed, job.errorMessage,
                    job.createdAt, job.startedAt, job.completedAt, job.updatedAt, job.phase, job.processed, job.total);
        }
    }

    public record EvaluationSummaryResponse(
            int windowHours,
            int total,
            int passed,
            int failed,
            BigDecimal passRate,
            BigDecimal averageScore,
            BigDecimal averageConfidence,
            List<EvaluatorSummary> evaluators
    ) {
    }

    public record EvaluationDatasetCalibrationResponse(
            String datasetKey,
            String datasetVersion,
            int requestedLimit,
            int totalItems,
            int evaluatedItems,
            int skippedItems,
            int failedItems,
            int unannotatedItems,
            int abstainedItems,
            int truePositive,
            int falsePositive,
            int trueNegative,
            int falseNegative,
            BigDecimal precision,
            BigDecimal recall,
            BigDecimal f1,
            BigDecimal accuracy,
            Instant calculatedAt,
            List<EvaluatorCalibration> evaluators,
            String runId
    ) {
    }

    public record CalibrationRunSummary(
            String runId, String datasetKey, String datasetVersion, int requestedLimit,
            int totalItems, int evaluatedItems, int failedItems, Instant calculatedAt
    ) { }

    public record CalibrationEvaluatorSnapshot(
            String evaluatorKey, String evaluatorVersion, EvaluatorType evaluatorType,
            String implementationKey, EvaluationLevel level, BigDecimal threshold, boolean enabled
    ) { }

    public record CalibrationSampleSnapshot(
            String itemKey, String evaluatorKey, String evaluatorVersion,
            String groundTruthLabel, BigDecimal groundTruthScore, Instant annotatedAt,
            String outcome, String reason, String resultKey, BigDecimal score, Boolean passed
    ) { }

    public record CalibrationRunDetail(
            EvaluationDatasetCalibrationResponse calibration,
            List<CalibrationEvaluatorSnapshot> evaluatorDefinitions,
            List<CalibrationSampleSnapshot> samples
    ) { }

    public record EvaluatorCalibration(
            String evaluatorKey,
            String evaluatorVersion,
            int totalItems,
            int evaluatedItems,
            int skippedItems,
            int failedItems,
            int unannotatedItems,
            int abstainedItems,
            int truePositive,
            int falsePositive,
            int trueNegative,
            int falseNegative,
            BigDecimal precision,
            BigDecimal recall,
            BigDecimal f1,
            BigDecimal accuracy
    ) {
    }

    public record EvaluatorSummary(
            String evaluatorKey,
            String name,
            int total,
            int passed,
            int failed,
            BigDecimal passRate,
            BigDecimal averageScore
    ) {
    }
}
