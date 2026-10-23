package com.eap.platform.discovery;

import com.eap.platform.audit.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoveryAgentV1ServiceTests {
    @Mock private DiscoveryEnrollmentTokenRepository tokenRepository;
    @Mock private DiscoveryEndpointRepository endpointRepository;
    @Mock private DiscoveryIngestionBatchRepository batchRepository;
    @Mock private AgentDiscoveryService discoveryService;
    @Mock private AuditLogRepository auditLogRepository;

    private DiscoveryAgentV1Service service;

    @BeforeEach
    void setUp() {
        service = new DiscoveryAgentV1Service(
                tokenRepository, endpointRepository, batchRepository, discoveryService,
                new DiscoveryAgentV1Properties(), auditLogRepository, new ObjectMapper().findAndRegisterModules(), new com.eap.platform.config.SecurityProperties()
        );
    }

    @Test
    void enrollsWithSeparateDiscoveryAndContentCredentialsAndDualApproval() {
        AtomicReference<DiscoveryEnrollmentToken> storedToken = new AtomicReference<>();
        when(tokenRepository.save(any())).thenAnswer(invocation -> {
            DiscoveryEnrollmentToken token = invocation.getArgument(0);
            token.id = 1L;
            storedToken.set(token);
            return token;
        });
        DiscoveryAgentV1Dtos.EnrollmentTokenResponse token = service.createEnrollmentToken(
                new DiscoveryAgentV1Dtos.EnrollmentTokenRequest(
                        "default", "tencent", "prod", "AI", Map.of("region", "ap-shanghai"), 15, 1, "admin-a"
                )
        );
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(storedToken.get()));
        when(endpointRepository.findByInstallationId("install-1")).thenReturn(Optional.empty());
        AtomicReference<DiscoveryEndpoint> storedEndpoint = new AtomicReference<>();
        when(endpointRepository.save(any())).thenAnswer(invocation -> {
            DiscoveryEndpoint endpoint = invocation.getArgument(0);
            endpoint.id = 9L;
            storedEndpoint.set(endpoint);
            return endpoint;
        });

        DiscoveryAgentV1Dtos.EnrollResponse enrolled = service.enroll(new DiscoveryAgentV1Dtos.EnrollRequest(
                token.token(), "install-1", "fingerprint-1", "vm-1", "ubuntu", "linux", "amd64", "1.0.0", List.of("PROCESS")
        ));

        assertThat(enrolled.credential()).startsWith("eap_agent_");
        assertThat(enrolled.contentCredential()).startsWith("eap_content_").isNotEqualTo(enrolled.credential());
        assertThat(storedEndpoint.get().credentialHash).isNotEqualTo(storedEndpoint.get().contentCredentialHash);
        assertThat(enrolled.policy().contentCaptureEnabled()).isFalse();
        assertThat(enrolled.policy().contentAutoAttachEnabled()).isFalse();

        when(endpointRepository.findById(9L)).thenReturn(Optional.of(storedEndpoint.get()));
        assertThat(service.approveContent(9L, new DiscoveryAgentV1Dtos.ContentApprovalRequest("PLATFORM_ADMIN", "admin-a"))
                .contentCaptureStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(service.approveContent(9L, new DiscoveryAgentV1Dtos.ContentApprovalRequest("DATA_OWNER", "owner-b"))
                .contentCaptureStatus()).isEqualTo("ENABLED");
    }

    @Test
    void centralPolicyEnablesOnlyApprovedAutoAttachProfiles() {
        DiscoveryAgentV1Properties properties = new DiscoveryAgentV1Properties();
        properties.setContentAutoAttachEnabled(true);
        properties.setContentAutoAttachProfiles("litellm, openai-sdk");
        service = new DiscoveryAgentV1Service(
                tokenRepository, endpointRepository, batchRepository, discoveryService,
                properties, auditLogRepository, new ObjectMapper().findAndRegisterModules(), new com.eap.platform.config.SecurityProperties()
        );
        DiscoveryEndpoint endpoint = endpoint();
        endpoint.contentCaptureStatus = "ENABLED";
        when(endpointRepository.findByContentCredentialHash(anyString())).thenReturn(Optional.of(endpoint));

        DiscoveryAgentV1Dtos.PolicyResponse policy = service.contentPolicy("content-credential");

        assertThat(policy.contentCaptureEnabled()).isTrue();
        assertThat(policy.contentAutoAttachEnabled()).isTrue();
        assertThat(policy.contentAutoAttachProfiles()).containsExactly("litellm", "openai-sdk");
    }

    @Test
    void reportsCollectionScopeWithoutClaimingPassiveTokenVisibility() {
        DiscoveryEndpoint endpoint = endpoint();
        endpoint.capabilitiesJson = "[\"PROCESS\",\"EBPF\"]";

        DiscoveryAgentV1Dtos.EndpointAgentResponse passive = service.endpointResponse(endpoint);

        assertThat(passive.collectionScopes()).contains("PROCESS", "EBPF_METADATA");
        assertThat(passive.collectionScopes()).doesNotContain("BUSINESS_CONTENT");
        assertThat(passive.tokenUsageStatus()).isEqualTo("NOT_AVAILABLE");
        assertThat(passive.tokenUsageNote()).contains("eBPF");

        endpoint.contentCaptureStatus = "ENABLED";
        DiscoveryAgentV1Dtos.EndpointAgentResponse governed = service.endpointResponse(endpoint);

        assertThat(governed.collectionScopes()).contains("BUSINESS_CONTENT");
        assertThat(governed.tokenUsageStatus()).isEqualTo("AVAILABLE_WHEN_REPORTED");
        assertThat(governed.tokenUsageNote()).contains("usage");
    }

    @Test
    void noApprovalModeEnablesContentAndAutoAttachWithoutFabricatedApprovers() {
        DiscoveryAgentV1Properties properties = new DiscoveryAgentV1Properties();
        properties.setContentApprovalMode("NONE");
        properties.setContentAutoAttachEnabled(true);
        service = new DiscoveryAgentV1Service(
                tokenRepository, endpointRepository, batchRepository, discoveryService,
                properties, auditLogRepository, new ObjectMapper().findAndRegisterModules(), new com.eap.platform.config.SecurityProperties()
        );
        DiscoveryEndpoint endpoint = endpoint();
        endpoint.capabilitiesJson = "[\"CONTENT_PROXY\",\"AUTO_ATTACH\"]";
        when(endpointRepository.findByContentCredentialHash(anyString())).thenReturn(Optional.of(endpoint));

        DiscoveryAgentV1Dtos.PolicyResponse policy = service.contentPolicy("content-credential");
        DiscoveryAgentV1Dtos.EndpointAgentResponse response = service.endpointResponse(endpoint);

        assertThat(policy.contentCaptureEnabled()).isTrue();
        assertThat(policy.contentAutoAttachEnabled()).isTrue();
        assertThat(response.contentApprovalMode()).isEqualTo("NONE");
        assertThat(response.contentCaptureEnabled()).isTrue();
        assertThat(response.contentCaptureStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(response.contentPlatformApprovedBy()).isNull();
        assertThat(response.contentDataApprovedBy()).isNull();
        assertThat(response.collectionScopes()).contains("BUSINESS_CONTENT");
    }

    @Test
    void acceptsEmptyFullSnapshotSoMissingAssetsCanBeReconciled() {
        DiscoveryEndpoint endpoint = endpoint();
        when(endpointRepository.findByCredentialHash(anyString())).thenReturn(Optional.of(endpoint));
        when(batchRepository.findByEndpointKeyAndBatchId("endpoint-1", "batch-1")).thenReturn(Optional.empty());
        when(batchRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        DiscoveryAgentV1Dtos.ObservationBatchResponse response = service.ingest(
                "discovery-credential",
                new DiscoveryAgentV1Dtos.ObservationBatchRequest(
                        "batch-1", "scan-1", DiscoveryAgentV1Dtos.SnapshotType.FULL, Instant.now(), List.of()
                )
        );

        assertThat(response.observationsCount()).isZero();
        verify(discoveryService).reconcileEndpointSnapshot("endpoint-1", java.util.Set.of());
        verify(discoveryService, never()).ingestEndpoint(any());
    }

    private DiscoveryEndpoint endpoint() {
        DiscoveryEndpoint endpoint = new DiscoveryEndpoint();
        endpoint.id = 9L;
        endpoint.endpointKey = "endpoint-1";
        endpoint.installationId = "install-1";
        endpoint.machineFingerprint = "fingerprint-1";
        endpoint.hostName = "vm-1";
        endpoint.tenantId = "default";
        endpoint.status = "ONLINE";
        endpoint.contentCaptureStatus = "DISABLED";
        endpoint.firstSeenAt = Instant.now();
        endpoint.lastSeenAt = Instant.now();
        return endpoint;
    }
}
