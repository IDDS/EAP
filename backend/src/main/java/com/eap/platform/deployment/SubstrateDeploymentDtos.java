package com.eap.platform.deployment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class SubstrateDeploymentDtos {
    private SubstrateDeploymentDtos() {
    }

    /**
     * @param sourceType RUNNER (configuration-only agent on the platform runner image), IMAGE (existing image)
     *                   or SOURCE (built in-cluster from a public Git repository)
     * @param size       SMALL, MEDIUM or LARGE
     */
    public record DeployRequest(
            @NotBlank @Size(max = 16) String sourceType,
            @Size(max = 512) String image,
            @Size(max = 16) List<@Size(max = 512) String> command,
            Integer port,
            @Size(max = 256) String readinessPath,
            @Size(max = 20) Map<String, @Size(max = 2048) String> env,
            @Size(max = 8000) String systemPrompt,
            @Size(max = 64) String model,
            @Size(max = 16) String sandboxClass,
            @Size(max = 16) String size,
            @Size(max = 10) List<@Size(max = 253) String> egressHosts,
            @Size(max = 64) String requestKey,
            @Size(max = 512) String gitUrl,
            @Size(max = 200) String gitRef,
            @Size(max = 200) String contextPath,
            @Size(max = 200) String dockerfile
    ) {
        public DeployRequest(String sourceType, String image, List<String> command, Integer port, String readinessPath,
                             Map<String, String> env, String systemPrompt, String model, String sandboxClass, String size,
                             List<String> egressHosts, String requestKey) {
            this(sourceType, image, command, port, readinessPath, env, systemPrompt, model, sandboxClass, size,
                    egressHosts, requestKey, null, null, null, null);
        }
    }

    public record Step(String step, String status, String detail, Instant at) {
    }

    public record DeploymentResponse(String deploymentKey, Long agentId, Integer version, String sourceType, String status,
                                     String imageRef, String templateName, String instanceKey, String reason,
                                     String requestedBy, List<Step> steps, Map<String, Object> config,
                                     Instant createdAt, Instant updatedAt) {
    }

    public record InstanceResponse(String instanceKey, String name, Integer version, String state, String eapStatus, String detail) {
    }

    public record ChatRequest(@NotBlank @Size(max = 4000) String message) {
    }

    public record ChatResponse(String reply, Integer turns, String instanceKey) {
    }

    public record CapabilityResponse(boolean enabled, boolean runnerAvailable, boolean gatewayConfigured,
                                     String atespace, String gatewayHost, List<String> sizes, boolean sourceBuildAvailable) {
    }
}
