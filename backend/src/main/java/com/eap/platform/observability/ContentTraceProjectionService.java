package com.eap.platform.observability;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

/** A derived model-call observation, not an SDK span or a complete task trace. */
@Service
public class ContentTraceProjectionService {
    private final TraceSpanObservationRepository repository;

    public ContentTraceProjectionService(TraceSpanObservationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void project(String traceId, String spanId, String endpointKey, Long agentId,
                        String status, long durationMs, String attributesJson, Instant observedAt) {
        if (repository.findByTraceIdAndSpanId(traceId, spanId).isPresent()) {
            throw new IllegalStateException("Content projection span identity already exists");
        }
        TraceSpanObservation span = new TraceSpanObservation();
        span.traceId = traceId;
        span.spanId = spanId;
        span.endpointKey = endpointKey;
        span.agentId = agentId;
        span.spanName = "模型调用（内容采集投影）";
        span.spanKind = TraceSpanKind.MODEL;
        span.operation = "content.capture";
        span.status = status;
        span.durationMs = durationMs;
        span.attributesJson = attributesJson;
        span.observedAt = observedAt;
        repository.save(span);
    }
}
