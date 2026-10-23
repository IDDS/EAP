package com.eap.platform.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class KnowledgeDtos {
    private KnowledgeDtos() {
    }

    public record KnowledgeInteractionRequest(
            @NotBlank @Size(max = 128) String interactionKey,
            @Size(max = 128) String traceId,
            Long agentId,
            @Size(max = 128) String endpointKey,
            Long taskId,
            @NotBlank @Size(max = 128) String inputType,
            @Min(0) @Max(1000000) int inputSourceCount,
            @Min(0) long inputTokenCount,
            @Size(max = 2048) String inputSummary,
            @Size(max = 100) List<@Size(max = 256) String> inputKnowledgeRefs,
            @Size(max = 128) String outputType,
            @Min(0) @Max(1000000) int outputSourceCount,
            @Min(0) long outputTokenCount,
            @Size(max = 2048) String outputSummary,
            @Size(max = 100) List<@Size(max = 256) String> outputKnowledgeRefs,
            @Size(max = 100) List<@Valid KnowledgeStepRequest> processSteps,
            @NotNull KnowledgeInteractionStatus status,
            Boolean adopted,
            Boolean reused,
            @Min(0) @Max(100) Integer qualityScore,
            Instant startedAt,
            Instant completedAt
    ) {
    }

    public record KnowledgeStepRequest(
            @Min(0) int stepOrder,
            @NotNull KnowledgeStepType stepType,
            @NotBlank @Size(max = 256) String name,
            @NotNull KnowledgeStepStatus status,
            @Min(0) @Max(1000000) int sourceCount,
            @Min(0) long inputTokens,
            @Min(0) long outputTokens,
            @Min(0) long latencyMs,
            @Size(max = 100) List<@Size(max = 256) String> knowledgeRefs,
            @Size(max = 64) java.util.Map<String, Object> metadata
    ) {
    }

    public record OutcomeRequest(
            @NotNull Boolean adopted,
            @NotNull Boolean reused,
            @Min(0) @Max(100) Integer qualityScore
    ) {
    }

    public record KnowledgeStepResponse(
            Long id,
            Integer stepOrder,
            KnowledgeStepType stepType,
            String name,
            KnowledgeStepStatus status,
            Integer sourceCount,
            Long inputTokens,
            Long outputTokens,
            Long latencyMs,
            List<String> knowledgeRefs,
            Instant observedAt
    ) {
    }

    public record KnowledgeInteractionResponse(
            Long id,
            String interactionKey,
            String traceId,
            Long agentId,
            String endpointKey,
            Long taskId,
            String inputType,
            Integer inputSourceCount,
            Long inputTokenCount,
            String inputSummary,
            List<String> inputKnowledgeRefs,
            String outputType,
            Integer outputSourceCount,
            Long outputTokenCount,
            String outputSummary,
            List<String> outputKnowledgeRefs,
            KnowledgeInteractionStatus status,
            Boolean adopted,
            Boolean reused,
            Integer qualityScore,
            Integer processStepCount,
            Long processTokenCount,
            Long processLatencyMs,
            Instant startedAt,
            Instant completedAt,
            Instant createdAt,
            List<KnowledgeStepResponse> processSteps
    ) {
    }

    public record KnowledgeAgentUsage(
            String subjectKey,
            String subjectName,
            Long agentId,
            String endpointKey,
            long interactions,
            long inputTokens,
            long outputTokens,
            long adoptedInteractions,
            long reusedInteractions,
            double adoptionRate,
            double reuseRate,
            double averageQualityScore,
            Instant lastInteractionAt
    ) {
    }

    public record KnowledgeSummary(
            int windowHours,
            long totalInteractions,
            long succeededInteractions,
            long failedInteractions,
            double successRate,
            long inputTokens,
            long outputTokens,
            long inputSourceCount,
            long outputSourceCount,
            long adoptedInteractions,
            long reusedInteractions,
            double adoptionRate,
            double reuseRate,
            double averageQualityScore,
            long averageProcessLatencyMs,
            Instant lastInteractionAt,
            List<KnowledgeAgentUsage> agentUsage
    ) {
    }
}
