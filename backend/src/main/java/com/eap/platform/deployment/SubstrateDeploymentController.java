package com.eap.platform.deployment;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Console deployment of agents onto Agent Substrate. Writes require OPERATOR or PLATFORM_ADMIN (SecurityConfig /api/agents/**). */
@RestController
@RequestMapping("/api/agents")
public class SubstrateDeploymentController {
    private final SubstrateDeploymentService service;
    private final ChatSubmissionService chats;

    public SubstrateDeploymentController(SubstrateDeploymentService service, ChatSubmissionService chats) {
        this.service = service; this.chats = chats;
    }

    @GetMapping("/substrate/capabilities")
    public ApiResponse<SubstrateDeploymentDtos.CapabilityResponse> capabilities() {
        return ApiResponse.ok(service.capabilities());
    }

    @GetMapping("/{agentId}/substrate/deployments")
    public ApiResponse<List<SubstrateDeploymentDtos.DeploymentResponse>> deployments(@PathVariable Long agentId) {
        return ApiResponse.ok(service.list(agentId));
    }

    @PostMapping("/{agentId}/substrate/deployments")
    public ApiResponse<SubstrateDeploymentDtos.DeploymentResponse> deploy(@PathVariable Long agentId,
            @Valid @RequestBody SubstrateDeploymentDtos.DeployRequest request, Authentication authentication) {
        return ApiResponse.ok(service.submit(agentId, request, requester(authentication)));
    }

    @GetMapping("/{agentId}/substrate/deployments/requests/{requestKey}")
    public ApiResponse<SubstrateDeploymentDtos.DeploymentReceipt> deploymentReceipt(@PathVariable Long agentId,
            @PathVariable String requestKey) {
        return ApiResponse.ok(service.receipt(agentId, requestKey));
    }

    @PostMapping("/{agentId}/substrate/deployments/{version}/retry")
    public ApiResponse<SubstrateDeploymentDtos.DeploymentResponse> retry(@PathVariable Long agentId, @PathVariable Integer version,
            Authentication authentication) {
        return ApiResponse.ok(service.retry(agentId, version, requester(authentication)));
    }

    @GetMapping("/{agentId}/substrate/deployments/{version}/build-log")
    public ApiResponse<String> buildLog(@PathVariable Long agentId, @PathVariable Integer version) {
        return ApiResponse.ok(service.buildLog(agentId, version));
    }

    @GetMapping("/{agentId}/substrate/instances")
    public ApiResponse<List<SubstrateDeploymentDtos.InstanceResponse>> instances(@PathVariable Long agentId) {
        return ApiResponse.ok(service.instances(agentId));
    }

    @PostMapping("/{agentId}/substrate/instances")
    public ApiResponse<SubstrateDeploymentDtos.InstanceResponse> createInstance(@PathVariable Long agentId, @Valid @RequestBody SubstrateDeploymentDtos.CreateInstanceRequest request, Authentication authentication) {
        return ApiResponse.ok(service.createInstance(agentId, request.requestKey(), requester(authentication)));
    }

    @DeleteMapping("/{agentId}/substrate/instances/{name}")
    public ApiResponse<Void> deleteInstance(@PathVariable Long agentId, @PathVariable String name, Authentication authentication) {
        service.deleteInstance(agentId, name, requester(authentication));
        return ApiResponse.ok(null);
    }

    // POST, not GET: a trial run calls the model and spends tokens.
    @PostMapping("/{agentId}/substrate/instances/{name}/chat")
    public ApiResponse<ChatSubmissionService.Receipt> chat(@PathVariable Long agentId, @PathVariable String name,
            @Valid @RequestBody SubstrateDeploymentDtos.ChatRequest request, Authentication authentication) {
        return ApiResponse.ok(chats.submit(agentId, name, request, authentication == null ? "console" : authentication.getName()));
    }

    private static String requester(Authentication authentication) {
        return authentication == null ? "console" : authentication.getName();
    }
    @GetMapping("/{agentId}/substrate/instances/{name}/chat/{requestKey}")
    public ApiResponse<ChatSubmissionService.Receipt> chatReceipt(@PathVariable Long agentId, @PathVariable String name,
            @PathVariable String requestKey, Authentication authentication) {
        return ApiResponse.ok(chats.get(agentId, name, requestKey, authentication == null ? "console" : authentication.getName()));
    }

}
