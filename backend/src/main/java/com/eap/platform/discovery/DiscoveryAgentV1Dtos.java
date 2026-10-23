package com.eap.platform.discovery;

import com.eap.platform.agent.RiskLevel;
import com.eap.platform.discovery.HostObservationService.HostObservationDtos.HostNetworkObservation;
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

public final class DiscoveryAgentV1Dtos {
    private DiscoveryAgentV1Dtos() { }

    public record EnrollmentTokenRequest(
            @Size(max = 64) String tenantId,
            @Size(max = 128) String siteName,
            @Size(max = 64) String environmentName,
            @Size(max = 128) String department,
            @Size(max = 32) Map<String, String> labels,
            @Min(1) @Max(1440) Integer ttlMinutes,
            @Min(1) @Max(100) Integer maxUses,
            @Size(max = 128) String createdBy
    ) { }

    public record EnrollmentTokenResponse(
            Long id,
            String token,
            String tokenHint,
            String tenantId,
            String siteName,
            String environmentName,
            String department,
            Map<String, String> labels,
            int maxUses,
            int usedCount,
            String status,
            Instant expiresAt,
            String createdBy,
            Instant createdAt
    ) { }

    public record EnrollRequest(
            @NotBlank @Size(max = 256) String enrollmentToken,
            @NotBlank @Size(max = 64) String installationId,
            @NotBlank @Size(max = 128) String machineFingerprint,
            @NotBlank @Size(max = 256) String hostName,
            @Size(max = 256) String accountName,
            @Size(max = 128) String operatingSystem,
            @Size(max = 32) String architecture,
            @NotBlank @Size(max = 64) String agentVersion,
            @Size(max = 32) List<@Size(max = 64) String> capabilities
    ) { }

    public record EnrollResponse(
            String endpointKey,
            String credential,
            String credentialHint,
            String contentCredential,
            String contentCredentialHint,
            PolicyResponse policy,
            Instant enrolledAt
    ) { }

    public record HeartbeatRequest(
            @NotBlank @Size(max = 64) String installationId,
            @NotBlank @Size(max = 128) String machineFingerprint,
            @NotBlank @Size(max = 64) String agentVersion,
            @Size(max = 64) String policyVersion,
            @Size(max = 64) String rulesVersion,
            @Min(0) long queueDepth,
            @Min(0) long queueBytes,
            Instant lastScanAt,
            @Size(max = 1024) String lastError,
            @Size(max = 32) List<@Size(max = 64) String> capabilities
    ) { }

    public record HeartbeatResponse(
            String endpointKey,
            String status,
            boolean fingerprintReviewRequired,
            PolicyResponse policy,
            Instant serverTime
    ) { }

    public record PolicyResponse(
            String policyVersion,
            String rulesVersion,
            int heartbeatIntervalSeconds,
            int scanIntervalSeconds,
            int fullSnapshotIntervalSeconds,
            int offlineQueueDays,
            long offlineQueueMaxBytes,
            boolean requireHttps,
            List<String> enabledDetectors,
            List<AgentSignatureRuleResponse> agentSignatures,
            boolean contentCaptureEnabled,
            boolean contentAutoAttachEnabled,
            List<String> contentAutoAttachProfiles,
            double rawContentSampleRate,
            int rawContentRetentionDays
    ) { }

    public record AgentSignatureRuleResponse(
            String product,
            String vendor,
            String framework,
            List<String> processMarkers,
            List<String> contentMarkers,
            List<String> packageNames,
            List<String> homeDirectories,
            List<String> manifestNames
    ) { }

    public record ObservationBatchRequest(
            @NotBlank @Size(max = 128) String batchId,
            @NotBlank @Size(max = 128) String scanId,
            @NotNull SnapshotType snapshotType,
            Instant observedAt,
            @NotNull @Size(max = 1000) List<@Valid ObservationRequest> observations
    ) { }

    public record HostObservationRequest(
            @NotNull HostObservationSensorType sensorType,
            @Size(max = 64) String collectorVersion,
            @NotEmpty @Size(max = 1000) List<@Valid HostNetworkObservation> observations
    ) { }

    public enum SnapshotType { INCREMENTAL, FULL }

    public record ObservationRequest(
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
            @Size(max = 128) String framework,
            @Size(max = 64) String runtimeStatus,
            @Size(max = 64) List<@Size(max = 128) String> ports,
            @Size(max = 32) List<@Size(max = 64) String> protocols,
            @Size(max = 64) List<@Size(max = 512) String> serviceEndpoints,
            @Size(max = 128) List<@Size(max = 128) String> skills,
            @Size(max = 128) List<@Size(max = 128) String> plugins,
            @Size(max = 256) List<@Size(max = 128) String> tools,
            @Size(max = 128) List<@Size(max = 128) String> mcpServers,
            @Size(max = 64) List<@Size(max = 128) String> models,
            @Size(max = 128) List<@Valid Relationship> relationships,
            @Size(max = 64) Map<String, Object> securitySignals,
            @Min(0) @Max(100) int confidence,
            @NotBlank @Size(max = 128) String evidenceType
    ) { }

    public record Relationship(
            @NotBlank @Size(max = 64) String relationType,
            @NotBlank @Size(max = 256) String target,
            @Min(0) @Max(100) int confidence
    ) { }

    public record ObservationBatchResponse(
            String endpointKey,
            String batchId,
            boolean duplicate,
            int observationsCount,
            int discoveredCount,
            Instant completedAt
    ) { }

    public record ContentApprovalRequest(
            @NotBlank String approvalRole,
            @NotBlank @Size(max = 128) String approvedBy
    ) { }

    public record EndpointAgentResponse(
            Long id,
            String endpointKey,
            String hostName,
            String accountName,
            String operatingSystem,
            String architecture,
            String agentVersion,
            String status,
            String tenantId,
            String siteName,
            String environmentName,
            String department,
            Map<String, String> labels,
            List<String> capabilities,
            String policyVersion,
            String rulesVersion,
            Instant lastHeartbeatAt,
            Instant lastScanAt,
            long queueDepth,
            long queueBytes,
            String lastError,
            boolean fingerprintReviewRequired,
            String contentCaptureStatus,
            String contentPlatformApprovedBy,
            String contentDataApprovedBy,
            String contentApprovalMode,
            boolean contentCaptureEnabled,
            List<String> collectionScopes,
            String tokenUsageStatus,
            String tokenUsageNote,
            Instant firstSeenAt,
            Instant lastSeenAt,
            Instant uninstalledAt
    ) { }

    public record ContentCaptureRequest(
            @NotBlank @Size(max = 128) String observationKey,
            @Size(max = 128) String agentCode,
            Long agentId,
            Long taskId,
            @Size(max = 128) String traceId,
            @Size(max = 128) String spanId,
            @NotBlank @Size(max = 32) String direction,
            @Size(max = 128) String provider,
            @Size(max = 128) String modelName,
            @NotBlank @Size(max = 4_194_304) String content,
            @Size(max = 32) String sensitivity,
            @Size(max = 128) String dataCategory,
            @Size(max = 64) Map<String, Object> metadata,
            boolean retainRaw,
            boolean sampled,
            Instant observedAt
    ) { }

    public record ContentCaptureResponse(
            Long id,
            String observationKey,
            String contentHash,
            boolean rawRetained,
            Instant expiresAt
    ) { }

    static RiskLevel recommendedRisk(Map<String, Object> signals, int mcpServers, int exposedEndpoints) {
        Map<String, Object> values = signals == null ? Map.of() : signals;
        boolean autoApprove = flag(values, "autoApprove", "autoApproveDetected");
        boolean untrusted = flag(values, "untrustedProcess", "trustedProcessFalse");
        boolean criticalPrivilege = flag(values, "privileged", "dockerSocketMounted", "hostPidMode", "hostNetworkMode");
        boolean severeContent = flag(values, "credentialValueDetected", "malwarePayloadDetected", "toolPoisoningDetected");
        boolean riskyContent = flag(values, "credentialReferenceDetected", "promptInjectionDetected", "toolShadowingDetected");
        if ((autoApprove && untrusted) || (criticalPrivilege && severeContent)) return RiskLevel.CRITICAL;
        if (autoApprove || untrusted || criticalPrivilege || severeContent || riskyContent) return RiskLevel.HIGH;
        if (mcpServers > 0 || exposedEndpoints > 0) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    private static boolean flag(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value instanceof Boolean bool && bool) return true;
            if (value != null && "true".equalsIgnoreCase(String.valueOf(value))) return true;
        }
        return false;
    }
}
