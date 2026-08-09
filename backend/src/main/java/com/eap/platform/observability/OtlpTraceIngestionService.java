package com.eap.platform.observability;

import com.eap.platform.common.BusinessException;
import com.eap.platform.discovery.AgentDiscoveryDtos;
import com.eap.platform.discovery.AgentDiscoveryService;
import com.eap.platform.telemetry.ModelInvocationStatus;
import com.eap.platform.telemetry.ModelObservationSource;
import com.eap.platform.telemetry.ModelTelemetryDtos;
import com.eap.platform.telemetry.ModelTelemetryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class OtlpTraceIngestionService {
    private static final int MAX_SPANS = 10_000;
    private final ObjectMapper objectMapper;
    private final AgentDiscoveryService discoveryService;
    private final ModelTelemetryService telemetryService;
    private final ContentObservationService contentObservationService;
    private final SensitiveContentSanitizer sanitizer;

    public OtlpTraceIngestionService(
            ObjectMapper objectMapper,
            AgentDiscoveryService discoveryService,
            ModelTelemetryService telemetryService,
            ContentObservationService contentObservationService,
            SensitiveContentSanitizer sanitizer
    ) {
        this.objectMapper = objectMapper;
        this.discoveryService = discoveryService;
        this.telemetryService = telemetryService;
        this.contentObservationService = contentObservationService;
        this.sanitizer = sanitizer;
    }

    public OtlpTraceIngestionDtos.IngestionResponse ingest(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new BusinessException("OTLP traces 必须是 JSON 对象");
        }
        ArrayNode resources = array(payload.path("resourceSpans"));
        if (resources == null) {
            throw new BusinessException("OTLP traces 缺少 resourceSpans");
        }
        Counters counters = new Counters();
        List<String> warnings = new ArrayList<>();
        for (JsonNode resourceSpan : resources) {
            Map<String, JsonNode> resource = attributes(resourceSpan.path("resource").path("attributes"));
            String endpointKey = endpointKey(resource);
            registerEndpoint(endpointKey, resource);
            counters.endpointsRegistered++;
            ArrayNode scopes = firstArray(resourceSpan, "scopeSpans", "instrumentationLibrarySpans");
            if (scopes == null) continue;
            for (JsonNode scope : scopes) {
                ArrayNode spans = array(scope.path("spans"));
                if (spans == null) continue;
                for (JsonNode span : spans) {
                    if (++counters.spansScanned > MAX_SPANS) {
                        warnings.add("超过单次最大 Span 数量 " + MAX_SPANS + "，其余数据已忽略");
                        return response(counters, warnings);
                    }
                    Map<String, JsonNode> attrs = attributes(span.path("attributes"));
                    if (!isGenAiSpan(span, attrs)) continue;
                    counters.genAiSpansDetected++;
                    try {
                        ingestSpan(span, attrs, endpointKey, counters);
                    } catch (RuntimeException exception) {
                        if (warnings.size() < 20) warnings.add("Span " + text(span, "spanId", "unknown") + "：" + exception.getMessage());
                    }
                }
            }
        }
        return response(counters, warnings);
    }

    private void ingestSpan(JsonNode span, Map<String, JsonNode> attrs, String endpointKey, Counters counters) {
        String traceId = text(span, "traceId", hash(span.toString()).substring(0, 32));
        String spanId = text(span, "spanId", hash(span.toString()).substring(0, 16));
        String eventKey = normalize("otlp-" + traceId + "-" + spanId, 128);
        String provider = firstText(attrs, "gen_ai.provider.name", "gen_ai.system", "llm.vendor", "ai.model.provider");
        String model = firstText(attrs, "gen_ai.response.model", "gen_ai.request.model", "llm.response.model", "llm.request.model", "ai.model.id");
        if (provider == null) provider = inferProvider(model, firstText(attrs, "server.address", "net.peer.name"));
        if (model == null) model = "unknown-model";
        long inputTokens = firstLong(attrs, "gen_ai.usage.input_tokens", "llm.usage.prompt_tokens", "llm.token_count.prompt");
        long outputTokens = firstLong(attrs, "gen_ai.usage.output_tokens", "llm.usage.completion_tokens", "llm.token_count.completion");
        long cachedTokens = firstLong(attrs, "gen_ai.usage.cache_read.input_tokens", "gen_ai.usage.cached_tokens");
        long reasoningTokens = firstLong(attrs, "gen_ai.usage.reasoning.output_tokens", "gen_ai.usage.reasoning_tokens");
        long latencyMs = durationMillis(span, attrs);
        ModelInvocationStatus status = status(span, attrs);
        Instant observedAt = observedAt(span);
        String errorType = status == ModelInvocationStatus.FAILED
                ? firstText(attrs, "error.type", "exception.type", "gen_ai.response.status") : null;
        int toolCalls = hasAny(attrs, "gen_ai.tool.call.id", "gen_ai.tool.call.name", "llm.request.functions") ? 1 : 0;
        int mcpServers = hasAny(attrs, "mcp.server.name", "mcp.server.version") ? 1 : 0;
        BigDecimal cost = decimal(attrs, "gen_ai.usage.cost", "llm.usage.total_cost");
        String currency = firstText(attrs, "gen_ai.usage.cost.currency", "llm.usage.currency");

        telemetryService.recordPassive(new ModelTelemetryDtos.ModelInvocationRequest(
                eventKey, traceId, spanId, null, endpointKey, null, null, null,
                provider, model, status, inputTokens, outputTokens, cachedTokens, reasoningTokens,
                latencyMs, cost, currency == null ? "USD" : currency, toolCalls, mcpServers,
                errorType, observedAt
        ), ModelObservationSource.OTLP);
        counters.invocationsRecorded++;

        JsonNode input = firstNode(attrs, "gen_ai.input.messages", "gen_ai.prompt", "llm.prompts", "llm.input_messages", "traceloop.entity.input");
        JsonNode output = firstNode(attrs, "gen_ai.output.messages", "gen_ai.completion", "llm.completions", "llm.output_messages", "traceloop.entity.output");
        if (input != null) {
            capture(eventKey + "-input", eventKey, traceId, spanId, endpointKey, provider, model, ContentDirection.INPUT, input, observedAt);
            counters.contentObservationsRecorded++;
        }
        if (output != null) {
            capture(eventKey + "-output", eventKey, traceId, spanId, endpointKey, provider, model, ContentDirection.OUTPUT, output, observedAt);
            counters.contentObservationsRecorded++;
        }
    }

    private void capture(
            String observationKey, String eventKey, String traceId, String spanId, String endpointKey,
            String provider, String model, ContentDirection direction, JsonNode content, Instant observedAt
    ) {
        String original = content.isTextual() ? content.asText() : content.toString();
        SensitiveContentSanitizer.SanitizedContent safe = sanitizer.sanitize(original);
        contentObservationService.capture(new ContentObservationService.CaptureCommand(
                normalize(observationKey, 128), eventKey, traceId, spanId, null, endpointKey, null,
                provider, model, direction, original, safe.safeContent(), safe.dataCategory(),
                safe.sensitivity(), safe.governanceAction(), observedAt
        ));
    }

    private void registerEndpoint(String endpointKey, Map<String, JsonNode> resource) {
        String host = firstText(resource, "k8s.pod.name", "host.name", "service.name", "service.instance.id");
        String operatingSystem = firstText(resource, "os.type", "cloud.platform", "k8s.cluster.name");
        String sdkName = firstText(resource, "telemetry.sdk.name");
        String sdkVersion = firstText(resource, "telemetry.sdk.version");
        discoveryService.registerEndpoint(new AgentDiscoveryDtos.EndpointRegistrationRequest(
                endpointKey, host == null ? endpointKey : host, null,
                operatingSystem == null ? "OTLP" : operatingSystem,
                normalize((sdkName == null ? "otlp" : sdkName) + (sdkVersion == null ? "" : "/" + sdkVersion), 64)
        ));
    }

    private String endpointKey(Map<String, JsonNode> resource) {
        String identity = firstText(resource, "k8s.pod.uid", "service.instance.id", "container.id", "host.id");
        String service = firstText(resource, "service.name", "k8s.pod.name", "host.name");
        if (identity == null) identity = service;
        if (identity == null) identity = "unknown-workload";
        String prefix = service == null ? "workload" : service.replaceAll("[^a-zA-Z0-9._-]", "-");
        return normalize("otlp-" + prefix + "-" + hash(identity).substring(0, 16), 128);
    }

    private boolean isGenAiSpan(JsonNode span, Map<String, JsonNode> attrs) {
        if (attrs.keySet().stream().anyMatch(key -> key.startsWith("gen_ai.") || key.startsWith("llm.") || key.startsWith("ai.model."))) {
            return true;
        }
        String name = text(span, "name", "").toLowerCase(Locale.ROOT);
        return name.contains("chat") || name.contains("completion") || name.contains("generate_content") || name.contains("embeddings");
    }

    private Map<String, JsonNode> attributes(JsonNode attributesNode) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        if (attributesNode == null || attributesNode.isMissingNode() || attributesNode.isNull()) return result;
        if (attributesNode.isObject()) {
            attributesNode.fields().forEachRemaining(entry -> result.put(entry.getKey(), unwrap(entry.getValue())));
            return result;
        }
        if (attributesNode.isArray()) {
            for (JsonNode item : attributesNode) {
                String key = item.path("key").asText(null);
                if (key != null) result.put(key, unwrap(item.path("value")));
            }
        }
        return result;
    }

    private JsonNode unwrap(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull()) return objectMapper.nullNode();
        for (String field : List.of("stringValue", "intValue", "doubleValue", "boolValue", "bytesValue")) {
            if (value.has(field)) return value.get(field);
        }
        if (value.has("arrayValue")) {
            ArrayNode array = objectMapper.createArrayNode();
            JsonNode values = value.path("arrayValue").path("values");
            if (values.isArray()) values.forEach(item -> array.add(unwrap(item)));
            return array;
        }
        if (value.has("kvlistValue")) {
            ObjectNode object = objectMapper.createObjectNode();
            JsonNode values = value.path("kvlistValue").path("values");
            if (values.isArray()) values.forEach(item -> object.set(item.path("key").asText(), unwrap(item.path("value"))));
            return object;
        }
        return value;
    }

    private ArrayNode firstArray(JsonNode node, String... fields) {
        for (String field : fields) {
            ArrayNode array = array(node.path(field));
            if (array != null) return array;
        }
        return null;
    }

    private ArrayNode array(JsonNode node) {
        return node != null && node.isArray() ? (ArrayNode) node : null;
    }

    private JsonNode firstNode(Map<String, JsonNode> attrs, String... keys) {
        for (String key : keys) {
            JsonNode value = attrs.get(key);
            if (value != null && !value.isNull() && !value.isMissingNode()) return value;
        }
        return null;
    }

    private String firstText(Map<String, JsonNode> attrs, String... keys) {
        JsonNode value = firstNode(attrs, keys);
        if (value == null) return null;
        String text = value.isTextual() ? value.asText() : value.isValueNode() ? value.asText() : value.toString();
        return text == null || text.isBlank() ? null : normalize(text, 128);
    }

    private long firstLong(Map<String, JsonNode> attrs, String... keys) {
        JsonNode value = firstNode(attrs, keys);
        if (value == null) return 0L;
        if (value.isNumber()) return Math.max(0L, value.asLong());
        try { return Math.max(0L, Long.parseLong(value.asText("0"))); } catch (NumberFormatException ignored) { return 0L; }
    }

    private BigDecimal decimal(Map<String, JsonNode> attrs, String... keys) {
        JsonNode value = firstNode(attrs, keys);
        if (value == null) return BigDecimal.ZERO;
        try { return new BigDecimal(value.asText("0")).max(BigDecimal.ZERO); } catch (NumberFormatException ignored) { return BigDecimal.ZERO; }
    }

    private boolean hasAny(Map<String, JsonNode> attrs, String... keys) {
        return firstNode(attrs, keys) != null;
    }

    private long durationMillis(JsonNode span, Map<String, JsonNode> attrs) {
        long explicit = firstLong(attrs, "duration_ms", "gen_ai.duration_ms");
        if (explicit > 0) return explicit;
        try {
            long start = Long.parseLong(span.path("startTimeUnixNano").asText("0"));
            long end = Long.parseLong(span.path("endTimeUnixNano").asText("0"));
            return end > start ? (end - start) / 1_000_000L : 0L;
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private Instant observedAt(JsonNode span) {
        try {
            long nanos = Long.parseLong(span.path("startTimeUnixNano").asText("0"));
            if (nanos > 0) return Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
        } catch (RuntimeException ignored) {
        }
        return Instant.now();
    }

    private ModelInvocationStatus status(JsonNode span, Map<String, JsonNode> attrs) {
        String responseStatus = firstText(attrs, "gen_ai.response.status");
        String code = span.path("status").path("code").asText("");
        if ("2".equals(code) || code.toUpperCase(Locale.ROOT).contains("ERROR")
                || (responseStatus != null && containsAny(responseStatus.toLowerCase(Locale.ROOT), "failed", "error", "cancelled"))) {
            return ModelInvocationStatus.FAILED;
        }
        return ModelInvocationStatus.SUCCEEDED;
    }

    private String inferProvider(String model, String server) {
        String value = ((model == null ? "" : model) + " " + (server == null ? "" : server)).toLowerCase(Locale.ROOT);
        if (containsAny(value, "openai", "gpt-", "o1", "o3", "o4")) return "openai";
        if (containsAny(value, "anthropic", "claude")) return "anthropic";
        if (containsAny(value, "azure")) return "azure.ai.openai";
        if (containsAny(value, "bedrock", "amazon")) return "aws.bedrock";
        if (containsAny(value, "gemini", "vertex", "google")) return "gcp.gen_ai";
        if (containsAny(value, "ollama")) return "ollama";
        return "unknown";
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? fallback : normalize(value, 128);
    }

    private String normalize(String value, int maxLength) {
        if (value == null) return null;
        String normalized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private OtlpTraceIngestionDtos.IngestionResponse response(Counters counters, List<String> warnings) {
        return new OtlpTraceIngestionDtos.IngestionResponse(
                counters.spansScanned, counters.genAiSpansDetected, counters.invocationsRecorded,
                counters.contentObservationsRecorded, counters.endpointsRegistered, List.copyOf(warnings)
        );
    }

    private static final class Counters {
        int spansScanned;
        int genAiSpansDetected;
        int invocationsRecorded;
        int contentObservationsRecorded;
        int endpointsRegistered;
    }
}
