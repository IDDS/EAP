package com.eap.platform.deployment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

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
            @Size(max = 200) String dockerfile,
            @Min(1) @Max(8192) Integer maxTokens,
            @Size(max = 32) String thinkingMode,
            @Size(max = 32) String runnerFramework,
            @Size(max = 32) String runnerToolMode
    ) {
        public DeployRequest(String sourceType, String image, List<String> command, Integer port, String readinessPath,
                             Map<String, String> env, String systemPrompt, String model, String sandboxClass, String size,
                             List<String> egressHosts, String requestKey, String gitUrl, String gitRef,
                             String contextPath, String dockerfile, Integer maxTokens, String thinkingMode, String runnerFramework) {
            this(sourceType, image, command, port, readinessPath, env, systemPrompt, model, sandboxClass, size,
                    egressHosts, requestKey, gitUrl, gitRef, contextPath, dockerfile, maxTokens, thinkingMode, runnerFramework, null);
        }
        public DeployRequest(String sourceType, String image, List<String> command, Integer port, String readinessPath,
                             Map<String, String> env, String systemPrompt, String model, String sandboxClass, String size,
                             List<String> egressHosts, String requestKey, String gitUrl, String gitRef,
                             String contextPath, String dockerfile, Integer maxTokens, String thinkingMode) {
            this(sourceType, image, command, port, readinessPath, env, systemPrompt, model, sandboxClass, size,
                    egressHosts, requestKey, gitUrl, gitRef, contextPath, dockerfile, maxTokens, thinkingMode, null);
        }
        public DeployRequest(String sourceType, String image, List<String> command, Integer port, String readinessPath,
                             Map<String, String> env, String systemPrompt, String model, String sandboxClass, String size,
                             List<String> egressHosts, String requestKey, String gitUrl, String gitRef,
                             String contextPath, String dockerfile) {
            this(sourceType, image, command, port, readinessPath, env, systemPrompt, model, sandboxClass, size,
                    egressHosts, requestKey, gitUrl, gitRef, contextPath, dockerfile, null, null);
        }
        public DeployRequest(String sourceType, String image, List<String> command, Integer port, String readinessPath,
                             Map<String, String> env, String systemPrompt, String model, String sandboxClass, String size,
                             List<String> egressHosts, String requestKey) {
            this(sourceType, image, command, port, readinessPath, env, systemPrompt, model, sandboxClass, size,
                    egressHosts, requestKey, null, null, null, null);
        }
    }

    public record Step(String step, String status, String detail, Instant at) {
    }

    /** Metadata only: querying a submission never starts or resumes deployment work. */
    public record DeploymentReceipt(String requestKey, Long agentId, String deploymentKey, Integer version,
                                    String status, String instanceKey) {
    }

    public record DeploymentResponse(String deploymentKey, Long agentId, Integer version, String sourceType, String status,
                                     String imageRef, String templateName, String instanceKey, String reason,
                                     String requestedBy, List<Step> steps, Map<String, Object> config,
                                     Instant createdAt, Instant updatedAt) {
    }

    public record InstanceResponse(String instanceKey, String name, Integer version, String state, String eapStatus, String detail) {
    }

    public record CreateInstanceRequest(@NotBlank @Size(max=36) String requestKey) {}

    public record ChatMessage(@NotBlank @Pattern(regexp = "user|assistant") String role,
                              @NotBlank @Size(max = 65536) String content) {}

    public record ChatRequest(@NotBlank @Pattern(regexp = "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") String requestKey,
                              @NotBlank @Size(max = 4000) String message,
                              @Size(max = 40) List<@NotNull @jakarta.validation.Valid ChatMessage> history) {
        public ChatRequest { if (history == null) history = List.of(); }
    }

    public record ChatResponse(String reply, Integer turns, String instanceKey) {
    }

    public record CapabilityResponse(boolean enabled, boolean runnerAvailable, boolean gatewayConfigured,
                                     String atespace, String gatewayHost, List<String> sizes, boolean sourceBuildAvailable, boolean instanceIdentityAvailable,
                                     boolean kagentRunnerAvailable) {
    }
}
