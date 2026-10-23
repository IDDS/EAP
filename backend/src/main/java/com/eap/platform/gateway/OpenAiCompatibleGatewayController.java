package com.eap.platform.gateway;

import com.eap.platform.common.ApiResponse;
import com.eap.platform.common.BusinessException;
import com.eap.platform.common.TraceContext;
import com.eap.platform.discovery.AgentDiscoveryDtos;
import com.eap.platform.discovery.AgentDiscoveryService;
import com.eap.platform.governance.GovernanceDataSensitivity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/gateway")
public class OpenAiCompatibleGatewayController {
    @org.springframework.beans.factory.annotation.Autowired
    private com.eap.platform.registry.RuntimeConnectionService runtimeConnections;
    private final OpenAiCompatibleGatewayService service;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final AgentDiscoveryService discoveryService;

    public OpenAiCompatibleGatewayController(
            OpenAiCompatibleGatewayService service,
            GatewayProperties properties,
            ObjectMapper objectMapper,
            AgentDiscoveryService discoveryService
    ) {
        this.service = service;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.discoveryService = discoveryService;
    }

    @GetMapping("/status")
    public ApiResponse<GatewayDtos.GatewayStatus> status() {
        return ApiResponse.ok(service.status());
    }

    @PostMapping(value = "/v1/chat/completions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public ResponseEntity<?> chatCompletions(
            @RequestHeader(value = "X-EAP-Gateway-Token", required = false) @Size(max = 256) String gatewayToken,
            @RequestHeader(value = "Authorization", required = false) @Size(max = 512) String authorization,
            @RequestHeader(value = "X-EAP-Agent-Id", required = false) Long agentId,
            @RequestHeader(value = "X-EAP-Endpoint-Key", required = false) @Size(max = 128) String endpointKey,
            @RequestHeader(value = "X-EAP-Task-Id", required = false) Long taskId,
            @RequestHeader(value = "X-EAP-Trace-Id", required = false) @Size(max = 128) String traceId,
            @RequestHeader(value = "X-EAP-Governance-Decision-Key", required = false) @Size(max = 128) String reusableDecisionKey,
            @RequestHeader(value = "X-EAP-Data-Category", required = false) @Size(max = 128) String dataCategory,
            @RequestHeader(value = "X-EAP-Sensitivity", required = false) GovernanceDataSensitivity sensitivity,
            @RequestHeader(value = "X-EAP-Internal-Data", required = false) Boolean internalData,
            @RequestHeader(value = "X-EAP-Company-Secret", required = false) Boolean companySecret,
            @RequestHeader(value = "X-EAP-Detected-Types", required = false) @Size(max = 1024) String detectedTypes,
            HttpServletRequest servletRequest,
            @RequestBody JsonNode request
    ) {
        requireGatewayToken(gatewayToken, authorization);
        runtimeConnections.validateLegacyTaskAttribution(servletRequest.getHeader("X-EAP-Task-Key"));
        String resolvedEndpointKey = resolveEndpointKey(agentId, endpointKey, servletRequest);
        registerAutoAttributedEndpoint(resolvedEndpointKey, servletRequest);
        GatewayDtos.ClassificationHints hints = new GatewayDtos.ClassificationHints(
                dataCategory, sensitivity, internalData, companySecret, split(detectedTypes)
        );
        if (request.path("stream").asBoolean(false)) {
            return service.chatCompletionsStream(request, agentId, resolvedEndpointKey, taskId, traceId, hints, reusableDecisionKey);
        }
        return service.chatCompletions(request, agentId, resolvedEndpointKey, taskId, traceId, hints, reusableDecisionKey);
    }

    @GetMapping("/v1/models")
    public ResponseEntity<JsonNode> models(
            @RequestHeader(value = "X-EAP-Gateway-Token", required = false) String gatewayToken,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        requireGatewayToken(gatewayToken, authorization);
        return service.models();
    }

    @GetMapping("/v1/decisions/{decisionKey}")
    public ApiResponse<com.eap.platform.governance.GovernanceDataDtos.PreflightResponse> decision(
            @PathVariable String decisionKey,
            @RequestHeader(value = "X-EAP-Gateway-Token", required = false) String gatewayToken,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        requireGatewayToken(gatewayToken, authorization);
        return ApiResponse.ok(service.decision(decisionKey));
    }

    private void requireGatewayToken(String token, String authorization) {
        String configured = properties.getIngestionToken();
        if (configured.isBlank()) {
            if (properties.isAllowUnauthenticated()) return;
            throw new GatewayException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "gateway_auth_not_configured", "模型网关未配置客户端访问凭证");
        }
        String provided = token;
        if ((provided == null || provided.isBlank()) && authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            provided = authorization.substring(7).trim();
        }
        if (provided == null || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8))) {
            throw new GatewayException(org.springframework.http.HttpStatus.UNAUTHORIZED, "invalid_gateway_token", "模型网关凭证无效");
        }
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<JsonNode> handleGatewayException(GatewayException exception) {
        JsonNode body = objectMapper.createObjectNode().set("error", objectMapper.createObjectNode()
                .put("message", exception.getMessage())
                .put("type", "eap_gateway_error")
                .put("code", exception.getCode())
                .put("trace_id", TraceContext.traceId()));
        return ResponseEntity.status(exception.getStatus()).header("X-EAP-Admission-Outcome",exception.admissionOutcome()).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<JsonNode> handleBusinessException(BusinessException exception) {
        return handleGatewayException(new GatewayException(org.springframework.http.HttpStatus.BAD_REQUEST, "invalid_request", exception.getMessage()));
    }

    private List<String> split(String values) {
        if (values == null || values.isBlank()) return List.of();
        return Arrays.stream(values.split(",")).map(String::trim).filter(value -> !value.isBlank()).limit(64).toList();
    }

    private String resolveEndpointKey(Long agentId, String endpointKey, HttpServletRequest request) {
        if (agentId != null || (endpointKey != null && !endpointKey.isBlank())) {
            return endpointKey;
        }
        if (!properties.isAutoAttributionEnabled()) {
            return null;
        }
        String remoteAddress = request == null ? null : request.getRemoteAddr();
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return "auto-network-unknown";
        }
        String normalized = remoteAddress.replaceAll("[^a-zA-Z0-9._:-]", "-");
        if (normalized.length() > 100) {
            normalized = normalized.substring(0, 100);
        }
        return "auto-network-" + normalized;
    }

    private void registerAutoAttributedEndpoint(String endpointKey, HttpServletRequest request) {
        if (endpointKey == null || !endpointKey.startsWith("auto-network-")) {
            return;
        }
        String remoteAddress = request == null ? "unknown" : request.getRemoteAddr();
        if (remoteAddress == null || remoteAddress.isBlank()) {
            remoteAddress = "unknown";
        }
        discoveryService.registerEndpoint(new AgentDiscoveryDtos.EndpointRegistrationRequest(
                endpointKey,
                "network-source-" + remoteAddress,
                null,
                "NETWORK",
                "gateway-auto-attribution/0.1"
        ));
    }
}
