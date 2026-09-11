package com.eap.platform.management;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ManagementDtos {
    private ManagementDtos() {
    }

    public record AgentContributionRequest(
            @NotNull Long agentId,
            @NotNull @DecimalMin("0.000001") @DecimalMax("1.0") @Digits(integer = 1, fraction = 6) BigDecimal contributionWeight,
            @Size(max = 32) String attributionMethod,
            @Size(max = 256) String evidenceReference
    ) {
    }

    public record AgentContributionResponse(
            Long agentId,
            BigDecimal contributionWeight,
            String attributionMethod,
            String evidenceReference
    ) {
    }

    public record ValueEventRequest(
            @NotBlank @Size(max = 128) String eventKey,
            Long agentId,
            @Size(max = 128) String endpointKey,
            Long taskId,
            Long knowledgeInteractionId,
            @NotBlank @Size(max = 128) String valueType,
            @Size(max = 2048) String description,
            @NotBlank @Size(max = 128) String validatedBy,
            @DecimalMin("0.0") @Digits(integer = 12, fraction = 6) BigDecimal benefitMoney,
            @Size(max = 16) String currency,
            Instant observedAt,
            @Size(max = 128) String metricName,
            @Size(max = 32) String metricUnit,
            @DecimalMin("0.0") @Digits(integer = 12, fraction = 6) BigDecimal baselineValue,
            @DecimalMin("0.0") @Digits(integer = 12, fraction = 6) BigDecimal currentValue,
            @Size(max = 32) String improvementDirection,
            @Size(max = 32) String sourceType,
            @Size(max = 128) String sourceSystem,
            @Size(max = 128) String sourceRecordKey,
            @DecimalMin("0.0") @DecimalMax("1.0") @Digits(integer = 1, fraction = 4) BigDecimal confidence,
            Instant measurementWindowStart,
            Instant measurementWindowEnd,
            List<@Valid AgentContributionRequest> agentContributions
    ) {
        public ValueEventRequest(
                String eventKey, Long agentId, String endpointKey, Long taskId, Long knowledgeInteractionId,
                String valueType, String description, String validatedBy, BigDecimal benefitMoney,
                String currency, Instant observedAt
        ) {
            this(eventKey, agentId, endpointKey, taskId, knowledgeInteractionId, valueType, description,
                    validatedBy, benefitMoney, currency, observedAt, null, null, null, null, null,
                    null, null, null, null, null, null, null);
        }

        public ValueEventRequest(
                String eventKey, Long agentId, String endpointKey, Long taskId, Long knowledgeInteractionId,
                String valueType, String description, String validatedBy, BigDecimal benefitMoney,
                String currency, Instant observedAt, String metricName, String metricUnit,
                BigDecimal baselineValue, BigDecimal currentValue, String improvementDirection
        ) {
            this(eventKey, agentId, endpointKey, taskId, knowledgeInteractionId, valueType, description,
                    validatedBy, benefitMoney, currency, observedAt, metricName, metricUnit, baselineValue,
                    currentValue, improvementDirection, null, null, null, null, null, null, null);
        }
    }

    public record ValueEventResponse(
            Long id,
            String eventKey,
            Long agentId,
            String endpointKey,
            Long taskId,
            Long knowledgeInteractionId,
            String valueType,
            String description,
            String validatedBy,
            BigDecimal benefitMoney,
            String currency,
            String metricName,
            String metricUnit,
            BigDecimal baselineValue,
            BigDecimal currentValue,
            BigDecimal improvementRate,
            String sourceType,
            String sourceSystem,
            String sourceRecordKey,
            BigDecimal confidence,
            Instant measurementWindowStart,
            Instant measurementWindowEnd,
            List<AgentContributionResponse> agentContributions,
            Instant observedAt,
            Instant createdAt
    ) {
    }

    public record AgentManagementUsage(
            String subjectKey,
            String subjectName,
            Long agentId,
            String endpointKey,
            long tasks,
            long succeededTasks,
            long modelCalls,
            long knowledgeInteractions,
            long totalTokens,
            BigDecimal totalCost,
            BigDecimal validatedValue,
            BigDecimal netBenefit,
            BigDecimal roi,
            double knowledgeAdoptionRate,
            double knowledgeReuseRate,
            long measuredImpactEvents,
            double averageImprovementRate
    ) {
    }

    public record ManagementSummary(
            int windowHours,
            String currency,
            List<String> availableCurrencies,
            boolean mixedCurrencies,
            long totalTasks,
            long succeededTasks,
            double taskSuccessRate,
            long totalModelCalls,
            long totalTokens,
            BigDecimal totalCost,
            BigDecimal validatedBusinessValue,
            BigDecimal netBenefit,
            BigDecimal roi,
            long adoptedKnowledgeInteractions,
            long reusedKnowledgeInteractions,
            double knowledgeAdoptionRate,
            double knowledgeReuseRate,
            long measuredImpactEvents,
            double averageImprovementRate,
            long efficiencyImpactEvents,
            long businessImpactEvents,
            long businessSystemValueEvents,
            long multiAgentValueEvents,
            double averageConfidence,
            boolean hasValidatedValue,
            Instant lastValueEventAt,
            List<AgentManagementUsage> agentUsage,
            long resolvedTasks,
            long unresolvedTasks,
            long unconfirmedTasks
    ) {
    }

    static ValueEventResponse from(ManagementValueEvent event, List<ManagementValueAttribution> attributions) {
        return new ValueEventResponse(
                event.id, event.eventKey, event.agentId, event.endpointKey, event.taskId,
                event.knowledgeInteractionId, event.valueType, event.description, event.validatedBy,
                event.benefitMoney, event.currency, event.metricName, event.metricUnit,
                event.baselineValue, event.currentValue, improvementRate(event), event.sourceType, event.sourceSystem,
                event.sourceRecordKey, event.confidence, event.measurementWindowStart, event.measurementWindowEnd,
                attributions.stream().map(item -> new AgentContributionResponse(
                        item.agentId, item.contributionWeight, item.attributionMethod, item.evidenceReference
                )).toList(), event.observedAt, event.createdAt
        );
    }

    static BigDecimal improvementRate(ManagementValueEvent event) {
        if (event.baselineValue == null || event.currentValue == null || event.baselineValue.signum() == 0) {
            return null;
        }
        BigDecimal delta = event.currentValue.subtract(event.baselineValue);
        if ("LOWER_IS_BETTER".equalsIgnoreCase(event.improvementDirection)) {
            delta = delta.negate();
        }
        return delta.divide(event.baselineValue.abs(), 4, java.math.RoundingMode.HALF_UP);
    }
}
