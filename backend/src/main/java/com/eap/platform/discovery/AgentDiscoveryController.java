package com.eap.platform.discovery;

import com.eap.platform.common.ApiResponse;
import com.eap.platform.config.MachineTokenValidator;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
@RestController
@RequestMapping("/api/discovery")
public class AgentDiscoveryController {
    private final AgentDiscoveryService discoveryService;
    private final MachineTokenValidator machineTokenValidator;
    private final ProtocolDiscoveryService protocolDiscoveryService;

    public AgentDiscoveryController(
            AgentDiscoveryService discoveryService,
            MachineTokenValidator machineTokenValidator,
            ProtocolDiscoveryService protocolDiscoveryService
    ) {
        this.discoveryService = discoveryService;
        this.machineTokenValidator = machineTokenValidator;
        this.protocolDiscoveryService = protocolDiscoveryService;
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

    @PostMapping("/protocol/probe")
    public ApiResponse<AgentDiscoveryDtos.ProtocolProbeResponse> probeProtocol(
            @Valid @RequestBody AgentDiscoveryDtos.ProtocolProbeRequest request
    ) {
        return ApiResponse.ok(protocolDiscoveryService.probe(request));
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
        machineTokenValidator.require(discoveryService.ingestionToken(), token, "端点发现上报凭证");
    }
}
