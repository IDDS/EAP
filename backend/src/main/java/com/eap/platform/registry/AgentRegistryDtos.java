package com.eap.platform.registry;

import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.RiskLevel;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class AgentRegistryDtos {
    private AgentRegistryDtos() {
    }

    public record CapabilityRequest(
            @NotBlank @Size(max = 128) String capability,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 512) String description
    ) {
    }

    public record RuntimeInstanceRequest(
            @NotBlank @Size(max = 128) String instanceKey,
            @NotBlank @Size(max = 512) String endpoint,
            @NotBlank @Size(max = 32) String protocol,
            Boolean dispatchAuthenticated
    ) {
    }

    public record HeartbeatRequest(
            @NotNull RuntimeStatus status,
            @NotNull @Min(0) @Max(100) Integer loadScore
    ) {
    }

    public record DiscoveryRequest(
            @NotBlank @Size(max = 128) String capability,
            RiskLevel maxRiskLevel,
            @Size(max = 128) String department,
            @Size(max = 64) String protocol
    ) {
    }

    public record CapabilityResponse(Long id, Long agentId, String capability, String name, String description, Instant createdAt, Instant updatedAt) {
    }

    public record RuntimeInstanceResponse(Long id, Long agentId, String instanceKey, String endpoint, String protocol, boolean dispatchAuthenticated, RuntimeStatus status, Integer loadScore, Instant lastHeartbeatAt, Instant createdAt, Instant updatedAt) {
    }

    public record DiscoveryCandidate(
            Long agentId,
            String code,
            String name,
            String ownerDepartment,
            AgentLifecycleStatus lifecycleStatus,
            RiskLevel riskLevel,
            String capability,
            String endpoint,
            String protocol,
            RuntimeStatus runtimeStatus,
            Integer loadScore,
            Integer score,
            List<String> constraints
    ) {
    }

    public record DiscoveryResponse(List<DiscoveryCandidate> candidates) {
    }

    static CapabilityResponse from(AgentCapability capability) {
        return new CapabilityResponse(capability.id, capability.agentId, capability.capability, capability.name, capability.description, capability.createdAt, capability.updatedAt);
    }

    static RuntimeInstanceResponse from(AgentRuntimeInstance instance) {
        return from(instance, instance.status);
    }

    static RuntimeInstanceResponse from(AgentRuntimeInstance instance, RuntimeStatus status) {
        return new RuntimeInstanceResponse(instance.id, instance.agentId, instance.instanceKey, instance.endpoint,
                instance.protocol, Boolean.TRUE.equals(instance.dispatchAuthenticated), status,
                instance.loadScore, instance.lastHeartbeatAt, instance.createdAt, instance.updatedAt);
    }
}
