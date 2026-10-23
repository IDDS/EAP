package com.eap.platform.agentops;

import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.RiskLevel;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class AgentOpsDtos {
    private AgentOpsDtos() {}

    public record WorkItemTransitionRequest(
            @NotNull WorkItemStatus status,
            @Size(max = 128) String assignee,
            @Size(max = 128) String assignedTeam,
            @Size(max = 128) String actor,
            @Size(max = 2048) String reason,
            String resolutionEvidenceJson
    ) {}

    public record WorkItemResponse(
            Long id,
            String itemKey,
            WorkItemType itemType,
            String title,
            String description,
            WorkItemSeverity severity,
            WorkItemStatus status,
            Long agentId,
            String endpointKey,
            String runtimeInstanceKey,
            String sourceType,
            String sourceId,
            String organizationScope,
            String assignee,
            String assignedTeam,
            EvidenceBasis evidenceBasis,
            String evidenceJson,
            int occurrenceCount,
            Instant firstDetectedAt,
            Instant lastDetectedAt,
            Instant slaDueAt,
            boolean overdue,
            String resolution,
            String resolutionEvidenceJson,
            Instant resolvedAt,
            Instant closedAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record WorkItemEventResponse(
            Long id,
            Long workItemId,
            String eventType,
            String actor,
            String fromStatus,
            String toStatus,
            String note,
            String evidenceJson,
            Instant createdAt
    ) {}

    public record AgentStateResponse(
            Long id,
            String code,
            String name,
            String description,
            String ownerDepartment,
            String ownerUser,
            String category,
            AgentLifecycleStatus lifecycleStatus,
            RiskLevel declaredRiskLevel,
            OperationalHealth operationalHealth,
            OnboardingState onboardingState,
            ObservationCoverage observationCoverage,
            RiskPosture riskPosture,
            int coverageScore,
            List<String> coverageSignals,
            int runtimeCount,
            int onlineRuntimeCount,
            int openWorkItemCount,
            Instant latestObservedAt,
            String dataFreshness,
            EvidenceBasis statusBasis,
            Instant updatedAt
    ) {}

    public record PagedAgentResponse(
            List<AgentStateResponse> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}

    public record MetricEvidence(
            String key,
            String label,
            long value,
            EvidenceBasis basis,
            String source,
            Instant measuredAt,
            String freshness
    ) {}

    public record WorkItemCounts(long totalOpen, long p0, long p1, long p2, long p3, long overdue) {}

    public record DashboardResponse(
            int windowHours,
            Instant windowStart,
            Instant windowEnd,
            String timeZone,
            String organizationScope,
            long agentCount,
            long healthyAgentCount,
            long degradedAgentCount,
            long offlineAgentCount,
            long missingObservationCount,
            long highRiskAgentCount,
            WorkItemCounts workItems,
            List<MetricEvidence> metrics,
            List<WorkItemResponse> priorityWorkItems,
            List<AgentStateResponse> attentionAgents,
            boolean demoDataIncluded,
            Instant generatedAt,
            Instant lastSignalSyncAt,
            String signalSyncFreshness
    ) {}

    static WorkItemResponse from(AgentWorkItem item, Instant now) {
        return new WorkItemResponse(
                item.id, item.itemKey, item.itemType, item.title, item.description, item.severity, item.status,
                item.agentId, item.endpointKey, item.runtimeInstanceKey, item.sourceType, item.sourceId,
                item.organizationScope, item.assignee, item.assignedTeam, item.evidenceBasis, item.evidenceJson,
                item.occurrenceCount == null ? 1 : item.occurrenceCount, item.firstDetectedAt, item.lastDetectedAt,
                item.slaDueAt, item.slaDueAt != null && item.slaDueAt.isBefore(now)
                        && item.status != WorkItemStatus.RESOLVED && item.status != WorkItemStatus.CLOSED,
                item.resolution, item.resolutionEvidenceJson, item.resolvedAt, item.closedAt,
                item.createdAt, item.updatedAt
        );
    }

    static WorkItemEventResponse from(AgentWorkItemEvent event) {
        return new WorkItemEventResponse(
                event.id, event.workItemId, event.eventType, event.actor, event.fromStatus, event.toStatus,
                event.note, event.evidenceJson, event.createdAt
        );
    }
}
