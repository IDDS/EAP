package com.eap.platform.safety;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class SafetyDtos {
    private SafetyDtos() {
    }

    public record SessionRequest(
            @NotBlank @Size(max = 128) String sessionKey,
            Long rootAgentId,
            @NotBlank @Size(max = 128) String objective
    ) {
    }

    public record SafetyEventRequest(
            @NotBlank @Size(max = 128) String sessionKey,
            Long agentId,
            Long parentAgentId,
            @NotNull SafetyEventType eventType,
            @NotBlank @Size(max = 128) String action,
            @NotBlank @Size(max = 256) String target,
            @Size(max = 1024) String payloadSummary,
            String payloadJson
    ) {
    }

    public record ArtifactRequest(
            @NotBlank @Size(max = 128) String sessionKey,
            @NotNull Long eventId,
            @NotBlank @Size(max = 512) String artifactUri,
            @NotBlank @Size(max = 64) String artifactType,
            @Size(max = 128) String contentHash
    ) {
    }

    public record SessionResponse(Long id, String sessionKey, Long rootAgentId, String objective, String status, Instant createdAt, Instant updatedAt) {
    }

    public record EventResponse(Long id, String sessionKey, Long agentId, Long parentAgentId, SafetyEventType eventType, String action, String target, String payloadSummary, Instant createdAt) {
    }

    public record DecisionResponse(Long id, Long eventId, String sessionKey, SafetyDecision decision, SafetySeverity severity, String reasonCode, String reason, String controlActions, String sidecarMode, Instant createdAt) {
    }

    public record ArtifactResponse(Long id, String sessionKey, Long eventId, String artifactUri, String artifactType, String contentHash, Instant createdAt) {
    }

    public record SafetyEvaluationResponse(EventResponse event, DecisionResponse decision) {
    }

    public record SafetySummary(long sessionCount, long eventCount, long denyCount, long askCount, long criticalAlertCount, List<DecisionResponse> recentDecisions) {
    }

    static SessionResponse from(SafetySession session) {
        return new SessionResponse(session.id, session.sessionKey, session.rootAgentId, session.objective, session.status, session.createdAt, session.updatedAt);
    }

    static EventResponse from(SafetyEvent event) {
        return new EventResponse(event.id, event.sessionKey, event.agentId, event.parentAgentId, event.eventType, event.action, event.target, event.payloadSummary, event.createdAt);
    }

    static DecisionResponse from(SafetyDecisionLog log) {
        return new DecisionResponse(log.id, log.eventId, log.sessionKey, log.decision, log.severity, log.reasonCode, log.reason, log.controlActions, log.sidecarMode, log.createdAt);
    }

    static ArtifactResponse from(SafetyArtifact artifact) {
        return new ArtifactResponse(artifact.id, artifact.sessionKey, artifact.eventId, artifact.artifactUri, artifact.artifactType, artifact.contentHash, artifact.createdAt);
    }
}

