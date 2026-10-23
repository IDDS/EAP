package com.eap.platform.discovery;

import com.eap.platform.evaluation.EvaluationDtos;
import com.eap.platform.evaluation.EvaluationService;
import com.eap.platform.observability.ContentTraceProjectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects persisted metadata in its own transaction; ingestion never waits for analysis. */
@Service
public class ContentAnalysisProjectionService {
    private final BusinessContentRecordRepository repository;
    private final ContentTraceProjectionService traces;
    private final EvaluationService evaluations;
    private final ObjectMapper mapper;

    public ContentAnalysisProjectionService(BusinessContentRecordRepository repository,
                                            ContentTraceProjectionService traces,
                                            EvaluationService evaluations, ObjectMapper mapper) {
        this.repository = repository;
        this.traces = traces;
        this.evaluations = evaluations;
        this.mapper = mapper;
    }

    @Transactional(timeout = 10)
    public void project(Long id) {
        BusinessContentRecord record = repository.lockForProjection(id).orElse(null);
        if (record == null || !"PENDING".equals(record.projectionStatus) || !"OUTPUT".equals(record.direction)) return;
        if (record.traceId == null || record.traceId.isBlank()) {
            record.projectionStatus = "SKIPPED";
            record.projectionError = "MISSING_TRACE_ID";
            return;
        }
        JsonNode metadata = parse(record.metadataJson);
        String outcome = outcome(metadata);
        String spanId = "eap-content-" + record.id;
        ObjectNode attributes = mapper.createObjectNode();
        attributes.put("eap.projection.source", "CONTENT_PROXY");
        attributes.put("eap.projection.content_record_id", record.id);
        if (record.spanId != null && !record.spanId.isBlank()) {
            attributes.put("eap.projection.original_span_id", record.spanId);
        }
        attributes.put("eap.evaluation.scope", "MODEL_CALL_ONLY");
        attributes.put("eap.task.completion_evidence", "UNKNOWN");
        attributes.put("eap.model.outcome", outcome);
        long latency = boundedLong(metadata.path("latencyMs"), 86_400_000L);
        attributes.put("eap.duration.reported", metadata.path("latencyMs").isIntegralNumber());
        int status = httpStatus(metadata);
        if (status != 0) attributes.put("http.response.status_code", status);
        // Only whitelisted numeric usage is copied. Never copy body, error text or arbitrary metadata.
        JsonNode usage = metadata.path("usage");
        for (String key : new String[]{"prompt_tokens", "completion_tokens", "total_tokens"}) {
            if (usage.path(key).isIntegralNumber()) {
                attributes.put("gen_ai.usage." + key, boundedLong(usage.path(key), 1_000_000_000L));
            }
        }
        traces.project(record.traceId, spanId, record.endpointKey, record.agentId, outcome,
                latency, attributes.toString(), record.observedAt);
        if (!"UNKNOWN".equals(outcome)) {
            ObjectNode actual = mapper.createObjectNode();
            actual.put("outcome", outcome);
            actual.put("scope", "MODEL_CALL_ONLY");
            actual.put("taskCompletion", "UNKNOWN");
            actual.put("source", "CONTENT_PROXY");
            evaluations.evaluate(new EvaluationDtos.EvaluationRequest(
                    "content-call-" + record.id + "-v1", "MODEL_CALL_STATUS", "v1", null,
                    record.agentId, record.traceId, spanId, mapper.createObjectNode(), null,
                    actual, record.observedAt));
        }
        record.projectionStatus = "DONE";
        record.projectionError = "UNKNOWN".equals(outcome) ? "UNKNOWN_OUTCOME_NO_SCORE" : null;
    }

    @Transactional(timeout = 10)
    public void recordFailure(Long id, String errorType) {
        BusinessContentRecord record = repository.lockForProjection(id).orElse(null);
        if (record == null || !"PENDING".equals(record.projectionStatus)) return;
        record.projectionAttempts++;
        record.projectionError = errorType.substring(0, Math.min(128, errorType.length()));
        if (record.projectionAttempts >= 3) record.projectionStatus = "FAILED";
    }

    private JsonNode parse(String json) {
        if (json == null) return mapper.createObjectNode();
        try {
            JsonNode value = mapper.readTree(json);
            return value != null && value.isObject() ? value : mapper.createObjectNode();
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return mapper.createObjectNode();
        }
    }

    private String outcome(JsonNode metadata) {
        String outcome = metadata.path("outcome").asText("");
        int status = httpStatus(metadata);
        if ("FAILED".equalsIgnoreCase(outcome) || status >= 400) return "FAILED";
        if ("SUCCEEDED".equalsIgnoreCase(outcome) || status >= 200 && status < 300) return "SUCCEEDED";
        return "UNKNOWN";
    }

    private int httpStatus(JsonNode metadata) {
        JsonNode value = metadata.path("httpStatus");
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 100 && value.asInt() <= 599
                ? value.asInt() : 0;
    }

    private long boundedLong(JsonNode value, long maximum) {
        return value.isIntegralNumber() && value.canConvertToLong() ? Math.max(0, Math.min(maximum, value.asLong())) : 0;
    }
}
