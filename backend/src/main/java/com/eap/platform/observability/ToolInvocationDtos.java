package com.eap.platform.observability;

import java.time.Instant;

public final class ToolInvocationDtos {
    private ToolInvocationDtos() {
    }

    public record ToolInvocationResponse(
            Long id,
            String invocationKey,
            String traceId,
            String spanId,
            String parentSpanId,
            Long agentId,
            String endpointKey,
            String runtimeInstanceKey,
            Long taskId,
            ToolInvocationSource observationSource,
            ToolInvocationKind toolKind,
            String toolName,
            String mcpServerName,
            ToolInvocationStatus status,
            long durationMs,
            String inputObservationKey,
            String outputObservationKey,
            String attributesJson,
            Instant observedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    static ToolInvocationResponse from(ToolInvocationEvent event) {
        return new ToolInvocationResponse(
                event.id, event.invocationKey, event.traceId, event.spanId, event.parentSpanId,
                event.agentId, event.endpointKey, event.runtimeInstanceKey, event.taskId,
                event.observationSource, event.toolKind, event.toolName, event.mcpServerName,
                event.status, event.durationMs == null ? 0L : event.durationMs,
                event.inputObservationKey, event.outputObservationKey, TraceMetadata.project(event.attributesJson),
                event.observedAt, event.createdAt, event.updatedAt
        );
    }
}
