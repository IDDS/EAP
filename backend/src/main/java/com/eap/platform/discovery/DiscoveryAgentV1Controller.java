package com.eap.platform.discovery;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/discovery/v1")
public class DiscoveryAgentV1Controller {
    private final DiscoveryAgentV1Service service;
    private final BusinessContentVaultService contentVaultService;
    private final HostObservationService hostObservationService;

    public DiscoveryAgentV1Controller(
            DiscoveryAgentV1Service service,
            BusinessContentVaultService contentVaultService,
            HostObservationService hostObservationService
    ) {
        this.service = service;
        this.contentVaultService = contentVaultService;
        this.hostObservationService = hostObservationService;
    }

    @PostMapping("/admin/enrollment-tokens")
    public ApiResponse<DiscoveryAgentV1Dtos.EnrollmentTokenResponse> createToken(
            @Valid @RequestBody DiscoveryAgentV1Dtos.EnrollmentTokenRequest request
    ) {
        return ApiResponse.ok(service.createEnrollmentToken(request));
    }

    @GetMapping("/admin/enrollment-tokens")
    public ApiResponse<List<DiscoveryAgentV1Dtos.EnrollmentTokenResponse>> listTokens() {
        return ApiResponse.ok(service.listEnrollmentTokens());
    }

    @GetMapping("/admin/agents")
    public ApiResponse<List<DiscoveryAgentV1Dtos.EndpointAgentResponse>> listAgents() {
        return ApiResponse.ok(service.listAgents());
    }

    @PostMapping("/admin/agents/{id}/content-approval")
    public ApiResponse<DiscoveryAgentV1Dtos.EndpointAgentResponse> approveContent(
            @PathVariable Long id,
            @Valid @RequestBody DiscoveryAgentV1Dtos.ContentApprovalRequest request
    ) {
        return ApiResponse.ok(service.approveContent(id, request));
    }

    @PostMapping("/admin/agents/{id}/content-revoke")
    public ApiResponse<DiscoveryAgentV1Dtos.EndpointAgentResponse> revokeContent(
            @PathVariable Long id,
            @RequestParam(required = false) String actor
    ) {
        return ApiResponse.ok(service.revokeContent(id, actor));
    }

    @PostMapping("/admin/agents/{id}/content-enable")
    public ApiResponse<DiscoveryAgentV1Dtos.EndpointAgentResponse> enableContent(
            @PathVariable Long id,
            @RequestParam(required = false) String actor
    ) {
        return ApiResponse.ok(service.enableContent(id, actor));
    }

    @PostMapping("/admin/agents/{id}/revoke")
    public ApiResponse<DiscoveryAgentV1Dtos.EndpointAgentResponse> revokeAgent(
            @PathVariable Long id,
            @RequestParam(required = false) String actor
    ) {
        return ApiResponse.ok(service.revokeAgent(id, actor));
    }

    @PostMapping("/admin/agents/{id}/approve-fingerprint")
    public ApiResponse<DiscoveryAgentV1Dtos.EndpointAgentResponse> approveFingerprint(
            @PathVariable Long id,
            @RequestParam(required = false) String actor
    ) {
        return ApiResponse.ok(service.approveFingerprint(id, actor));
    }

    @PostMapping("/agent/enroll")
    public ApiResponse<DiscoveryAgentV1Dtos.EnrollResponse> enroll(
            @Valid @RequestBody DiscoveryAgentV1Dtos.EnrollRequest request
    ) {
        return ApiResponse.ok(service.enroll(request));
    }

    @PostMapping("/agent/heartbeat")
    public ApiResponse<DiscoveryAgentV1Dtos.HeartbeatResponse> heartbeat(
            @RequestHeader(value = "X-EAP-Agent-Credential", required = false) String credential,
            @Valid @RequestBody DiscoveryAgentV1Dtos.HeartbeatRequest request
    ) {
        return ApiResponse.ok(service.heartbeat(credential, request));
    }

    @GetMapping("/agent/policy")
    public ApiResponse<DiscoveryAgentV1Dtos.PolicyResponse> policy(
            @RequestHeader(value = "X-EAP-Agent-Credential", required = false) String credential
    ) {
        return ApiResponse.ok(service.policy(credential));
    }

    @PostMapping("/agent/observations")
    public ApiResponse<DiscoveryAgentV1Dtos.ObservationBatchResponse> observations(
            @RequestHeader(value = "X-EAP-Agent-Credential", required = false) String credential,
            @Valid @RequestBody DiscoveryAgentV1Dtos.ObservationBatchRequest request
    ) {
        return ApiResponse.ok(service.ingest(credential, request));
    }

    @PostMapping("/agent/host-observations")
    public ApiResponse<AgentDiscoveryDtos.HostObservationResponse> hostObservations(
            @RequestHeader(value = "X-EAP-Agent-Credential", required = false) String credential,
            @Valid @RequestBody DiscoveryAgentV1Dtos.HostObservationRequest request
    ) {
        DiscoveryEndpoint endpoint = service.authenticate(credential);
        return ApiResponse.ok(hostObservationService.ingestForEndpoint(
                endpoint, request.sensorType(), request.collectorVersion(), request.observations()
        ));
    }

    @PostMapping("/agent/content")
    public ApiResponse<DiscoveryAgentV1Dtos.ContentCaptureResponse> content(
            @RequestHeader(value = "X-EAP-Content-Credential", required = false) String credential,
            @Valid @RequestBody DiscoveryAgentV1Dtos.ContentCaptureRequest request
    ) {
        return ApiResponse.ok(contentVaultService.capture(credential, request));
    }

    @GetMapping("/agent/content-policy")
    public ApiResponse<DiscoveryAgentV1Dtos.PolicyResponse> contentPolicy(
            @RequestHeader(value = "X-EAP-Content-Credential", required = false) String credential
    ) {
        return ApiResponse.ok(service.contentPolicy(credential));
    }

    @PostMapping("/agent/uninstall")
    public ApiResponse<Void> uninstall(
            @RequestHeader(value = "X-EAP-Agent-Credential", required = false) String credential
    ) {
        service.uninstall(credential);
        return ApiResponse.ok(null);
    }
}
