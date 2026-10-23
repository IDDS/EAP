package com.eap.platform.governance;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class GovernanceDataDtos {
    private GovernanceDataDtos() {
    }

    public record DataEventRequest(
            @NotBlank @Size(max = 128) String eventKey,
            Long agentId,
            @Size(max = 128) String endpointKey,
            Long taskId,
            @Size(max = 128) String traceId,
            @NotNull GovernanceDataDirection direction,
            @NotBlank @Size(max = 128) String dataCategory,
            @NotNull GovernanceDataSensitivity sensitivity,
            @NotNull GovernanceDataAction action,
            boolean internalData,
            boolean companySecret,
            @Min(0) long tokenCount,
            @Min(0) long payloadBytes,
            Long policyId,
            @Size(max = 256) String policyRule,
            Instant observedAt
    ) {
    }

    public record PreflightRequest(
            @NotBlank @Size(max = 128) String decisionKey,
            @NotBlank @Size(max = 128) String eventKey,
            Long agentId,
            @Size(max = 128) String endpointKey,
            Long taskId,
            @Size(max = 128) String traceId,
            @NotNull GovernanceDataDirection direction,
            @NotBlank @Size(max = 128) String dataCategory,
            @NotNull GovernanceDataSensitivity sensitivity,
            boolean internalData,
            boolean companySecret,
            @Min(0) long tokenCount,
            @Min(0) long payloadBytes,
            @NotBlank @Size(max = 128) String classifier,
            @Size(max = 128) String provider,
            @Size(max = 128) String modelName,
            @Size(max = 64) List<@Size(max = 128) String> detectedTypes,
            @Size(max = 64) String requestFingerprint,
            Instant observedAt
    ) {
    }

    public record DataEventResponse(
            Long id,
            String eventKey,
            Long agentId,
            String endpointKey,
            Long taskId,
            String traceId,
            GovernanceDataDirection direction,
            String dataCategory,
            GovernanceDataSensitivity sensitivity,
            GovernanceDataAction action,
            boolean internalData,
            boolean companySecret,
            long tokenCount,
            long payloadBytes,
            Long policyId,
            String policyRule,
            Instant observedAt,
            Instant createdAt
    ) {
    }

    public record SubjectUsage(
            String subjectKey,
            String subjectName,
            Long agentId,
            String endpointKey,
            long events,
            long modelInputs,
            long internalEvents,
            long companySecretEvents,
            long blockedEvents,
            long redactedEvents,
            long tokenCount
    ) {
    }

    public record MatchedPolicy(
            Long id,
            String name,
            PolicyType policyType,
            EnforcementMode enforcementMode,
            String reasonCode,
            Long revision
    ) {
    }

    public record PreflightResponse(
            Long id,
            String decisionKey,
            Long dataEventId,
            GovernanceDataAction action,
            GovernanceGatewayDirective gatewayDirective,
            boolean requestAllowed,
            int matchedPolicyCount,
            List<MatchedPolicy> matchedPolicies,
            List<String> reasonCodes,
            String classifier,
            String provider,
            String modelName,
            GovernanceApprovalStatus approvalStatus,
            String approvedBy,
            String approvalReason,
            Instant approvedAt,
            String requestFingerprint,
            Instant decidedAt,
            String approvalContextState
    ) {
    }

    public record ApprovalRequest(
            @jakarta.validation.constraints.NotBlank @Size(max = 128) String approver,
            @Size(max = 512) String reason
    ) {
    }

    public record EnforcementStatus(
            boolean enforcementEnabled,
            boolean failClosedOnSecret,
            long enabledPolicies,
            long preflightDecisions,
            long blockedDecisions,
            long redactionDecisions,
            long approvalDecisions,
            long pendingApprovals,
            Instant lastDecisionAt
    ) {
    }

    public record GovernanceSummary(
            int windowHours,
            long totalEvents,
            long modelInputEvents,
            long internalInfoEvents,
            long companySecretEvents,
            long allowedEvents,
            long warnedEvents,
            long redactedEvents,
            long blockedEvents,
            long approvalRequiredEvents,
            double internalExposureRate,
            double secretExposureRate,
            double blockRate,
            long totalTokens,
            long totalPayloadBytes,
            Instant lastEventAt,
            List<SubjectUsage> subjectUsage
    ) {
    }

    static DataEventResponse from(GovernanceDataEvent event) {
        return new DataEventResponse(
                event.id, event.eventKey, event.agentId, event.endpointKey, event.taskId, event.traceId,
                event.direction, event.dataCategory, event.sensitivity, event.action,
                Boolean.TRUE.equals(event.internalData), Boolean.TRUE.equals(event.companySecret),
                event.tokenCount, event.payloadBytes, event.policyId, event.policyRule,
                event.observedAt, event.createdAt
        );
    }
}
