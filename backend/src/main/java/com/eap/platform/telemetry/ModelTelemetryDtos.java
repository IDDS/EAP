package com.eap.platform.telemetry;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ModelTelemetryDtos {
    private ModelTelemetryDtos() {
    }

    public record ModelInvocationRequest(
            @NotBlank @Size(max = 128) String eventKey,
            @Size(max = 128) String traceId,
            @Size(max = 128) String spanId,
            Long agentId,
            @Size(max = 128) String endpointKey,
            @Size(max = 128) String runtimeInstanceKey,
            Long taskId,
            @Size(max = 128) String governanceDecisionKey,
            @NotBlank @Size(max = 128) String provider,
            @NotBlank @Size(max = 128) String modelName,
            @NotNull ModelInvocationStatus status,
            @Min(0) long inputTokens,
            @Min(0) long outputTokens,
            @Min(0) long cachedInputTokens,
            @Min(0) long reasoningTokens,
            @Min(0) long latencyMs,
            @DecimalMin("0.0") @Digits(integer = 12, fraction = 6) BigDecimal moneyCost,
            @Size(max = 16) String currency,
            @Min(0) @Max(100000) int toolCallCount,
            @Min(0) @Max(100000) int mcpServerCount,
            @Size(max = 128) String errorType,
            @Size(max = 128) String usagePoolKey,
            Instant observedAt
    ) {
    }

    public record ModelInvocationResponse(
            Long id,
            String eventKey,
            String traceId,
            String spanId,
            Long agentId,
            String endpointKey,
            String runtimeInstanceKey,
            Long taskId,
            String governanceDecisionKey,
            ModelObservationSource observationSource,
            Boolean governanceCovered,
            String usagePoolKey,
            String provider,
            String modelName,
            ModelInvocationStatus status,
            Long inputTokens,
            Long outputTokens,
            Long cachedInputTokens,
            Long reasoningTokens,
            Long totalTokens,
            Long latencyMs,
            BigDecimal moneyCost,
            String currency,
            Integer toolCallCount,
            Integer mcpServerCount,
            String errorType,
            Instant observedAt,
            Instant createdAt
    ) {
    }

    public record SubjectUsage(
            String subjectKey,
            String subjectName,
            Long agentId,
            String endpointKey,
            long calls,
            long succeededCalls,
            long failedCalls,
            double successRate,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            BigDecimal moneyCost,
            long averageLatencyMs,
            Instant lastEventAt
    ) {
    }

    public record ModelUsage(
            String provider,
            String modelName,
            long calls,
            long totalTokens,
            BigDecimal moneyCost,
            long averageLatencyMs
    ) {
    }

    public record PhysicalTelemetrySummary(
            int windowHours,
            long totalCalls,
            long succeededCalls,
            long failedCalls,
            double successRate,
            long inputTokens,
            long outputTokens,
            long cachedInputTokens,
            long reasoningTokens,
            long totalTokens,
            BigDecimal moneyCost,
            long averageLatencyMs,
            long p95LatencyMs,
            long registeredEndpoints,
            long activeEndpoints,
            long onlineRuntimeCount,
            long degradedRuntimeCount,
            long offlineRuntimeCount,
            Instant lastEventAt,
            List<SubjectUsage> subjectUsage,
            List<ModelUsage> modelUsage
    ) {
    }

    static ModelInvocationResponse from(ModelInvocationEvent event) {
        return new ModelInvocationResponse(
                event.id, event.eventKey, event.traceId, event.spanId, event.agentId, event.endpointKey,
                event.runtimeInstanceKey, event.taskId, event.governanceDecisionKey, event.observationSource,
                Boolean.TRUE.equals(event.governanceCovered), event.usagePoolKey, event.provider, event.modelName, event.status,
                event.inputTokens, event.outputTokens, event.cachedInputTokens, event.reasoningTokens,
                event.inputTokens + event.outputTokens, event.latencyMs, event.moneyCost, event.currency,
                event.toolCallCount, event.mcpServerCount, event.errorType, event.observedAt, event.createdAt
        );
    }
}
