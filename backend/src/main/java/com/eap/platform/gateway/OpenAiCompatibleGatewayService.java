package com.eap.platform.gateway;

import com.eap.platform.common.BusinessException;
import com.eap.platform.common.TraceContext;
import com.eap.platform.governance.GovernanceDataDirection;
import com.eap.platform.governance.GovernanceDataDtos;
import com.eap.platform.governance.GovernanceDataSensitivity;
import com.eap.platform.governance.GovernanceGatewayDirective;
import com.eap.platform.observability.ContentDirection;
import com.eap.platform.observability.ContentObservationService;
import com.eap.platform.observability.ToolInvocationKind;
import com.eap.platform.observability.ToolInvocationService;
import com.eap.platform.observability.ToolInvocationSource;
import com.eap.platform.observability.ToolInvocationStatus;
import com.eap.platform.governance.GovernancePreflightService;
import com.eap.platform.telemetry.ModelInvocationStatus;
import com.eap.platform.telemetry.ModelTelemetryDtos;
import com.eap.platform.telemetry.ModelTelemetryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OpenAiCompatibleGatewayService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.eap.platform.budget.BudgetLedger budget;
    private final GatewayProperties properties;
    private final GovernancePreflightService preflightService;
    private final ModelTelemetryService telemetryService;
    private final ContentObservationService contentObservationService;
    private final ToolInvocationService toolInvocationService;
    private final ObjectMapper objectMapper;
    private final OpenAiUpstreamClient upstreamClient;

    public OpenAiCompatibleGatewayService(
            GatewayProperties properties,
            GovernancePreflightService preflightService,
            ModelTelemetryService telemetryService,
            ContentObservationService contentObservationService,
            ToolInvocationService toolInvocationService,
            ObjectMapper objectMapper,
            OpenAiUpstreamClient upstreamClient
    ) {
        this.properties = properties;
        this.preflightService = preflightService;
        this.telemetryService = telemetryService;
        this.contentObservationService = contentObservationService;
        this.toolInvocationService = toolInvocationService;
        this.objectMapper = objectMapper;
        this.upstreamClient = upstreamClient;
    }

    public GatewayDtos.GatewayStatus status() {
        return new GatewayDtos.GatewayStatus(
                properties.isEnabled(),
                properties.isEnabled() && !properties.getUpstreamBaseUrl().isBlank()
                        && (properties.isAllowUnauthenticated() || !properties.getIngestionToken().isBlank()),
                properties.isAllowUnauthenticated() || !properties.getIngestionToken().isBlank(),
                properties.getProvider(),
                properties.getClassifier(),
                properties.getReadTimeoutMs(),
                properties.getMaxResponseBytes(),
                properties.getInputCostPer1kTokens().signum() > 0 || properties.getOutputCostPer1kTokens().signum() > 0,
                Instant.now()
        );
    }

    public ResponseEntity<JsonNode> models() {
        if (!properties.isEnabled() || properties.getUpstreamBaseUrl().isBlank()) {
            throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "gateway_not_configured", "模型网关未启用或未配置上游地址");
        }
        ResponseEntity<JsonNode> response = upstreamClient.models();
        return ResponseEntity.status(response.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(response.getBody());
    }

    public ResponseEntity<JsonNode> chatCompletions(
            JsonNode request,
            Long agentId,
            String endpointKey,
            Long taskId,
            String traceId,
            GatewayDtos.ClassificationHints hints
    ) {
        return chatCompletions(request, agentId, endpointKey, taskId, traceId, hints, null);
    }

    public ResponseEntity<JsonNode> chatCompletions(
            JsonNode request,
            Long agentId,
            String endpointKey,
            Long taskId,
            String traceId,
            GatewayDtos.ClassificationHints hints,
            String reusableDecisionKey
    ) {
        if (!properties.isEnabled()) {
            throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "gateway_disabled", "模型网关未启用");
        }
        if (properties.getUpstreamBaseUrl().isBlank()) {
            throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "gateway_not_configured", "模型网关未配置上游地址");
        }
        if (request == null || !request.isObject()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request", "模型请求必须是 JSON 对象");
        }
        long requestBytes = request.toString().getBytes(StandardCharsets.UTF_8).length;
        if (requestBytes > properties.getMaxPayloadBytes()) {
            throw new GatewayException(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", "模型请求超过网关允许的最大载荷");
        }
        String model = text(request, "model");
        if (model.isBlank()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "missing_model", "模型请求缺少 model");
        }
        JsonNode messages = request.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "missing_messages", "模型请求缺少 messages");
        }
        if (request.path("stream").asBoolean(false)) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "stream_not_supported", "当前网关版本暂不转发 stream=true，请先使用非流式请求");
        }
        if (agentId == null && blank(endpointKey)) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "missing_attribution", "模型网关请求必须携带 X-EAP-Agent-Id 或 X-EAP-Endpoint-Key");
        }

        String normalizedTrace = blank(traceId) ? TraceContext.traceId() : traceId;
        String eventKey = "gateway-" + UUID.randomUUID();
        JsonNode forwardedRequest = request;
        Classification classification = classify(messages, hints == null ? GatewayDtos.ClassificationHints.empty() : hints);
        GovernanceDataDtos.PreflightResponse decision = inputDecision(
                reusableDecisionKey, eventKey, agentId, endpointKey, taskId, normalizedTrace, classification, model,
                requestFingerprint(request, classification)
        );
        Classification inputObservationClassification = classification;
        GovernanceGatewayDirective inputObservationDirective = decision.gatewayDirective();
        if (decision.gatewayDirective() == GovernanceGatewayDirective.REDACT_AND_RECHECK) {
            forwardedRequest = redact(request);
            classification = classify(forwardedRequest.path("messages"), GatewayDtos.ClassificationHints.empty());
            decision = preflight(
                    "gateway-decision-" + UUID.randomUUID(), eventKey + "-redacted", agentId, endpointKey, taskId, normalizedTrace,
                    GovernanceDataDirection.INPUT_TO_MODEL, classification, model, requestFingerprint(forwardedRequest, classification)
            );
        }
        JsonNode observableRequest = canExposeGovernedContent(inputObservationDirective) ? forwardedRequest : null;
        captureContent(eventKey + "-input", eventKey, normalizedTrace, agentId, endpointKey, taskId,
                model, ContentDirection.INPUT, request, observableRequest, inputObservationClassification, inputObservationDirective);
        ensureApprovalContext(decision);
        if (decision.gatewayDirective() != GovernanceGatewayDirective.PROCEED
                && decision.gatewayDirective() != GovernanceGatewayDirective.PROCEED_WITH_WARNING) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "governance_" + decision.gatewayDirective().name().toLowerCase(), "治理网关已停止模型请求：" + decision.gatewayDirective());
        }
        recordGatewayToolResults(forwardedRequest.path("messages"), eventKey, normalizedTrace, agentId, endpointKey,
                taskId, model, inputObservationDirective);

        var budgetTicket=budget==null?null:budget.admitModel(forwardedRequest,agentId,endpointKey,taskId,normalizedTrace,eventKey);
        Instant startedAt = Instant.now();
        try {
            ResponseEntity<JsonNode> response = upstreamClient.chatCompletions(forwardedRequest);
            JsonNode responseBody = response.getBody();
            if(budget!=null)budget.complete(budgetTicket,responseBody);
            JsonNode originalResponseBody = responseBody;
            GatewayDtos.CompletionUsage usage = GatewayDtos.CompletionUsage.from(responseBody);
            long promptTokens = usage.promptTokens() > 0 ? usage.promptTokens() : classification.estimatedTokens();
            long completionTokens = usage.completionTokens();
            Classification outputClassification = classify(responseBody == null ? objectMapper.nullNode() : responseBody.path("choices"), GatewayDtos.ClassificationHints.empty());
            GovernanceDataDtos.PreflightResponse outputDecision = preflight(
                    "gateway-output-decision-" + UUID.randomUUID(), eventKey + "-output", agentId, endpointKey, taskId, normalizedTrace,
                    GovernanceDataDirection.OUTPUT_FROM_MODEL, outputClassification, model
            );
            Classification outputObservationClassification = outputClassification;
            GovernanceGatewayDirective outputObservationDirective = outputDecision.gatewayDirective();
            if (outputDecision.gatewayDirective() == GovernanceGatewayDirective.REDACT_AND_RECHECK) {
                responseBody = redact(responseBody);
                outputClassification = classify(responseBody == null ? objectMapper.nullNode() : responseBody.path("choices"), GatewayDtos.ClassificationHints.empty());
                outputDecision = preflight(
                        "gateway-output-decision-" + UUID.randomUUID(), eventKey + "-output-redacted", agentId, endpointKey, taskId, normalizedTrace,
                        GovernanceDataDirection.OUTPUT_FROM_MODEL, outputClassification, model
                );
            }
            JsonNode observableResponse = canExposeGovernedContent(outputObservationDirective) ? responseBody : null;
            captureContent(eventKey + "-output", eventKey, normalizedTrace, agentId, endpointKey, taskId,
                    model, ContentDirection.OUTPUT, originalResponseBody, observableResponse, outputObservationClassification, outputObservationDirective);
            ensureApprovalContext(outputDecision);
            if (outputDecision.gatewayDirective() != GovernanceGatewayDirective.PROCEED
                    && outputDecision.gatewayDirective() != GovernanceGatewayDirective.PROCEED_WITH_WARNING) {
                throw new GatewayException(HttpStatus.FORBIDDEN, "governance_output_" + outputDecision.gatewayDirective().name().toLowerCase(), "治理网关已停止模型输出：" + outputDecision.gatewayDirective());
            }
            List<GatewayToolCall> toolCalls = parseToolCalls(responseBody);
            List<GatewayToolCall> originalToolCalls = parseToolCalls(originalResponseBody);
            recordGatewayToolCalls(originalToolCalls, toolCalls, eventKey, normalizedTrace, agentId, endpointKey,
                    taskId, model, outputObservationDirective);
            telemetryService.recordGateway(new ModelTelemetryDtos.ModelInvocationRequest(
                    eventKey, normalizedTrace, null, agentId, blankToNull(endpointKey), null, taskId, decision.decisionKey(),
                    properties.getProvider(), model, ModelInvocationStatus.SUCCEEDED, promptTokens, completionTokens,
                    cachedTokens(responseBody), reasoningTokens(responseBody), elapsedMillis(startedAt), estimateCost(promptTokens, completionTokens), "USD",
                    toolCalls.size(), 0, null, properties.getUsagePoolKey(), Instant.now()
            ));
            if(budget!=null)budget.recordTeamResponse(budgetTicket,responseBody);
            return ResponseEntity.status(response.getStatusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-EAP-Governance-Decision", decision.decisionKey())
                    .header("X-EAP-Output-Governance-Decision", outputDecision.decisionKey())
                    .header("X-EAP-Trace-Id", normalizedTrace)
                    .body(responseBody);
        } catch (RestClientResponseException exception) {
            long latency = elapsedMillis(startedAt);
            telemetryService.recordGateway(new ModelTelemetryDtos.ModelInvocationRequest(
                    eventKey, normalizedTrace, null, agentId, blankToNull(endpointKey), null, taskId, decision.decisionKey(),
                    properties.getProvider(), model, ModelInvocationStatus.FAILED, classification.estimatedTokens(), 0,
                    0, 0, latency, estimateCost(classification.estimatedTokens(), 0), "USD", 0, 0,
                    "UPSTREAM_" + exception.getStatusCode().value(), properties.getUsagePoolKey(), Instant.now()
            ));
            JsonNode body = errorBody(exception);
            return ResponseEntity.status(exception.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(body);
        } catch (GatewayException exception) {
            telemetryService.recordGateway(new ModelTelemetryDtos.ModelInvocationRequest(
                    eventKey, normalizedTrace, null, agentId, blankToNull(endpointKey), null, taskId, decision.decisionKey(),
                    properties.getProvider(), model, ModelInvocationStatus.FAILED, classification.estimatedTokens(), 0,
                    0, 0, elapsedMillis(startedAt), estimateCost(classification.estimatedTokens(), 0), "USD", 0, 0,
                    exception.getCode().toUpperCase(), properties.getUsagePoolKey(), Instant.now()
            ));
            throw exception;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientException exception) {
            telemetryService.recordGateway(new ModelTelemetryDtos.ModelInvocationRequest(
                    eventKey, normalizedTrace, null, agentId, blankToNull(endpointKey), null, taskId, decision.decisionKey(),
                    properties.getProvider(), model, ModelInvocationStatus.FAILED, classification.estimatedTokens(), 0,
                    0, 0, elapsedMillis(startedAt), estimateCost(classification.estimatedTokens(), 0), "USD", 0, 0,
                    "UPSTREAM_ERROR", properties.getUsagePoolKey(), Instant.now()
            ));
            throw new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_unavailable", "上游模型服务不可用");
        } finally {
            if(budget!=null)budget.unconfirmed(budgetTicket,"DISPATCH_RECEIPT_LOST");
        }
    }

    public ResponseEntity<String> chatCompletionsStream(
            JsonNode request,
            Long agentId,
            String endpointKey,
            Long taskId,
            String traceId,
            GatewayDtos.ClassificationHints hints
    ) {
        return chatCompletionsStream(request, agentId, endpointKey, taskId, traceId, hints, null);
    }

    public ResponseEntity<String> chatCompletionsStream(
            JsonNode request,
            Long agentId,
            String endpointKey,
            Long taskId,
            String traceId,
            GatewayDtos.ClassificationHints hints,
            String reusableDecisionKey
    ) {
        validateGatewayRequest(request, agentId, endpointKey, true);
        String model = text(request, "model");
        JsonNode messages = request.get("messages");
        String normalizedTrace = blank(traceId) ? TraceContext.traceId() : traceId;
        String eventKey = "gateway-stream-" + UUID.randomUUID();
        Classification classification = classify(messages, hints == null ? GatewayDtos.ClassificationHints.empty() : hints);
        GovernanceDataDtos.PreflightResponse decision = inputDecision(
                reusableDecisionKey, eventKey, agentId, endpointKey, taskId, normalizedTrace, classification, model,
                requestFingerprint(request, classification)
        );
        Classification inputObservationClassification = classification;
        GovernanceGatewayDirective inputObservationDirective = decision.gatewayDirective();
        JsonNode forwardedRequest = request;
        if (decision.gatewayDirective() == GovernanceGatewayDirective.REDACT_AND_RECHECK) {
            forwardedRequest = redact(request);
            classification = classify(forwardedRequest.path("messages"), GatewayDtos.ClassificationHints.empty());
            decision = preflight(
                    "gateway-stream-decision-" + UUID.randomUUID(), eventKey + "-redacted", agentId, endpointKey, taskId, normalizedTrace,
                    GovernanceDataDirection.INPUT_TO_MODEL, classification, model, requestFingerprint(forwardedRequest, classification)
            );
        }
        JsonNode observableRequest = canExposeGovernedContent(inputObservationDirective) ? forwardedRequest : null;
        captureContent(eventKey + "-input", eventKey, normalizedTrace, agentId, endpointKey, taskId,
                model, ContentDirection.INPUT, request, observableRequest, inputObservationClassification, inputObservationDirective);
        ensureProceed(decision, "模型请求");
        recordGatewayToolResults(forwardedRequest.path("messages"), eventKey, normalizedTrace, agentId, endpointKey,
                taskId, model, inputObservationDirective);

        var budgetTicket=budget==null?null:budget.admitModel(forwardedRequest,agentId,endpointKey,taskId,normalizedTrace,eventKey);
        Instant startedAt = Instant.now();
        try {
            ResponseEntity<String> response = upstreamClient.chatCompletionsStream(forwardedRequest);
            String rawStream = response.getBody() == null ? "" : response.getBody();
            if(budget!=null)budget.completeStream(budgetTicket,rawStream);
            String originalRawStream = rawStream;
            if (rawStream.getBytes(StandardCharsets.UTF_8).length > properties.getMaxResponseBytes()) {
                throw new GatewayException(HttpStatus.BAD_GATEWAY, "stream_response_too_large", "上游流式响应超过网关允许的最大大小");
            }
            StreamAggregate aggregate = aggregateStream(rawStream);
            StreamAggregate originalAggregate = aggregate;
            Classification outputClassification = classify(objectMapper.valueToTree(aggregate.text()), GatewayDtos.ClassificationHints.empty());
            GovernanceDataDtos.PreflightResponse outputDecision = preflight(
                    "gateway-stream-output-decision-" + UUID.randomUUID(), eventKey + "-output", agentId, endpointKey, taskId, normalizedTrace,
                    GovernanceDataDirection.OUTPUT_FROM_MODEL, outputClassification, model
            );
            Classification outputObservationClassification = outputClassification;
            GovernanceGatewayDirective outputObservationDirective = outputDecision.gatewayDirective();
            if (outputDecision.gatewayDirective() == GovernanceGatewayDirective.REDACT_AND_RECHECK) {
                rawStream = redactSse(rawStream);
                StreamAggregate redactedAggregate = aggregateStream(rawStream);
                outputClassification = classify(objectMapper.valueToTree(redactedAggregate.text()), GatewayDtos.ClassificationHints.empty());
                outputDecision = preflight(
                        "gateway-stream-output-decision-" + UUID.randomUUID(), eventKey + "-output-redacted", agentId, endpointKey, taskId, normalizedTrace,
                        GovernanceDataDirection.OUTPUT_FROM_MODEL, outputClassification, model
                );
                aggregate = redactedAggregate;
            }
            JsonNode observableResponse = canExposeGovernedContent(outputObservationDirective) ? objectMapper.valueToTree(rawStream) : null;
            captureContent(eventKey + "-output", eventKey, normalizedTrace, agentId, endpointKey, taskId,
                    model, ContentDirection.OUTPUT, objectMapper.valueToTree(originalRawStream), observableResponse,
                    outputObservationClassification, outputObservationDirective);
            ensureProceed(outputDecision, "模型输出");
            recordGatewayToolCalls(originalAggregate.toolCalls(), aggregate.toolCalls(), eventKey, normalizedTrace, agentId,
                    endpointKey, taskId, model, outputObservationDirective);
            long promptTokens = aggregate.usage().promptTokens() > 0 ? aggregate.usage().promptTokens() : classification.estimatedTokens();
            long completionTokens = aggregate.usage().completionTokens();
            telemetryService.recordGateway(new ModelTelemetryDtos.ModelInvocationRequest(
                    eventKey, normalizedTrace, null, agentId, blankToNull(endpointKey), null, taskId, decision.decisionKey(),
                    properties.getProvider(), model, ModelInvocationStatus.SUCCEEDED, promptTokens, completionTokens,
                    aggregate.cachedTokens(), aggregate.reasoningTokens(), elapsedMillis(startedAt), estimateCost(promptTokens, completionTokens), "USD",
                    aggregate.toolCalls().size(), 0, null, properties.getUsagePoolKey(), Instant.now()
            ));
            return ResponseEntity.status(response.getStatusCode())
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .header("X-EAP-Governance-Decision", decision.decisionKey())
                    .header("X-EAP-Output-Governance-Decision", outputDecision.decisionKey())
                    .header("X-EAP-Trace-Id", normalizedTrace)
                    .body(rawStream);
        } catch (RestClientResponseException exception) {
            telemetryFailure(eventKey, normalizedTrace, agentId, endpointKey, taskId, decision.decisionKey(), model, classification, elapsedMillis(startedAt), "UPSTREAM_" + exception.getStatusCode().value());
            return ResponseEntity.status(exception.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(exception.getResponseBodyAsString());
        } catch (GatewayException exception) {
            telemetryFailure(eventKey, normalizedTrace, agentId, endpointKey, taskId, decision.decisionKey(), model, classification, elapsedMillis(startedAt), exception.getCode().toUpperCase());
            throw exception;
        } catch (RestClientException exception) {
            telemetryFailure(eventKey, normalizedTrace, agentId, endpointKey, taskId, decision.decisionKey(), model, classification, elapsedMillis(startedAt), "UPSTREAM_ERROR");
            throw new GatewayException(HttpStatus.BAD_GATEWAY, "upstream_unavailable", "上游模型服务不可用");
        } finally {
            if(budget!=null)budget.unconfirmed(budgetTicket,"DISPATCH_RECEIPT_LOST");
        }
    }

    private void validateGatewayRequest(JsonNode request, Long agentId, String endpointKey, boolean stream) {
        if (!properties.isEnabled()) throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "gateway_disabled", "模型网关未启用");
        if (properties.getUpstreamBaseUrl().isBlank()) throw new GatewayException(HttpStatus.SERVICE_UNAVAILABLE, "gateway_not_configured", "模型网关未配置上游地址");
        if (request == null || !request.isObject()) throw new GatewayException(HttpStatus.BAD_REQUEST, "invalid_request", "模型请求必须是 JSON 对象");
        if (request.toString().getBytes(StandardCharsets.UTF_8).length > properties.getMaxPayloadBytes()) throw new GatewayException(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", "模型请求超过网关允许的最大载荷");
        if (text(request, "model").isBlank()) throw new GatewayException(HttpStatus.BAD_REQUEST, "missing_model", "模型请求缺少 model");
        JsonNode messages = request.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) throw new GatewayException(HttpStatus.BAD_REQUEST, "missing_messages", "模型请求缺少 messages");
        if (stream && !request.path("stream").asBoolean(false)) throw new GatewayException(HttpStatus.BAD_REQUEST, "stream_flag_missing", "流式入口必须携带 stream=true");
        if (agentId == null && blank(endpointKey)) throw new GatewayException(HttpStatus.BAD_REQUEST, "missing_attribution", "模型网关请求必须携带 X-EAP-Agent-Id 或 X-EAP-Endpoint-Key");
    }

    private void ensureProceed(GovernanceDataDtos.PreflightResponse decision, String subject) {
        ensureApprovalContext(decision);
        if (decision.gatewayDirective() != GovernanceGatewayDirective.PROCEED && decision.gatewayDirective() != GovernanceGatewayDirective.PROCEED_WITH_WARNING) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "governance_" + decision.gatewayDirective().name().toLowerCase(), "治理网关已停止" + subject + "：" + decision.gatewayDirective());
        }
    }

    private void ensureApprovalContext(GovernanceDataDtos.PreflightResponse decision) {
        if (decision.approvalStatus() == com.eap.platform.governance.GovernanceApprovalStatus.APPROVED
                && !"CURRENT".equals(decision.approvalContextState())) {
            throw new GatewayException(HttpStatus.FORBIDDEN, "governance_approval_context_changed", "审批依据已变更或未记录，请重新预检");
        }
    }

    private void telemetryFailure(String eventKey, String traceId, Long agentId, String endpointKey, Long taskId, String decisionKey, String model, Classification classification, long latency, String errorType) {
        telemetryService.recordGateway(new ModelTelemetryDtos.ModelInvocationRequest(
                eventKey, traceId, null, agentId, blankToNull(endpointKey), null, taskId, decisionKey,
                properties.getProvider(), model, ModelInvocationStatus.FAILED, classification.estimatedTokens(), 0,
                0, 0, latency, estimateCost(classification.estimatedTokens(), 0), "USD", 0, 0,
                errorType, properties.getUsagePoolKey(), Instant.now()
        ));
    }

    private void captureContent(
            String observationKey,
            String eventKey,
            String traceId,
            Long agentId,
            String endpointKey,
            Long taskId,
            String model,
            ContentDirection direction,
            JsonNode original,
            JsonNode safe,
            Classification classification,
            GovernanceGatewayDirective directive
    ) {
        String originalText = original == null ? "null" : original.toString();
        String safeText = safe == null ? null : safe.toString();
        contentObservationService.capture(new ContentObservationService.CaptureCommand(
                observationKey, eventKey, traceId, null, agentId, blankToNull(endpointKey), taskId,
                properties.getProvider(), model, direction, originalText, safeText,
                classification.dataCategory(), classification.sensitivity().name(), directive.name(), Instant.now()
        ));
    }

    private boolean canExposeGovernedContent(GovernanceGatewayDirective directive) {
        return directive == GovernanceGatewayDirective.PROCEED
                || directive == GovernanceGatewayDirective.PROCEED_WITH_WARNING
                || directive == GovernanceGatewayDirective.REDACT_AND_RECHECK;
    }

    private List<GatewayToolCall> parseToolCalls(JsonNode body) {
        List<GatewayToolCall> result = new ArrayList<>();
        if (body == null || !body.path("choices").isArray()) return result;
        for (JsonNode choice : body.path("choices")) {
            JsonNode calls = choice.path("message").path("tool_calls");
            if (!calls.isArray()) continue;
            for (JsonNode call : calls) {
                JsonNode function = call.path("function");
                String name = text(function, "name");
                JsonNode arguments = function.get("arguments");
                result.add(new GatewayToolCall(
                        blankToNull(text(call, "id")),
                        blankToNull(name),
                        parseArguments(arguments)
                ));
            }
        }
        return result;
    }

    private JsonNode parseArguments(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) return objectMapper.createObjectNode();
        if (!value.isTextual()) return value;
        try {
            return objectMapper.readTree(value.asText());
        } catch (Exception ignored) {
            return value;
        }
    }

    private void recordGatewayToolCalls(
            List<GatewayToolCall> originalCalls,
            List<GatewayToolCall> safeCalls,
            String eventKey,
            String traceId,
            Long agentId,
            String endpointKey,
            Long taskId,
            String model,
            GovernanceGatewayDirective directive
    ) {
        for (int index = 0; index < originalCalls.size(); index++) {
            GatewayToolCall original = originalCalls.get(index);
            GatewayToolCall safe = index < safeCalls.size() ? safeCalls.get(index) : original;
            String callKey = blank(original.callId()) ? eventKey + "-tool-" + index : "gateway-tool-" + original.callId();
            String spanId = blank(original.callId()) ? null : original.callId();
            String inputObservationKey = callKey + "-input";
            JsonNode originalArguments = parseArguments(original.arguments());
            JsonNode safeArguments = parseArguments(safe.arguments());
            Classification classification = classify(originalArguments, GatewayDtos.ClassificationHints.empty());
            String originalText = originalArguments == null ? "{}" : originalArguments.toString();
            String safeText = canExposeGovernedContent(directive)
                    ? (safeArguments == null ? redact(originalArguments).toString() : safeArguments.toString()) : null;
            contentObservationService.capture(new ContentObservationService.CaptureCommand(
                    inputObservationKey, callKey, traceId, spanId, agentId, blankToNull(endpointKey), taskId,
                    properties.getProvider(), model, ContentDirection.TOOL_INPUT, originalText, safeText,
                    classification.dataCategory(), classification.sensitivity().name(), directive.name(), Instant.now()
            ));
            toolInvocationService.record(new ToolInvocationService.RecordCommand(
                    callKey, traceId, spanId, null, agentId, blankToNull(endpointKey), null, taskId,
                    ToolInvocationSource.GATEWAY, toolKind(original.toolName()), original.toolName(), mcpServer(original.toolName()),
                    ToolInvocationStatus.REQUESTED, 0L, inputObservationKey, null,
                    toolAttributes(callKey, model), Instant.now()
            ));
        }
    }

    private void recordGatewayToolResults(
            JsonNode messages,
            String eventKey,
            String traceId,
            Long agentId,
            String endpointKey,
            Long taskId,
            String model,
            GovernanceGatewayDirective directive
    ) {
        if (messages == null || !messages.isArray()) return;
        for (JsonNode message : messages) {
            if (!"tool".equalsIgnoreCase(text(message, "role"))) continue;
            String callId = blankToNull(text(message, "tool_call_id"));
            if (callId == null) continue;
            String callKey = "gateway-tool-" + callId;
            String outputObservationKey = callKey + "-output";
            JsonNode content = message.get("content");
            if (content == null) content = objectMapper.nullNode();
            Classification classification = classify(content, GatewayDtos.ClassificationHints.empty());
            String originalText = content.toString();
            String safeText = canExposeGovernedContent(directive) ? redact(content).toString() : null;
            contentObservationService.capture(new ContentObservationService.CaptureCommand(
                    outputObservationKey, callKey, traceId, callId, agentId, blankToNull(endpointKey), taskId,
                    properties.getProvider(), model, ContentDirection.TOOL_OUTPUT, originalText, safeText,
                    classification.dataCategory(), classification.sensitivity().name(), directive.name(), Instant.now()
            ));
            toolInvocationService.record(new ToolInvocationService.RecordCommand(
                    callKey, traceId, callId, null, agentId, blankToNull(endpointKey), null, taskId,
                    ToolInvocationSource.GATEWAY, toolKind(text(message, "name")),
                    blankToNull(text(message, "name")), mcpServer(text(message, "name")),
                    ToolInvocationStatus.SUCCEEDED, null, null, outputObservationKey,
                    toolAttributes(callKey, model), Instant.now()
            ));
        }
    }

    private ToolInvocationKind toolKind(String toolName) {
        String normalized = toolName == null ? "" : toolName.toLowerCase();
        return normalized.startsWith("mcp__") || normalized.startsWith("mcp.")
                ? ToolInvocationKind.MCP : ToolInvocationKind.TOOL;
    }

    private String mcpServer(String toolName) {
        if (toolName == null) return null;
        if (toolName.startsWith("mcp__")) {
            int separator = toolName.indexOf("__", 5);
            return separator > 5 ? toolName.substring(5, separator) : null;
        }
        return null;
    }

    private String toolAttributes(String callKey, String model) {
        ObjectNode attributes = objectMapper.createObjectNode();
        attributes.put("source", "EAP_GATEWAY");
        attributes.put("model", model == null ? "unknown-model" : model);
        attributes.put("invocationKey", callKey);
        return attributes.toString();
    }

    private StreamAggregate aggregateStream(String raw) {
        StringBuilder text = new StringBuilder();
        GatewayDtos.CompletionUsage usage = new GatewayDtos.CompletionUsage(0, 0, 0);
        long cached = 0;
        long reasoning = 0;
        Map<Integer, StreamToolCallAccumulator> tools = new LinkedHashMap<>();
        for (String line : raw.split("\\R")) {
            String payload = line.startsWith("data:") ? line.substring(5).trim() : "";
            if (payload.isBlank() || "[DONE]".equals(payload)) continue;
            try {
                JsonNode node = objectMapper.readTree(payload);
                JsonNode choices = node.path("choices");
                if (choices.isArray()) {
                    for (JsonNode choice : choices) {
                        JsonNode delta = choice.path("delta");
                        if (delta.has("content")) text.append(extractText(delta.get("content")));
                        JsonNode calls = delta.path("tool_calls");
                        if (calls.isArray()) {
                            for (JsonNode call : calls) {
                                int index = call.path("index").asInt(tools.size());
                                StreamToolCallAccumulator accumulator = tools.computeIfAbsent(index, ignored -> new StreamToolCallAccumulator());
                                String id = text(call, "id");
                                if (!blank(id)) accumulator.callId = id;
                                JsonNode function = call.path("function");
                                String name = text(function, "name");
                                if (!blank(name)) accumulator.toolName = name;
                                String arguments = text(function, "arguments");
                                if (!blank(arguments)) accumulator.arguments.append(arguments);
                            }
                        }
                    }
                }
                GatewayDtos.CompletionUsage next = GatewayDtos.CompletionUsage.from(node);
                if (next.totalTokens() > 0 || next.promptTokens() > 0 || next.completionTokens() > 0) usage = next;
                cached += node.path("usage").path("prompt_tokens_details").path("cached_tokens").asLong(0);
                reasoning += node.path("usage").path("completion_tokens_details").path("reasoning_tokens").asLong(0);
            } catch (Exception ignored) {
                // Keep non-JSON SSE comments and provider-specific keepalive frames intact.
            }
        }
        List<GatewayToolCall> toolCalls = tools.values().stream().map(StreamToolCallAccumulator::toToolCall).toList();
        return new StreamAggregate(text.toString(), usage, cached, reasoning, toolCalls);
    }

    private String redactSse(String raw) {
        StringBuilder output = new StringBuilder();
        for (String line : raw.split("\\R", -1)) {
            if (!line.startsWith("data:") || "[DONE]".equals(line.substring(5).trim())) {
                output.append(line).append('\n');
                continue;
            }
            String payload = line.substring(5).trim();
            try {
                JsonNode node = objectMapper.readTree(payload);
                output.append("data: ").append(redact(node)).append("\n");
            } catch (Exception ignored) {
                output.append(line).append('\n');
            }
        }
        return output.toString();
    }

    private GovernanceDataDtos.PreflightResponse preflight(
            String decisionKey, String eventKey, Long agentId, String endpointKey, Long taskId, String traceId,
            GovernanceDataDirection direction, Classification classification, String model
    ) {
        return preflight(decisionKey, eventKey, agentId, endpointKey, taskId, traceId, direction, classification, model, null);
    }

    private GovernanceDataDtos.PreflightResponse preflight(
            String decisionKey, String eventKey, Long agentId, String endpointKey, Long taskId, String traceId,
            GovernanceDataDirection direction, Classification classification, String model, String requestFingerprint
    ) {
        return preflightService.evaluate(new GovernanceDataDtos.PreflightRequest(
                decisionKey, eventKey, agentId, blankToNull(endpointKey), taskId, traceId,
                direction, classification.dataCategory(), classification.sensitivity(),
                classification.internalData(), classification.companySecret(), classification.estimatedTokens(), classification.payloadBytes(),
                properties.getClassifier(), properties.getProvider(), model, classification.detectedTypes(), requestFingerprint, Instant.now()
        ));
    }

    private GovernanceDataDtos.PreflightResponse inputDecision(
            String reusableDecisionKey, String eventKey, Long agentId, String endpointKey, Long taskId, String traceId,
            Classification classification, String model, String requestFingerprint
    ) {
        if (!blank(reusableDecisionKey)) {
            return preflightService.reusableDecision(reusableDecisionKey, agentId, blankToNull(endpointKey), properties.getProvider(), model, requestFingerprint);
        }
        return preflight(
                "gateway-decision-" + UUID.randomUUID(), eventKey, agentId, endpointKey, taskId, traceId,
                GovernanceDataDirection.INPUT_TO_MODEL, classification, model, requestFingerprint
        );
    }

    private String requestFingerprint(JsonNode request, Classification classification) {
        try {
            byte[] requestBytes = objectMapper.writeValueAsBytes(request);
            String labels = "|" + classification.dataCategory() + "|" + classification.sensitivity().name()
                    + "|" + classification.internalData() + "|" + classification.companySecret()
                    + "|" + String.join(",", classification.detectedTypes());
            java.security.MessageDigest digestor = java.security.MessageDigest.getInstance("SHA-256");
            digestor.update(requestBytes);
            byte[] digest = digestor.digest(labels.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("模型请求指纹计算失败", exception);
        }
    }

    public GovernanceDataDtos.PreflightResponse decision(String decisionKey) {
        return preflightService.get(decisionKey);
    }

    private JsonNode errorBody(RestClientResponseException exception) {
        try {
            return objectMapper.readTree(exception.getResponseBodyAsString());
        } catch (Exception ignored) {
            return objectMapper.createObjectNode()
                    .put("error", "上游模型服务返回错误")
                    .put("code", "UPSTREAM_" + exception.getStatusCode().value());
        }
    }

    public Classification classify(JsonNode contentNode, GatewayDtos.ClassificationHints hints) {
        String content = extractText(contentNode);
        String normalized = content.toLowerCase();
        String inspectable = normalized
                .replace("[redacted_email]", "")
                .replace("[redacted_phone]", "")
                .replace("[redacted]", "");
        List<String> detected = new ArrayList<>();
        if (containsAny(inspectable, "api_key", "apikey", "access_token", "bearer ", "secret", "password", "private_key")) detected.add("CREDENTIAL");
        boolean idCard = containsAny(inspectable, "身份证", "id_card", "social_security");
        boolean phone = inspectable.matches("(?s).*1[3-9]\\d{9}.*");
        boolean email = inspectable.matches("(?s).*[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}.*");
        if (idCard) detected.add("ID_CARD");
        if (phone) detected.add("PHONE");
        if (email) detected.add("EMAIL");
        if (idCard || phone || email) detected.add("PII");
        if (containsAny(inspectable, "source code", "source_code", "源码", "repository", "git diff")) detected.add("SOURCE_CODE");
        boolean heuristicSecret = containsAny(inspectable, "company_secret", "confidential", "机密", "秘密", "restricted");
        boolean heuristicInternal = heuristicSecret || containsAny(inspectable, "internal", "内部", "employee", "员工", "intranet");
        if (hints.detectedTypes() != null) hints.detectedTypes().stream().filter(value -> value != null && !value.isBlank()).map(String::trim).forEach(detected::add);
        boolean companySecret = hints.companySecret() != null ? hints.companySecret() : heuristicSecret;
        boolean internal = hints.internalData() != null ? hints.internalData() : heuristicInternal || companySecret;
        GovernanceDataSensitivity sensitivity = hints.sensitivity() != null ? hints.sensitivity() : companySecret ? GovernanceDataSensitivity.SECRET
                : internal || !detected.isEmpty() ? GovernanceDataSensitivity.INTERNAL : GovernanceDataSensitivity.PUBLIC;
        String category = !blank(hints.dataCategory()) ? hints.dataCategory() : companySecret ? "COMPANY_SECRET" : !detected.isEmpty() ? "SENSITIVE_TEXT" : "GENERAL_TEXT";
        long bytes = content.getBytes(StandardCharsets.UTF_8).length;
        long estimated = Math.max(1, (bytes + 3) / 4);
        return new Classification(category, sensitivity, internal, companySecret, estimated, bytes, detected);
    }

    private String extractText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "";
        if (node.isTextual()) return node.asText();
        StringBuilder builder = new StringBuilder();
        if (node.isContainerNode()) node.forEach(child -> builder.append(extractText(child)).append(' '));
        return builder.toString();
    }

    private JsonNode redact(JsonNode source) {
        if (source == null) return null;
        JsonNode copy = source.deepCopy();
        redactNode(copy);
        return copy;
    }

    private void redactNode(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                if (value.isTextual()) ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(entry.getKey(), redactText(value.asText()));
                else redactNode(value);
            });
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                JsonNode value = node.get(index);
                if (value.isTextual()) ((com.fasterxml.jackson.databind.node.ArrayNode) node).set(index, objectMapper.getNodeFactory().textNode(redactText(value.asText())));
                else redactNode(value);
            }
        }
    }

    private String redactText(String value) {
        return value
                .replaceAll("(?i)(api[_-]?key|access[_-]?token|token|secret|password)\\s*[:=]\\s*[^\\s,;]+", "[REDACTED]")
                .replaceAll("(?i)bearer\\s+[a-z0-9._-]+", "[REDACTED]")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[REDACTED_EMAIL]")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[REDACTED_PHONE]");
    }

    private long cachedTokens(JsonNode body) {
        return body == null ? 0 : body.path("usage").path("prompt_tokens_details").path("cached_tokens").asLong(0);
    }

    private long reasoningTokens(JsonNode body) {
        return body == null ? 0 : body.path("usage").path("completion_tokens_details").path("reasoning_tokens").asLong(0);
    }

    private int toolCallCount(JsonNode body) {
        if (body == null || !body.path("choices").isArray()) return 0;
        int count = 0;
        for (JsonNode choice : body.path("choices")) {
            JsonNode calls = choice.path("message").path("tool_calls");
            if (calls.isArray()) count += calls.size();
        }
        return count;
    }

    private BigDecimal estimateCost(long inputTokens, long outputTokens) {
        return properties.getInputCostPer1kTokens().multiply(BigDecimal.valueOf(inputTokens))
                .add(properties.getOutputCostPer1kTokens().multiply(BigDecimal.valueOf(outputTokens)))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }

    private long elapsedMillis(Instant startedAt) { return Math.max(0, java.time.Duration.between(startedAt, Instant.now()).toMillis()); }
    private boolean containsAny(String value, String... needles) { for (String needle : needles) if (value.contains(needle)) return true; return false; }
    private String text(JsonNode node, String field) { JsonNode value = node.get(field); return value == null || !value.isValueNode() ? "" : value.asText(""); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String blankToNull(String value) { return blank(value) ? null : value; }

    public record Classification(String dataCategory, GovernanceDataSensitivity sensitivity, boolean internalData, boolean companySecret, long estimatedTokens, long payloadBytes, List<String> detectedTypes) {}

    private record GatewayToolCall(String callId, String toolName, JsonNode arguments) {}

    private static final class StreamToolCallAccumulator {
        private String callId;
        private String toolName;
        private final StringBuilder arguments = new StringBuilder();

        private GatewayToolCall toToolCall() {
            String rawArguments = arguments.toString();
            return new GatewayToolCall(callId, toolName, rawArguments.isBlank() ? null : JsonNodeFactory.instance.textNode(rawArguments));
        }
    }

    private record StreamAggregate(String text, GatewayDtos.CompletionUsage usage, long cachedTokens, long reasoningTokens, List<GatewayToolCall> toolCalls) {}
}
