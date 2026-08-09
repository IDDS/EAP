package com.eap.platform.management;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ManagementDtos {
    private ManagementDtos() {
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
            Instant observedAt
    ) {
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
            double knowledgeReuseRate
    ) {
    }

    public record ManagementSummary(
            int windowHours,
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
            boolean hasValidatedValue,
            Instant lastValueEventAt,
            List<AgentManagementUsage> agentUsage
    ) {
    }

    static ValueEventResponse from(ManagementValueEvent event) {
        return new ValueEventResponse(
                event.id, event.eventKey, event.agentId, event.endpointKey, event.taskId,
                event.knowledgeInteractionId, event.valueType, event.description, event.validatedBy,
                event.benefitMoney, event.currency, event.observedAt, event.createdAt
        );
    }
}
