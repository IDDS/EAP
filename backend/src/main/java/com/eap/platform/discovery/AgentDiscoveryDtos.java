package com.eap.platform.discovery;

import com.eap.platform.agent.RiskLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AgentDiscoveryDtos {
    private AgentDiscoveryDtos() {
    }

    public record StatusRequest(@NotNull DiscoveryAssetStatus status) {
    }

    public record ProtocolProbeRequest(
            @NotBlank @Size(max = 2048) String targetUrl,
            @NotNull ProtocolDiscoveryType discoveryType
    ) {
    }

    public record ProtocolProbeResponse(
            String targetUrl,
            ProtocolDiscoveryType discoveryType,
            int endpointsRegistered,
            int documentsFetched,
            int a2aAgentsDiscovered,
            int mcpServersDiscovered,
            List<String> warnings
    ) {
    }

    public record KubernetesDiscoveryResponse(
            String endpointKey,
            String eventType,
            int containersInspected,
            int agentsDiscovered,
            int mcpServersDiscovered,
            int observationsRecorded,
            List<String> warnings
    ) {
    }

    public record EbpfDiscoveryResponse(
            String endpointKey,
            int flowsInspected,
            int agentsDiscovered,
            int mcpServersDiscovered,
            int observationsRecorded,
            List<String> warnings
    ) {
    }

    public record HostObservationResponse(
            String endpointKey,
            HostObservationSensorType sensorType,
            int observationsInspected,
            int agentsDiscovered,
            int mcpServersDiscovered,
            int observationsRecorded,
            List<String> warnings
    ) {
    }

    public record EndpointRegistrationRequest(
            @NotBlank @Size(max = 128) String endpointKey,
            @NotBlank @Size(max = 256) String hostName,
            @Size(max = 256) String accountName,
            @Size(max = 128) String operatingSystem,
            @Size(max = 64) String collectorVersion
    ) {
    }

    public record EndpointObservationRequest(
            @NotBlank @Size(max = 128) String endpointKey,
            @NotEmpty @Size(max = 1000) List<@Valid EndpointEvidenceRequest> observations
    ) {
    }

    /**
     * Normalized ingress for Microsoft Defender AgentsInfo/Exposure Graph exports.
     * The source payload is intentionally a bounded map so the adapter can accept
     * schema additions without coupling the EAP database to Microsoft's preview schema.
     */
    public record MicrosoftDefenderImportRequest(
            @NotBlank @Size(max = 128) String endpointKey,
            @NotBlank @Size(max = 256) String hostName,
            @Size(max = 256) String accountName,
            @Size(max = 128) String operatingSystem,
            @NotEmpty @Size(max = 1000) List<@Size(max = 64) Map<String, Object>> agentsInfo
    ) {
    }

    public record EndpointEvidenceRequest(
            @NotBlank @Size(max = 128) String fingerprint,
            @NotBlank @Size(max = 256) String name,
            @NotBlank @Size(max = 128) String vendor,
            @NotBlank @Size(max = 128) String product,
            @Size(max = 64) String version,
            @NotNull DiscoveryAssetType assetType,
            @NotNull DiscoverySourceType sourceType,
            @NotBlank @Size(max = 1024) String sourceLocation,
            @Size(max = 256) String accountName,
            @Size(max = 256) String processName,
            @Size(max = 1024) String executablePath,
            Boolean autoApprove,
            Boolean trustedProcess,
            @Min(0) @Max(10000) int mcpServerCount,
            @Min(0) @Max(100) int confidence,
            @NotNull RiskLevel riskLevel,
            @NotBlank @Size(max = 128) String evidenceType,
            @Size(max = 64) Map<String, Object> evidence
    ) {
    }

    public record DiscoveryEndpointResponse(
            Long id,
            String endpointKey,
            String hostName,
            String accountName,
            String operatingSystem,
            String collectorVersion,
            String status,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record EndpointIngestionResponse(
            DiscoveryEndpointResponse endpoint,
            DiscoveryRunResponse run
    ) {
    }

    public record DiscoveredAgentResponse(
            Long id,
            String fingerprint,
            String name,
            String vendor,
            String product,
            String version,
            DiscoveryAssetType assetType,
            DiscoveryAssetStatus status,
            RiskLevel riskLevel,
            Integer confidence,
            List<String> sourceTypes,
            String hostName,
            String accountName,
            String processName,
            String executablePath,
            Boolean autoApprove,
            Boolean trustedProcess,
            Integer mcpServerCount,
            Integer evidenceCount,
            Long managedAgentId,
            String availabilityStatus,
            String lastEndpointKey,
            Integer missingSnapshotCount,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record DiscoveryRunResponse(
            Long id,
            DiscoveryTriggerType triggerType,
            String endpointKey,
            DiscoveryRunStatus status,
            Integer observationsCount,
            Integer discoveredCount,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt
    ) {
    }

    public record DiscoverySummary(
            boolean autonomousDiscoveryEnabled,
            long totalDiscovered,
            long agentCount,
            long mcpConfigurationCount,
            long skillCount,
            long pluginCount,
            long highRiskCount,
            long reviewingCount,
            long managedCount,
            DiscoveryRunResponse lastRun
    ) {
    }

    public record DiscoveryIdentityResponse(
            Long id,
            String identityKey,
            Long discoveredAgentId,
            String relationType,
            Integer confidence,
            String identityBasis,
            List<String> sourceTypes,
            Instant firstSeenAt,
            Instant lastSeenAt
    ) {
    }

    static DiscoveredAgentResponse from(DiscoveredAgent agent) {
        List<String> sourceTypes = agent.sourceTypes == null || agent.sourceTypes.isBlank()
                ? List.of()
                : List.of(agent.sourceTypes.split(","));
        return new DiscoveredAgentResponse(
                agent.id, agent.fingerprint, agent.name, agent.vendor, agent.product, agent.version, agent.assetType,
                agent.status, agent.riskLevel, agent.confidence, sourceTypes, agent.hostName, agent.accountName,
                agent.processName, agent.executablePath, agent.autoApprove, agent.trustedProcess,
                agent.mcpServerCount, agent.evidenceCount, agent.managedAgentId,
                agent.availabilityStatus, agent.lastEndpointKey, agent.missingSnapshotCount,
                agent.firstSeenAt, agent.lastSeenAt, agent.createdAt, agent.updatedAt
        );
    }

    static DiscoveryIdentityResponse from(DiscoveryIdentityLink link) {
        return new DiscoveryIdentityResponse(
                link.id,
                link.identityKey,
                link.discoveredAgent == null ? null : link.discoveredAgent.id,
                link.relationType,
                link.confidence,
                link.identityBasis,
                link.sourceTypes == null || link.sourceTypes.isBlank()
                        ? List.of() : List.of(link.sourceTypes.split(",")),
                link.firstSeenAt,
                link.lastSeenAt
        );
    }

    static DiscoveryRunResponse from(DiscoveryRun run) {
        if (run == null) {
            return null;
        }
        return new DiscoveryRunResponse(
                run.id, run.triggerType, run.endpointKey, run.status, run.observationsCount, run.discoveredCount,
                run.errorMessage, run.startedAt, run.finishedAt
        );
    }

    static DiscoveryEndpointResponse from(DiscoveryEndpoint endpoint) {
        return new DiscoveryEndpointResponse(
                endpoint.id, endpoint.endpointKey, endpoint.hostName, endpoint.accountName,
                endpoint.operatingSystem, endpoint.collectorVersion, endpoint.status,
                endpoint.firstSeenAt, endpoint.lastSeenAt, endpoint.createdAt, endpoint.updatedAt
        );
    }
}
