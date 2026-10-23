package com.eap.platform.observability;

import com.eap.platform.common.BusinessException;
import com.eap.platform.discovery.AgentDiscoveryDtos;
import com.eap.platform.discovery.AgentDiscoveryService;
import com.eap.platform.knowledge.KnowledgeDtos;
import com.eap.platform.knowledge.KnowledgeInteractionStatus;
import com.eap.platform.knowledge.KnowledgeService;
import com.eap.platform.knowledge.KnowledgeStepStatus;
import com.eap.platform.knowledge.KnowledgeStepType;
import com.eap.platform.telemetry.ModelInvocationStatus;
import com.eap.platform.telemetry.ModelObservationSource;
import com.eap.platform.telemetry.ModelTelemetryDtos;
import com.eap.platform.telemetry.ModelTelemetryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final TraceSpanObservationRepository traceSpanRepository;
    private final KnowledgeService knowledgeService;
    private final ToolInvocationService toolInvocationService;

    public OtlpTraceIngestionService(
            ObjectMapper objectMapper,
            AgentDiscoveryService discoveryService,
            ModelTelemetryService telemetryService,
            ContentObservationService contentObservationService,
            SensitiveContentSanitizer sanitizer,
            TraceSpanObservationRepository traceSpanRepository,
            KnowledgeService knowledgeService,
            ToolInvocationService toolInvocationService
    ) {
        this.objectMapper = objectMapper;
        this.discoveryService = discoveryService;
        this.telemetryService = telemetryService;
        this.contentObservationService = contentObservationService;
        this.sanitizer = sanitizer;
        this.traceSpanRepository = traceSpanRepository;
        this.knowledgeService = knowledgeService;
        this.toolInvocationService = toolInvocationService;
    }

    public OtlpTraceIngestionDtos.IngestionResponse ingest(JsonNode payload) {
        return ingestInternal(payload, null, null);
    }

    /** Called only after scoped runtime authentication and identity validation. */
    public OtlpTraceIngestionDtos.IngestionResponse ingestScoped(JsonNode payload, Long agentId, String endpointKey) {
        if (agentId == null || endpointKey == null) throw new BusinessException("实例观测缺少认证归属");
        return ingestInternal(payload, agentId, endpointKey);
    }

    private OtlpTraceIngestionDtos.IngestionResponse ingestInternal(JsonNode payload, Long scopedAgent, String scopedEndpoint) {
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
            String endpointKey = scopedEndpoint == null ? endpointKey(resource) : scopedEndpoint;
            registerEndpoint(endpointKey, resource);
            counters.endpointsRegistered++;
            ArrayNode scopes = firstArray(resourceSpan, "scopeSpans", "instrumentationLibrarySpans");
            if (scopes == null) continue;
            for (JsonNode scope : scopes) {
                ArrayNode spans = array(scope.path("spans"));
                if (spans == null) continue;
                java.util.Set<String> modelTraces = new java.util.HashSet<>();
                for (JsonNode candidate : spans) {
                    Map<String, JsonNode> candidateAttrs = attributes(candidate.path("attributes"));
                    if (classify(candidate, candidateAttrs) == TraceSpanKind.MODEL) {
                        modelTraces.add(text(candidate, "traceId", hash(candidate.toString()).substring(0, 32)));
                    }
                }
                for (JsonNode span : spans) {
                    if (++counters.spansScanned > MAX_SPANS) {
                        warnings.add("超过单次最大 Span 数量 " + MAX_SPANS + "，其余数据已忽略");
                        return response(counters, warnings);
                    }
                    Map<String, JsonNode> attrs = attributes(span.path("attributes"));
                    TraceSpanKind spanKind = classify(span, attrs);
                    Long agentId = scopedAgent == null ? resolveAgentId(resource, attrs) : scopedAgent;
                    if (spanKind != null) {
                        String spanTraceId = text(span, "traceId", hash(span.toString()).substring(0, 32));
                        recordTraceSpan(span, attrs, endpointKey, agentId, spanKind, modelTraces.contains(spanTraceId), counters);
                    }
                    if (spanKind != TraceSpanKind.MODEL) continue;
                    counters.genAiSpansDetected++;
                    try {
                        ingestSpan(span, attrs, endpointKey, agentId, counters);
                    } catch (RuntimeException exception) {
                        if (warnings.size() < 20) warnings.add("Span " + text(span, "spanId", "unknown") + "：" + exception.getMessage());
                    }
                }
            }
        }
        return response(counters, warnings);
    }

    public List<OtlpTraceIngestionDtos.TraceSpanResponse> listTraceSpans(int limit) {
        return listTraceSpans(limit, null, null, null, null, null, null);
    }

    public List<OtlpTraceIngestionDtos.TraceSpanResponse> listTraceSpans(
            int limit,
            Long agentId,
            String endpointKey,
            String traceId,
            TraceSpanKind spanKind,
            Instant from,
            Instant to
    ) {
        return listTraceSpans(limit, agentId, endpointKey, traceId, spanKind, from, to, 0);
    }

    @Transactional(readOnly = true)
    public com.eap.platform.common.HistoryPage<OtlpTraceIngestionDtos.TraceSpanResponse> page(com.eap.platform.common.ObservationHistoryQuery query) {
        if(query.getEventKey()!=null && !query.getEventKey().isBlank()) throw new com.eap.platform.common.BusinessException("此类记录不支持eventKey筛选，请使用Trace ID");
        var pageable=query.pageable();
        var selected=query.enumStatus(TraceStatus.class);
        var spec=query.<TraceSpanObservation>specification("status", selected==null?null:selected.name(), "traceId", "spanId", "spanName", "toolName", "mcpServerName").and(query.observationContext());
        return com.eap.platform.common.HistoryPage.from(traceSpanRepository.findAll(spec,pageable).map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public List<OtlpTraceIngestionDtos.TraceSpanResponse> listTraceSpans(
            int limit,
            Long agentId,
            String endpointKey,
            String traceId,
            TraceSpanKind spanKind,
            Instant from,
            Instant to,
            int page
    ) {
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        List<TraceSpanObservation> spans;
        Specification<TraceSpanObservation> specification = Specification.where(null);
        if (agentId != null) specification = specification.and((root, query, cb) -> cb.equal(root.get("agentId"), agentId));
        if (!blank(endpointKey)) specification = specification.and((root, query, cb) -> cb.equal(root.get("endpointKey"), endpointKey.trim()));
        if (!blank(traceId)) specification = specification.and((root, query, cb) -> cb.equal(root.get("traceId"), traceId.trim()));
        if (spanKind != null) specification = specification.and((root, query, cb) -> cb.equal(root.get("spanKind"), spanKind));
        if (from != null) specification = specification.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("observedAt"), from));
        if (to != null) specification = specification.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("observedAt"), to));
        spans = traceSpanRepository.findAll(specification,
                    PageRequest.of(page, boundedLimit, Sort.by(Sort.Direction.DESC, "observedAt", "id"))).getContent();
        return spans.stream()
                .map(this::toResponse).toList();
    }

    private void recordTraceSpan(
            JsonNode span,
            Map<String, JsonNode> attrs,
            String endpointKey,
            Long agentId,
            TraceSpanKind spanKind,
            boolean modelInTrace,
            Counters counters
    ) {
        String traceId = text(span, "traceId", hash(span.toString()).substring(0, 32));
        String spanId = text(span, "spanId", hash(span.toString()).substring(0, 16));
        TraceSpanObservation observation = traceSpanRepository.findByTraceIdAndSpanId(traceId, spanId)
                .orElseGet(TraceSpanObservation::new);
        observation.traceId = traceId;
        observation.spanId = spanId;
        observation.parentSpanId = text(span, "parentSpanId", null);
        observation.endpointKey = endpointKey;
        observation.agentId = agentId;
        observation.spanName = normalize(text(span, "name", spanKind.name()), 256);
        observation.spanKind = spanKind;
        observation.operation = firstText(attrs, "gen_ai.operation.name", "tool.operation", "rpc.method", "db.operation", "http.request.method");
        observation.toolName = firstText(attrs, "gen_ai.tool.name", "gen_ai.tool.call.name", "tool.name", "function.name");
        observation.mcpServerName = firstText(attrs, "mcp.server.name", "mcp.server", "server.address");
        observation.ragSource = firstText(attrs, "rag.source", "retrieval.source", "db.namespace", "vector_db.namespace");
        observation.status = McpProtocolObservation.applies(spanKind, attrs)
                ? McpProtocolObservation.status(span, attrs) : status(span, attrs).name();
        observation.durationMs = durationMillis(span, attrs);
        observation.attributesJson = traceAttributesJson(attrs);
        observation.observedAt = observedAt(span);
        observation.startedAt = producerTime(span, "startTimeUnixNano");
        observation.endedAt = producerTime(span, "endTimeUnixNano");
        traceSpanRepository.save(observation);
        counters.traceSpansRecorded++;
        switch (spanKind) {
            case TOOL -> counters.toolSpansRecorded++;
            case MCP -> counters.mcpSpansRecorded++;
            case RAG -> counters.ragSpansRecorded++;
            case A2A -> counters.a2aSpansRecorded++;
            default -> { }
        }
        ToolContentRefs contentRefs = spanKind == TraceSpanKind.MODEL
                ? ToolContentRefs.empty()
                : captureTracePayloads(traceId, spanId, endpointKey, agentId, spanKind, attrs, observation.observedAt, counters);
        if (spanKind == TraceSpanKind.TOOL || spanKind == TraceSpanKind.MCP) {
            toolInvocationService.record(new ToolInvocationService.RecordCommand(
                    normalize("otlp-tool-" + traceId + "-" + spanId, 128), traceId, spanId, observation.parentSpanId,
                    agentId, endpointKey, null, null, ToolInvocationSource.OTLP,
                    spanKind == TraceSpanKind.MCP ? ToolInvocationKind.MCP : ToolInvocationKind.TOOL,
                    observation.toolName == null ? observation.spanName : observation.toolName,
                    observation.mcpServerName, toolStatus(observation.status), observation.durationMs,
                    contentRefs.inputObservationKey(), contentRefs.outputObservationKey(),
                    observation.attributesJson, observation.observedAt
            ));
        }
        if (spanKind == TraceSpanKind.RAG) inferKnowledgeInteraction(
                traceId, spanId, endpointKey, agentId, attrs, modelInTrace, observation.observedAt, observation.durationMs, counters);
    }

    private void inferKnowledgeInteraction(
            String traceId,
            String spanId,
            String endpointKey,
            Long agentId,
            Map<String, JsonNode> attrs,
            boolean modelInTrace,
            Instant observedAt,
            long durationMs,
            Counters counters
    ) {
        JsonNode documents = firstNode(attrs, "rag.documents", "retrieval.documents", "rag.source.documents");
        JsonNode query = firstNode(attrs, "rag.query", "retrieval.query");
        List<String> refs = refs(documents);
        int sourceCount = refs.isEmpty() ? (documents == null ? 0 : 1) : refs.size();
        Boolean adopted = nullableBoolean(attrs, "rag.adopted", "knowledge.adopted");
        Boolean reused = nullableBoolean(attrs, "rag.reused", "knowledge.reused");
        if (adopted == null) adopted = modelInTrace && sourceCount > 0;
        if (reused == null) reused = duplicateRefs(refs);
        knowledgeService.record(new KnowledgeDtos.KnowledgeInteractionRequest(
                normalize("otlp-knowledge-" + traceId + "-" + spanId, 128),
                traceId, agentId, endpointKey, null,
                "RAG_QUERY", sourceCount, estimateTokens(query), null, refs.stream().map(TraceMetadata::reference).toList(),
                "RAG_CONTEXT", sourceCount, estimateTokens(documents), null, refs.stream().map(TraceMetadata::reference).toList(),
                List.of(new KnowledgeDtos.KnowledgeStepRequest(
                        0, KnowledgeStepType.RETRIEVAL, "OTLP RAG span", KnowledgeStepStatus.SUCCEEDED,
                        sourceCount, estimateTokens(query), estimateTokens(documents), durationMs, refs.stream().map(TraceMetadata::reference).toList(),
                        Map.of("inferenceSource", "OTLP_TRACE", "modelInSameTrace", modelInTrace)
                )),
                KnowledgeInteractionStatus.SUCCEEDED, adopted, reused, null, observedAt, observedAt
        ));
        counters.knowledgeInteractionsRecorded++;
    }

    private List<String> refs(JsonNode value) {
        if (value == null || value.isNull()) return List.of();
        if (value.isArray()) {
            List<String> result = new ArrayList<>();
            for (JsonNode item : value) {
                JsonNode ref = item;
                if (item.isObject()) {
                    ref = firstNode(item, "id", "ref", "documentId", "source", "uri", "key");
                }
                String itemText = textValue(ref == null ? item : ref);
                if (itemText != null && !itemText.isBlank()) result.add(normalize(itemText, 256));
                if (result.size() >= 100) break;
            }
            return result;
        }
        String text = textValue(value);
        if (text == null) return List.of();
        return java.util.Arrays.stream(text.split("[,\\s]+"))
                .filter(item -> !item.isBlank()).map(item -> normalize(item, 256)).limit(100).toList();
    }

    private JsonNode firstNode(JsonNode object, String... fields) {
        if (object == null || !object.isObject()) return null;
        for (String field : fields) {
            JsonNode value = object.get(field);
            if (value != null && !value.isNull() && !value.isMissingNode()) return value;
        }
        return null;
    }

    private boolean duplicateRefs(List<String> refs) { return refs.size() != new java.util.LinkedHashSet<>(refs).size(); }

    private long estimateTokens(JsonNode value) {
        String text = textValue(value);
        return text == null ? 0L : Math.max(0L, (text.length() + 3L) / 4L);
    }

    private String textValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        return value.isTextual() ? normalize(value.asText(), 2048) : normalize(value.toString(), 2048);
    }

    private Boolean nullableBoolean(Map<String, JsonNode> attrs, String... keys) {
        JsonNode value = firstNode(attrs, keys);
        return value == null ? null : value.asBoolean(false);
    }

    private ToolContentRefs captureTracePayloads(
            String traceId,
            String spanId,
            String endpointKey,
            Long agentId,
            TraceSpanKind kind,
            Map<String, JsonNode> attrs,
            Instant observedAt,
            Counters counters
    ) {
        JsonNode input = firstNode(attrs,
                "tool.input", "tool.arguments", "gen_ai.tool.call.arguments", "mcp.request", "mcp.request.arguments",
                "rag.query", "retrieval.query", "a2a.message.input", "agent.input");
        JsonNode output = firstNode(attrs,
                "tool.output", "tool.result", "gen_ai.tool.call.result", "mcp.response", "mcp.response.result",
                "rag.documents", "retrieval.documents", "a2a.message.output", "agent.output");
        String eventKey = normalize("otlp-span-" + traceId + "-" + spanId, 128);
        String inputObservationKey = input == null ? null : eventKey + "-input";
        String outputObservationKey = output == null ? null : eventKey + "-output";
        if (input != null) {
            capture(inputObservationKey, eventKey, traceId, spanId, agentId, endpointKey,
                    "trace", kind.name(), ContentDirection.INPUT, input, observedAt);
            counters.contentObservationsRecorded++;
        }
        if (output != null) {
            capture(outputObservationKey, eventKey, traceId, spanId, agentId, endpointKey,
                    "trace", kind.name(), ContentDirection.OUTPUT, output, observedAt);
            counters.contentObservationsRecorded++;
        }
        return new ToolContentRefs(inputObservationKey, outputObservationKey);
    }

    private ToolInvocationStatus toolStatus(String status) {
        if ("SUCCEEDED".equalsIgnoreCase(status)) return ToolInvocationStatus.SUCCEEDED;
        if ("FAILED".equalsIgnoreCase(status)) return ToolInvocationStatus.FAILED;
        return ToolInvocationStatus.UNKNOWN;
    }

    private TraceSpanKind classify(JsonNode span, Map<String, JsonNode> attrs) {
        String name = text(span, "name", "").toLowerCase(Locale.ROOT);
        String keys = String.join(" ", attrs.keySet()).toLowerCase(Locale.ROOT);
        if (hasAny(attrs, "gen_ai.request.model", "gen_ai.response.model", "gen_ai.provider.name",
                "gen_ai.usage.input_tokens", "gen_ai.usage.output_tokens", "llm.request.model", "llm.response.model", "ai.model.id")
                || containsAny(name, "chat", "completion", "generate_content", "embeddings")) return TraceSpanKind.MODEL;
        if (keys.contains("mcp.") || name.contains("mcp")) return TraceSpanKind.MCP;
        if (keys.contains("rag.") || keys.contains("retrieval.") || keys.contains("vector_db.")
                || name.contains("retriev") || name.contains("vector search") || name.contains("rag")) return TraceSpanKind.RAG;
        if (keys.contains("a2a.") || keys.contains("agent.peer") || name.contains("a2a")
                || name.contains("handoff") || name.contains("delegate")) return TraceSpanKind.A2A;
        if (keys.contains("tool.") || keys.contains("gen_ai.tool") || name.contains("tool") || name.contains("function")) {
            return TraceSpanKind.TOOL;
        }
        if (isGenAiSpan(span, attrs)) return TraceSpanKind.MODEL;
        if (keys.contains("agent.") || keys.contains("gen_ai.agent") || name.contains("agent")) return TraceSpanKind.AGENT;
        return null;
    }

    private String traceAttributesJson(Map<String, JsonNode> attrs) {
        return TraceMetadata.from(attrs);
    }

    private OtlpTraceIngestionDtos.TraceSpanResponse toResponse(TraceSpanObservation item) {
        return new OtlpTraceIngestionDtos.TraceSpanResponse(
                item.id, item.traceId, item.spanId, item.parentSpanId, item.endpointKey, item.agentId, item.spanName,
                item.spanKind, item.operation, item.toolName, item.mcpServerName, item.ragSource,
                item.status, item.durationMs == null ? 0L : item.durationMs, TraceMetadata.project(item.attributesJson), item.observedAt, item.startedAt, item.endedAt
        );
    }

    private void ingestSpan(JsonNode span, Map<String, JsonNode> attrs, String endpointKey, Long agentId, Counters counters) {
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
        String usagePoolKey = firstText(attrs, "eap.usage.pool_key", "gen_ai.usage.pool_key", "llm.usage.pool_key");

        telemetryService.recordPassive(new ModelTelemetryDtos.ModelInvocationRequest(
                eventKey, traceId, spanId, agentId, endpointKey, null, null, null,
                provider, model, status, inputTokens, outputTokens, cachedTokens, reasoningTokens,
                latencyMs, cost, currency == null ? "USD" : currency, toolCalls, mcpServers,
                errorType, usagePoolKey, observedAt
        ), ModelObservationSource.OTLP);
        counters.invocationsRecorded++;

        JsonNode input = firstNode(attrs, "gen_ai.input.messages", "gen_ai.prompt", "llm.prompts", "llm.input_messages", "traceloop.entity.input");
        JsonNode output = firstNode(attrs, "gen_ai.output.messages", "gen_ai.completion", "llm.completions", "llm.output_messages", "traceloop.entity.output");
        if (input != null) {
            capture(eventKey + "-input", eventKey, traceId, spanId, agentId, endpointKey, provider, model, ContentDirection.INPUT, input, observedAt);
            counters.contentObservationsRecorded++;
        }
        if (output != null) {
            capture(eventKey + "-output", eventKey, traceId, spanId, agentId, endpointKey, provider, model, ContentDirection.OUTPUT, output, observedAt);
            counters.contentObservationsRecorded++;
        }
    }

    private void capture(
            String observationKey, String eventKey, String traceId, String spanId, Long agentId, String endpointKey,
            String provider, String model, ContentDirection direction, JsonNode content, Instant observedAt
    ) {
        String original = content.isTextual() ? content.asText() : content.toString();
        SensitiveContentSanitizer.SanitizedContent safe = sanitizer.sanitize(original);
        contentObservationService.capture(new ContentObservationService.CaptureCommand(
                normalize(observationKey, 128), eventKey, traceId, spanId, agentId, endpointKey, null,
                provider, model, direction, original, safe.safeContent(), safe.dataCategory(),
                safe.sensitivity(), safe.governanceAction(), observedAt
        ));
    }

    private Long resolveAgentId(Map<String, JsonNode> resource, Map<String, JsonNode> attrs) {
        Long explicit = firstLongNullable(attrs, "eap.agent.id", "agent.id", "gen_ai.agent.id", "ai.eap.agent.id");
        return explicit == null
                ? firstLongNullable(resource, "eap.agent.id", "agent.id", "gen_ai.agent.id", "ai.eap.agent.id")
                : explicit;
    }

    private Long firstLongNullable(Map<String, JsonNode> values, String... keys) {
        for (String key : keys) {
            JsonNode value = values.get(key);
            if (value == null || value.isNull()) continue;
            try {
                long parsed = value.isNumber() ? value.longValue() : Long.parseLong(value.asText().trim());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                // Keep endpoint-level attribution when an identity attribute is not numeric.
            }
        }
        return null;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
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

    private Instant producerTime(JsonNode span, String field) {
        try {
            long nanos = Long.parseLong(span.path(field).asText("0"));
            return nanos > 0 ? Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Instant observedAt(JsonNode span) {
        Instant start = producerTime(span, "startTimeUnixNano");
        return start == null ? Instant.now() : start;
    }

    private enum TraceStatus { STARTED, SUCCEEDED, FAILED, CANCELLED, UNKNOWN }

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
                counters.contentObservationsRecorded, counters.endpointsRegistered, counters.traceSpansRecorded,
                counters.toolSpansRecorded, counters.mcpSpansRecorded, counters.ragSpansRecorded,
                counters.a2aSpansRecorded, counters.knowledgeInteractionsRecorded, List.copyOf(warnings)
        );
    }

    private static final class Counters {
        int spansScanned;
        int genAiSpansDetected;
        int invocationsRecorded;
        int contentObservationsRecorded;
        int endpointsRegistered;
        int traceSpansRecorded;
        int toolSpansRecorded;
        int mcpSpansRecorded;
        int ragSpansRecorded;
        int a2aSpansRecorded;
        int knowledgeInteractionsRecorded;
    }

    private record ToolContentRefs(String inputObservationKey, String outputObservationKey) {
        static ToolContentRefs empty() {
            return new ToolContentRefs(null, null);
        }
    }
}
