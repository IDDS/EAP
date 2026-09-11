package com.eap.platform.observability;

import java.time.Instant;
import java.util.List;

public final class OtlpTraceIngestionDtos {
    private OtlpTraceIngestionDtos() {
    }

    public record IngestionResponse(
            int spansScanned,
            int genAiSpansDetected,
            int invocationsRecorded,
            int contentObservationsRecorded,
            int endpointsRegistered,
            int traceSpansRecorded,
            int toolSpansRecorded,
            int mcpSpansRecorded,
            int ragSpansRecorded,
            int a2aSpansRecorded,
            int knowledgeInteractionsRecorded,
            List<String> warnings
    ) {
    }

    public record TraceSpanResponse(
            Long id,
            String traceId,
            String spanId,
            String parentSpanId,
            String endpointKey,
            Long agentId,
            String spanName,
            TraceSpanKind spanKind,
            String operation,
            String toolName,
            String mcpServerName,
            String ragSource,
            String status,
            long durationMs,
            String attributesJson,
            Instant observedAt,
            Instant startedAt,
            Instant endedAt
    ) {
    }
}
