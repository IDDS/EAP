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

    public SubstrateDeploymentController(SubstrateDeploymentService service) {
        this.service = service;
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

    @PostMapping("/{agentId}/substrate/deployments/{version}/retry")
    public ApiResponse<SubstrateDeploymentDtos.DeploymentResponse> retry(@PathVariable Long agentId, @PathVariable Integer version,
            Authentication authentication) {
        return ApiResponse.ok(service.retry(agentId, version, requester(authentication)));
    }

    @GetMapping("/{agentId}/substrate/instances")
    public ApiResponse<List<SubstrateDeploymentDtos.InstanceResponse>> instances(@PathVariable Long agentId) {
        return ApiResponse.ok(service.instances(agentId));
    }

    @PostMapping("/{agentId}/substrate/instances")
    public ApiResponse<SubstrateDeploymentDtos.InstanceResponse> createInstance(@PathVariable Long agentId, Authentication authentication) {
        return ApiResponse.ok(service.createInstance(agentId, requester(authentication)));
    }

    @DeleteMapping("/{agentId}/substrate/instances/{name}")
    public ApiResponse<Void> deleteInstance(@PathVariable Long agentId, @PathVariable String name, Authentication authentication) {
        service.deleteInstance(agentId, name, requester(authentication));
        return ApiResponse.ok(null);
    }

    // POST, not GET: a trial run calls the model and spends tokens.
    @PostMapping("/{agentId}/substrate/instances/{name}/chat")
    public ApiResponse<SubstrateDeploymentDtos.ChatResponse> chat(@PathVariable Long agentId, @PathVariable String name,
            @Valid @RequestBody SubstrateDeploymentDtos.ChatRequest request) {
        return ApiResponse.ok(service.chat(agentId, name, request.message()));
    }

    private static String requester(Authentication authentication) {
        return authentication == null ? "console" : authentication.getName();
    }
}
