package com.eap.platform.observability;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class ContentObservationDtos {
    private ContentObservationDtos() {
    }

    public record CaptureRequest(
            @NotBlank @Size(max = 128) String observationKey,
            @Size(max = 128) String eventKey,
            @Size(max = 128) String traceId,
            @Size(max = 128) String spanId,
            Long agentId,
            @Size(max = 128) String endpointKey,
            Long taskId,
            @Size(max = 128) String provider,
            @Size(max = 128) String modelName,
            @NotNull ContentDirection direction,
            @NotBlank @Size(max = 4_194_304) String content,
            @Size(max = 4_194_304) String redactedContent,
            @Size(max = 128) String dataCategory,
            @Size(max = 32) String sensitivity,
            @Size(max = 32) String governanceAction,
            Instant observedAt
    ) {
    }

    public record ContentObservationResponse(
            Long id,
            String observationKey,
            String eventKey,
            String traceId,
            String spanId,
            Long agentId,
            String endpointKey,
            Long taskId,
            String provider,
            String modelName,
            ContentDirection direction,
            ContentCaptureMode captureMode,
            String contentHash,
            Long contentBytes,
            Long estimatedTokens,
            String contentJson,
            String dataCategory,
            String sensitivity,
            String governanceAction,
            boolean redactionApplied,
            Instant observedAt,
            Instant createdAt
    ) {
        public ContentObservationResponse withoutContent() {
            return new ContentObservationResponse(
                    id, observationKey, eventKey, traceId, spanId, agentId, endpointKey, taskId,
                    provider, modelName, direction, captureMode, contentHash, contentBytes, estimatedTokens,
                    null, dataCategory, sensitivity, governanceAction, redactionApplied, observedAt, createdAt
            );
        }
    }

    public record ObservationStatus(
            boolean enabled,
            ContentCaptureMode captureMode,
            long maxContentBytes,
            long totalObservations,
            long inputObservations,
            long outputObservations,
            Instant lastObservedAt
    ) {
    }

    static ContentObservationResponse from(ContentObservation observation) {
        return new ContentObservationResponse(
                observation.id, observation.observationKey, observation.eventKey, observation.traceId,
                observation.spanId, observation.agentId, observation.endpointKey, observation.taskId,
                observation.provider, observation.modelName, observation.direction, observation.captureMode,
                observation.contentHash, observation.contentBytes, observation.estimatedTokens, observation.contentJson,
                observation.dataCategory, observation.sensitivity, observation.governanceAction,
                Boolean.TRUE.equals(observation.redactionApplied), observation.observedAt, observation.createdAt
        );
    }
}
