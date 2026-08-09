package com.eap.platform.discovery;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/discovery")
public class AgentDiscoveryController {
    private final AgentDiscoveryService discoveryService;

    public AgentDiscoveryController(AgentDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @GetMapping("/summary")
    public ApiResponse<AgentDiscoveryDtos.DiscoverySummary> summary() {
        return ApiResponse.ok(discoveryService.summary());
    }

    @GetMapping("/agents")
    public ApiResponse<List<AgentDiscoveryDtos.DiscoveredAgentResponse>> agents() {
        return ApiResponse.ok(discoveryService.listAgents());
    }

    @GetMapping("/runs")
    public ApiResponse<List<AgentDiscoveryDtos.DiscoveryRunResponse>> runs() {
        return ApiResponse.ok(discoveryService.listRuns());
    }

    @GetMapping("/endpoints")
    public ApiResponse<List<AgentDiscoveryDtos.DiscoveryEndpointResponse>> endpoints() {
        return ApiResponse.ok(discoveryService.listEndpoints());
    }

    @PostMapping("/scans")
    public ApiResponse<AgentDiscoveryDtos.DiscoveryRunResponse> scan() {
        return ApiResponse.ok(discoveryService.runScan(DiscoveryTriggerType.MANUAL));
    }

    @PostMapping("/agents/{id}/status")
    public ApiResponse<AgentDiscoveryDtos.DiscoveredAgentResponse> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody AgentDiscoveryDtos.StatusRequest request
    ) {
        return ApiResponse.ok(discoveryService.updateStatus(id, request.status()));
    }

    @PostMapping("/endpoints/register")
    public ApiResponse<AgentDiscoveryDtos.DiscoveryEndpointResponse> registerEndpoint(
            @RequestHeader(value = "X-EAP-Discovery-Token", required = false) String token,
            @Valid @RequestBody AgentDiscoveryDtos.EndpointRegistrationRequest request
    ) {
        requireIngestionToken(token);
        return ApiResponse.ok(discoveryService.registerEndpoint(request));
    }

    @PostMapping("/endpoints/observations")
    public ApiResponse<AgentDiscoveryDtos.EndpointIngestionResponse> ingestEndpoint(
            @RequestHeader(value = "X-EAP-Discovery-Token", required = false) String token,
            @Valid @RequestBody AgentDiscoveryDtos.EndpointObservationRequest request
    ) {
        requireIngestionToken(token);
        return ApiResponse.ok(discoveryService.ingestEndpoint(request));
    }

    @PostMapping("/connectors/microsoft-defender/ingest")
    public ApiResponse<AgentDiscoveryDtos.EndpointIngestionResponse> ingestMicrosoftDefender(
            @RequestHeader(value = "X-EAP-Discovery-Token", required = false) String token,
            @Valid @RequestBody AgentDiscoveryDtos.MicrosoftDefenderImportRequest request
    ) {
        requireIngestionToken(token);
        return ApiResponse.ok(discoveryService.ingestMicrosoftDefender(request));
    }

    private void requireIngestionToken(String token) {
        String configured = discoveryService.ingestionToken();
        if (configured.isBlank()) {
            return;
        }
        if (token == null || !MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8)
        )) {
            throw new com.eap.platform.common.BusinessException("端点发现上报凭证无效");
        }
    }
}
