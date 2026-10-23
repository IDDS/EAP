package com.eap.platform.evaluation;

import com.eap.platform.observability.ToolInvocationEvent;
import com.eap.platform.observability.ToolInvocationEventRepository;
import com.eap.platform.observability.ToolInvocationStatus;
import com.eap.platform.observability.TraceSpanKind;
import com.eap.platform.observability.TraceSpanObservation;
import com.eap.platform.observability.TraceSpanObservationRepository;
import com.eap.platform.knowledge.KnowledgeInteraction;
import com.eap.platform.knowledge.KnowledgeInteractionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Projects normalized tool observations into the EAP evaluation plane.
 * This is intentionally observe-only: it writes evaluation results but does
 * not dispatch a control command or alter an existing governance decision.
 */
@Service
public class EvaluationProjectionService {
    private final ToolInvocationEventRepository toolRepository;
    private final TraceSpanObservationRepository traceRepository;
    private final KnowledgeInteractionRepository knowledgeRepository;
    private final EvaluationService evaluationService;
    private final ObjectMapper objectMapper;
    private final EvaluationScanCoordinator coordinator;

    public EvaluationProjectionService(
            ToolInvocationEventRepository toolRepository,
            TraceSpanObservationRepository traceRepository,
            KnowledgeInteractionRepository knowledgeRepository,
            EvaluationService evaluationService,
            ObjectMapper objectMapper, EvaluationScanCoordinator coordinator
    ) {
        this.toolRepository = toolRepository;
        this.traceRepository = traceRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.evaluationService = evaluationService;
        this.objectMapper = objectMapper;
        this.coordinator = coordinator;
    }

    public record Progress(String phase, int processed, int total, int scanned, int evaluated, int skipped, int failed) { }

    public EvaluationDtos.EvaluationSyncResponse synchronize(int requestedLimit) {
        return synchronize(requestedLimit, progress -> { });
    }

    public EvaluationDtos.EvaluationSyncResponse synchronize(int requestedLimit, java.util.function.Consumer<Progress> listener) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        var lease = coordinator.acquire(toolRepository.scanUpperBound(), traceRepository.scanUpperBound(), knowledgeRepository.scanUpperBound());
        int scanned = 0, evaluated = 0, skipped = 0, failed = 0, processed = 0;
        try {
            var page = org.springframework.data.domain.PageRequest.of(0, limit);
            var events = toolRepository.findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(lease.tools().cursor(), lease.tools().upper(), page);
            var spans = traceRepository.findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(lease.spans().cursor(), lease.spans().upper(), page);
            var knowledge = knowledgeRepository.findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(lease.rag().cursor(), lease.rag().upper(), page);
            int total = events.size() + spans.size() + knowledge.size();
            listener.accept(new Progress("TOOLS", 0, total, 0, 0, 0, 0));
            for (var event : events) {
                coordinator.heartbeat(lease.owner());
                scanned++;
                if (event.getStatus() == null || event.getStatus() == ToolInvocationStatus.REQUESTED) skipped++;
                else try {
                    JsonNode attributes = parse(event.getAttributesJson());
                    ObjectNode input = objectMapper.createObjectNode();
                    JsonNode required = attributes.path("requiredArguments");
                    input.set("requiredArguments", required.isArray() ? required : objectMapper.createArrayNode());
                    ObjectNode actual = objectMapper.createObjectNode();
                    actual.put("status", event.getStatus().name());
                    actual.put("toolName", event.getToolName());
                    actual.put("durationMs", event.getDurationMs() == null ? 0L : event.getDurationMs());
                    JsonNode arguments = attributes.path("arguments");
                    if (arguments.isObject()) actual.set("arguments", arguments);
                    // Mutable source records produce a new result only when the evaluation input changes.
                    String revision = digest(event.getAgentId() + "|" + event.getTraceId() + "|" + event.getSpanId() + "|" + input + "|" + actual);
                    evaluate(event, "TOOL_STATUS", "tool-" + event.getId() + "-status-" + revision, objectMapper.createObjectNode(), actual);
                    evaluated++;
                    if (required.isArray() && !required.isEmpty()) {
                        evaluate(event, "TOOL_ARGUMENT_VALIDITY", "tool-" + event.getId() + "-args-" + revision, input, actual);
                        evaluated++;
                    }
                } catch (RuntimeException exception) { failed++; }
                listener.accept(new Progress("TOOLS", ++processed, total, scanned, evaluated, skipped, failed));
                coordinator.advance(lease.owner(), "TOOLS", event.getId());
            }
            if (events.size() < limit) coordinator.advance(lease.owner(), "TOOLS", lease.tools().upper());
            var visited = new java.util.HashSet<String>();
            listener.accept(new Progress("TRACES", processed, total, scanned, evaluated, skipped, failed));
            for (var span : spans) {
                coordinator.heartbeat(lease.owner());
                if (span.getTraceId() != null && visited.add(span.getTraceId())) {
                    scanned++;
                    try {
                        // Fetch the trace independently of the event page. Never score a truncated trace.
                        var complete = traceRepository.findByTraceIdOrderByIdAsc(span.getTraceId(), org.springframework.data.domain.PageRequest.of(0, 2001));
                        if (complete.size() > 2000 || !projectTrace(span.getTraceId(), complete)) skipped++;
                        else evaluated++;
                    } catch (RuntimeException exception) { failed++; }
                }
                listener.accept(new Progress("TRACES", ++processed, total, scanned, evaluated, skipped, failed));
                coordinator.advance(lease.owner(), "TRACES", span.getId());
            }
            if (spans.size() < limit) coordinator.advance(lease.owner(), "TRACES", lease.spans().upper());
            listener.accept(new Progress("RAG", processed, total, scanned, evaluated, skipped, failed));
            for (var interaction : knowledge) {
                coordinator.heartbeat(lease.owner());
                scanned++;
                if (!isRagInteraction(interaction)) skipped++;
                else try { projectRag(interaction); evaluated++; }
                catch (RuntimeException exception) { failed++; }
                listener.accept(new Progress("RAG", ++processed, total, scanned, evaluated, skipped, failed));
                coordinator.advance(lease.owner(), "RAG", interaction.getId());
            }
            if (knowledge.size() < limit) coordinator.advance(lease.owner(), "RAG", lease.rag().upper());
            listener.accept(new Progress("COMPLETE", processed, total, scanned, evaluated, skipped, failed));
            return new EvaluationDtos.EvaluationSyncResponse(scanned, evaluated, skipped, failed);
        } finally { coordinator.release(lease.owner()); }
    }

    private void projectRag(KnowledgeInteraction interaction) {
        ObjectNode actual = objectMapper.createObjectNode();
        int inputSourceCount = nonNegative(interaction.getInputSourceCount());
        int outputSourceCount = nonNegative(interaction.getOutputSourceCount());
        List<String> inputRefs = refs(interaction.getInputRefsJson());
        List<String> outputRefs = refs(interaction.getOutputRefsJson());
        actual.put("status", interaction.getStatus() == null ? "UNKNOWN" : interaction.getStatus().name());
        actual.put("inputSourceCount", inputSourceCount);
        actual.put("outputSourceCount", outputSourceCount);
        actual.put("inputRefsCount", inputRefs.size());
        actual.put("outputRefsCount", outputRefs.size());
        actual.put("referenceOverlapCount", inputRefs.stream().filter(outputRefs::contains).distinct().count());
        actual.put("adopted", Boolean.TRUE.equals(interaction.getAdopted()));
        actual.put("reused", Boolean.TRUE.equals(interaction.getReused()));
        actual.put("qualityScore", interaction.getQualityScore() == null ? -1 : interaction.getQualityScore());
        String traceId = interaction.getTraceId();
        String fingerprint = digest(interaction.getInteractionKey() + "|" + traceId + "|"
                + inputSourceCount + "|" + outputSourceCount + "|" + inputRefs + "|" + outputRefs
                + "|" + interaction.getAgentId() + "|" + actual);
        evaluationService.evaluate(new EvaluationDtos.EvaluationRequest(
                "rag-" + fingerprint, "RAG_CONTEXT_COVERAGE", "v1", null, interaction.getAgentId(),
                traceId, null, objectMapper.createObjectNode(), null, actual, interaction.getCreatedAt()
        ));
    }

    private boolean isRagInteraction(KnowledgeInteraction interaction) {
        String types = (Objects.toString(interaction.getInputType(), "") + " "
                + Objects.toString(interaction.getOutputType(), "")).toUpperCase(Locale.ROOT);
        return types.contains("RAG") || types.contains("RETRIEV") || types.contains("CONTEXT")
                || (nonNegative(interaction.getInputSourceCount()) > 0 && nonNegative(interaction.getOutputSourceCount()) > 0);
    }

    private List<String> refs(String json) {
        JsonNode node = parse(json);
        if (!node.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asText).filter(item -> item != null && !item.isBlank()).distinct().limit(100).toList();
    }

    private int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private boolean projectTrace(String traceId, List<TraceSpanObservation> spans) {
        if (spans.isEmpty()) return false;
        List<TraceSpanObservation> ordered = spans.stream()
                .sorted(java.util.Comparator.comparing(TraceSpanObservation::getObservedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .thenComparing(TraceSpanObservation::getId, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
        long failedSpans = ordered.stream().filter(this::failed).count();
        long toolSpans = ordered.stream().filter(this::tool).count();
        long failedToolSpans = ordered.stream().filter(item -> tool(item) && failed(item)).count();
        Boolean explicitCompletion = explicitCompletion(ordered);
        // A successful span or a partial trace is not evidence of task completion.
        // Conflicting signals or multiple attributed agents need a task-scoped contract first.
        if (explicitCompletion == null || ordered.stream().map(TraceSpanObservation::getAgentId)
                .filter(Objects::nonNull).distinct().count() > 1) return false;
        boolean completed = explicitCompletion;
        String inferenceMode = "EXPLICIT";
        long durationMs = ordered.stream().map(TraceSpanObservation::getDurationMs)
                .filter(Objects::nonNull).mapToLong(value -> Math.max(0L, value)).sum();
        TraceSpanObservation latest = ordered.get(ordered.size() - 1);

        ObjectNode actual = objectMapper.createObjectNode();
        actual.put("completed", completed);
        actual.put("inferenceMode", inferenceMode);
        actual.put("spanCount", ordered.size());
        actual.put("failedSpanCount", failedSpans);
        actual.put("toolCount", toolSpans);
        actual.put("failedToolCount", failedToolSpans);
        actual.put("modelCount", ordered.stream().filter(item -> item.getSpanKind() == TraceSpanKind.MODEL).count());
        actual.put("sumSpanDurationMs", durationMs);
        actual.put("evidenceScope", "RECEIVED_SPANS");
        actual.put("latestSpanId", Objects.toString(latest.getSpanId(), ""));
        actual.put("heuristic", explicitCompletion == null);

        String fingerprint = digest(traceId + "|" + latest.getAgentId() + "|" + actual);
        evaluateTrace(traceId, latest, "trace-action-" + fingerprint, actual);
        return true;
    }

    private void evaluateTrace(String traceId, TraceSpanObservation latest, String resultKey, ObjectNode actual) {
        evaluationService.evaluate(new EvaluationDtos.EvaluationRequest(
                resultKey, "TRACE_ACTION_COMPLETION", "v1", null, latest.getAgentId(), traceId,
                latest.getSpanId(), objectMapper.createObjectNode(), null, actual, latest.getObservedAt()
        ));
    }

    private Boolean explicitCompletion(List<TraceSpanObservation> spans) {
        String[] keys = {"eap.task.completed", "agent.task.completed", "task.completed", "action.completed"};
        Boolean signal = null;
        for (TraceSpanObservation span : spans) {
            JsonNode attributes = parse(span.getAttributesJson());
            for (String key : keys) {
                JsonNode value = attributes.get(key);
                if (value == null || value.isNull()) continue;
                Boolean next = null;
                if (value.isBoolean()) next = value.asBoolean();
                else if (value.isTextual() && ("true".equalsIgnoreCase(value.asText()) || "false".equalsIgnoreCase(value.asText())))
                    next = Boolean.parseBoolean(value.asText());
                if (next != null) {
                    if (signal != null && !signal.equals(next)) return null;
                    signal = next;
                }
            }
        }
        return signal;
    }

    private boolean tool(TraceSpanObservation span) {
        return span.getSpanKind() == TraceSpanKind.TOOL || span.getSpanKind() == TraceSpanKind.MCP;
    }

    private boolean failed(TraceSpanObservation span) {
        String status = span.getStatus();
        if (status == null || status.isBlank()) return false;
        String normalized = status.toUpperCase(Locale.ROOT);
        return normalized.contains("FAIL") || normalized.contains("ERROR") || normalized.contains("CANCEL");
    }

    private void evaluate(
            ToolInvocationEvent event,
            String evaluatorKey,
            String resultKey,
            ObjectNode input,
            ObjectNode actual
    ) {
        evaluationService.evaluate(new EvaluationDtos.EvaluationRequest(
                resultKey, evaluatorKey, "v1", null, event.getAgentId(), event.getTraceId(), event.getSpanId(),
                input, null, actual, event.getObservedAt()
        ));
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return objectMapper.createObjectNode();
        try {
            JsonNode node = objectMapper.readTree(json);
            return node == null ? objectMapper.createObjectNode() : node;
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 48);
        } catch (Exception exception) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
