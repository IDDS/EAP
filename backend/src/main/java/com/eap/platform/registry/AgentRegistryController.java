package com.eap.platform.registry;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class AgentRegistryController {
    private final AgentRegistryService registryService;

    public AgentRegistryController(AgentRegistryService registryService) {
        this.registryService = registryService;
    }

    @GetMapping("/agents/{agentId}/capabilities")
    public ApiResponse<List<AgentRegistryDtos.CapabilityResponse>> listCapabilities(@PathVariable Long agentId) {
        return ApiResponse.ok(registryService.listCapabilities(agentId));
    }

    @PostMapping("/agents/{agentId}/capabilities")
    public ApiResponse<AgentRegistryDtos.CapabilityResponse> registerCapability(@PathVariable Long agentId, @Valid @RequestBody AgentRegistryDtos.CapabilityRequest request) {
        return ApiResponse.ok(registryService.registerCapability(agentId, request));
    }

    @GetMapping("/agents/{agentId}/runtime-instances")
    public ApiResponse<List<AgentRegistryDtos.RuntimeInstanceResponse>> listRuntimeInstances(@PathVariable Long agentId) {
        return ApiResponse.ok(registryService.listRuntimeInstances(agentId));
    }

    @PostMapping("/agents/{agentId}/runtime-instances")
    public ApiResponse<AgentRegistryDtos.RuntimeInstanceResponse> registerRuntime(@PathVariable Long agentId, @Valid @RequestBody AgentRegistryDtos.RuntimeInstanceRequest request) {
        return ApiResponse.ok(registryService.registerRuntime(agentId, request));
    }

    @PostMapping("/agent-runtime/{instanceKey}/heartbeat")
    public ApiResponse<AgentRegistryDtos.RuntimeInstanceResponse> heartbeat(@PathVariable String instanceKey, @Valid @RequestBody AgentRegistryDtos.HeartbeatRequest request) {
        return ApiResponse.ok(registryService.heartbeat(instanceKey, request));
    }

    @PostMapping("/agent-discovery/search")
    public ApiResponse<AgentRegistryDtos.DiscoveryResponse> discover(@Valid @RequestBody AgentRegistryDtos.DiscoveryRequest request) {
        return ApiResponse.ok(registryService.discover(request));
    }
}

