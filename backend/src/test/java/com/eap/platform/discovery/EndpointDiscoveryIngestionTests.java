package com.eap.platform.discovery;

import com.eap.platform.agent.RiskLevel;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.agent.Agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndpointDiscoveryIngestionTests {
    @Mock
    private LocalAgentScanner scanner;
    @Mock
    private DiscoveredAgentRepository agentRepository;
    @Mock
    private DiscoveryEndpointRepository endpointRepository;
    @Mock
    private DiscoveryRunRepository runRepository;
    @Mock
    private DiscoveryObservationRepository observationRepository;
    @Mock
    private DiscoveryIdentityLinkRepository identityLinkRepository;
    @Mock
    private AgentRepository managedAgentRepository;

    private AgentDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new AgentDiscoveryService(
                new DiscoveryProperties(), scanner, agentRepository, endpointRepository,
                runRepository, observationRepository, identityLinkRepository, managedAgentRepository, new ObjectMapper()
        );
        lenient().when(managedAgentRepository.existsByCode(any())).thenReturn(false);
        lenient().when(managedAgentRepository.save(any())).thenAnswer(invocation -> {
            Agent managed = invocation.getArgument(0);
            ReflectionTestUtils.setField(managed, "id", 900L);
            return managed;
        });
    }

    @Test
    void ingestsEndpointEvidenceAndRedactsSensitiveValues() {
        DiscoveryEndpoint endpoint = new DiscoveryEndpoint();
        endpoint.id = 10L;
        endpoint.endpointKey = "macbook-dev-01";
        endpoint.hostName = "dev-mac.local";
        endpoint.accountName = "developer";
        endpoint.firstSeenAt = Instant.now();
        endpoint.lastSeenAt = Instant.now();

        when(endpointRepository.findByEndpointKey("macbook-dev-01")).thenReturn(Optional.of(endpoint));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            DiscoveryRun run = invocation.getArgument(0);
            run.id = run.id == null ? 20L : run.id;
            return run;
        });
        when(agentRepository.findByFingerprint(any())).thenReturn(Optional.empty());
        when(agentRepository.save(any())).thenAnswer(invocation -> {
            DiscoveredAgent agent = invocation.getArgument(0);
            agent.id = 30L;
            return agent;
        });
        when(observationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AgentDiscoveryDtos.EndpointEvidenceRequest observation = new AgentDiscoveryDtos.EndpointEvidenceRequest(
                "collector-local-fingerprint",
                "Codex @ dev-mac.local",
                "OpenAI",
                "Codex",
                null,
                DiscoveryAssetType.AGENT,
                DiscoverySourceType.PROCESS,
                "/Applications/Codex.app",
                "developer",
                "codex",
                "/Applications/Codex.app/Contents/MacOS/Codex",
                true,
                false,
                0,
                92,
                RiskLevel.LOW,
                "RUNNING_PROCESS",
                Map.of("apiToken", "must-not-be-stored", "credentialReferenceDetected", true)
        );

        AgentDiscoveryDtos.EndpointIngestionResponse response = service.ingestEndpoint(
                new AgentDiscoveryDtos.EndpointObservationRequest("macbook-dev-01", List.of(observation))
        );

        assertThat(response.run().triggerType()).isEqualTo(DiscoveryTriggerType.ENDPOINT);
        assertThat(response.run().endpointKey()).isEqualTo("macbook-dev-01");
        assertThat(response.run().observationsCount()).isEqualTo(1);

        ArgumentCaptor<DiscoveredAgent> agentCaptor = ArgumentCaptor.forClass(DiscoveredAgent.class);
        verify(agentRepository).save(agentCaptor.capture());
        assertThat(agentCaptor.getValue().riskLevel).isEqualTo(RiskLevel.CRITICAL);
        assertThat(agentCaptor.getValue().hostName).isEqualTo("dev-mac.local");
        assertThat(agentCaptor.getValue().status).isEqualTo(DiscoveryAssetStatus.MANAGED);
        assertThat(agentCaptor.getValue().managedAgentId).isEqualTo(900L);

        ArgumentCaptor<DiscoveryObservation> observationCaptor = ArgumentCaptor.forClass(DiscoveryObservation.class);
        verify(observationRepository).save(observationCaptor.capture());
        assertThat(observationCaptor.getValue().evidenceJson).contains("[REDACTED]");
        assertThat(observationCaptor.getValue().evidenceJson).doesNotContain("must-not-be-stored");
    }

    @Test
    void normalizesMicrosoftDefenderAgentsInfoIntoExternalEvidence() {
        DiscoveryEndpoint endpoint = new DiscoveryEndpoint();
        endpoint.id = 40L;
        endpoint.endpointKey = "defender-device-01";
        endpoint.hostName = "win-dev-01";
        endpoint.firstSeenAt = Instant.now();
        endpoint.lastSeenAt = Instant.now();

        when(endpointRepository.findByEndpointKey("defender-device-01"))
                .thenReturn(Optional.empty(), Optional.of(endpoint));
        when(endpointRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            DiscoveryRun run = invocation.getArgument(0);
            run.id = run.id == null ? 50L : run.id;
            return run;
        });
        when(agentRepository.findByFingerprint(any())).thenReturn(Optional.empty());
        when(agentRepository.save(any())).thenAnswer(invocation -> {
            DiscoveredAgent agent = invocation.getArgument(0);
            agent.id = 60L;
            return agent;
        });
        when(observationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AgentDiscoveryDtos.EndpointIngestionResponse response = service.ingestMicrosoftDefender(
                new AgentDiscoveryDtos.MicrosoftDefenderImportRequest(
                        "defender-device-01",
                        "win-dev-01",
                        "developer",
                        "Windows 11",
                        List.of(Map.of(
                                "AgentId", "agent-123",
                                "DisplayName", "Cursor",
                                "Vendor", "Cursor",
                                "DeviceName", "win-dev-01",
                                "AutoApprove", true,
                                "TrustedProcess", false,
                                "LocalMcp", true,
                                "ExposureGraphEdgeCount", 3
                        ))
                )
        );

        assertThat(response.run().triggerType()).isEqualTo(DiscoveryTriggerType.ENDPOINT);
        ArgumentCaptor<DiscoveredAgent> agentCaptor = ArgumentCaptor.forClass(DiscoveredAgent.class);
        verify(agentRepository).save(agentCaptor.capture());
        assertThat(agentCaptor.getValue().sourceTypes).contains("EXTERNAL");
        assertThat(agentCaptor.getValue().riskLevel).isEqualTo(RiskLevel.CRITICAL);
        assertThat(agentCaptor.getValue().mcpServerCount).isEqualTo(1);
    }

    @Test
    void reconcilesFullSnapshotUsingEndpointScopedFingerprint() throws Exception {
        DiscoveredAgent existing = new DiscoveredAgent();
        existing.fingerprint = sha256("endpoint|endpoint-1|raw-fingerprint");
        existing.lastSeenAt = Instant.now();
        existing.availabilityStatus = "OFFLINE";
        existing.missingSnapshotCount = 2;
        when(agentRepository.findAllByLastEndpointKey("endpoint-1")).thenReturn(List.of(existing));

        service.reconcileEndpointSnapshot("endpoint-1", java.util.Set.of("raw-fingerprint"));

        assertThat(existing.availabilityStatus).isEqualTo("ONLINE");
        assertThat(existing.missingSnapshotCount).isZero();
    }

    @Test
    void marksAssetOfflineAfterThreeConsecutiveMissingFullSnapshots() {
        DiscoveredAgent existing = new DiscoveredAgent();
        existing.fingerprint = "missing-fingerprint";
        existing.lastSeenAt = Instant.now();
        existing.availabilityStatus = "ONLINE";
        existing.missingSnapshotCount = 0;
        when(agentRepository.findAllByLastEndpointKey("endpoint-1")).thenReturn(List.of(existing));

        service.reconcileEndpointSnapshot("endpoint-1", java.util.Set.of());
        service.reconcileEndpointSnapshot("endpoint-1", java.util.Set.of());
        assertThat(existing.availabilityStatus).isEqualTo("ONLINE");
        service.reconcileEndpointSnapshot("endpoint-1", java.util.Set.of());

        assertThat(existing.availabilityStatus).isEqualTo("OFFLINE");
        assertThat(existing.missingSnapshotCount).isEqualTo(3);
    }

    @Test
    void doesNotCreateAgentFromPassiveModelNetworkFlowAlone() {
        DiscoveryEndpoint endpoint = new DiscoveryEndpoint();
        endpoint.endpointKey = "observer-1";
        endpoint.hostName = "worker-1";
        endpoint.firstSeenAt = Instant.now();
        endpoint.lastSeenAt = Instant.now();
        when(endpointRepository.findByEndpointKey("observer-1")).thenReturn(Optional.of(endpoint));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            DiscoveryRun run = invocation.getArgument(0);
            run.id = 100L;
            return run;
        });
        when(agentRepository.findByFingerprint(any())).thenReturn(Optional.empty());
        when(identityLinkRepository.findByIdentityKey(any())).thenReturn(Optional.empty());

        AgentDiscoveryDtos.EndpointIngestionResponse response = service.ingestEndpoint(
                new AgentDiscoveryDtos.EndpointObservationRequest("observer-1", List.of(
                        new AgentDiscoveryDtos.EndpointEvidenceRequest(
                                "flow-1", "python -> api.openai.com", "OpenAI", "Model Network Client", null,
                                DiscoveryAssetType.AGENT, DiscoverySourceType.HOST_OBSERVER,
                                "host-observer://worker-1/python/api.openai.com", "worker", "python", "/usr/bin/python",
                                null, null, 0, 80, RiskLevel.LOW, "PROCESS_NETWORK_SCAN_NETWORK_FLOW",
                                Map.of("tlsServerName", "api.openai.com")
                        )
                ))
        );

        assertThat(response.run().discoveredCount()).isZero();
        verify(managedAgentRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void passiveHostEvidenceDoesNotOverwriteCanonicalDockerIdentity() throws Exception {
        DiscoveryEndpoint endpoint = new DiscoveryEndpoint();
        endpoint.id = 70L;
        endpoint.endpointKey = "host-observer-01";
        endpoint.hostName = "worker-01";
        endpoint.firstSeenAt = Instant.now();
        endpoint.lastSeenAt = Instant.now();

        DiscoveredAgent existing = new DiscoveredAgent();
        existing.id = 80L;
        existing.fingerprint = "docker-fingerprint";
        existing.name = "HelloBoss @ worker-01";
        existing.vendor = "深圳矩维智能";
        existing.product = "HelloBoss";
        existing.assetType = DiscoveryAssetType.AGENT;
        existing.processName = "boss-agent-backend";
        existing.executablePath = "boss-agent-backend:latest";
        existing.sourceTypes = "DOCKER";
        existing.confidence = 98;
        existing.riskLevel = RiskLevel.MEDIUM;

        DiscoveryIdentityLink link = new DiscoveryIdentityLink();
        link.identityKey = "identity-key";
        link.discoveredAgent = existing;

        when(endpointRepository.findByEndpointKey("host-observer-01")).thenReturn(Optional.of(endpoint));
        when(runRepository.save(any())).thenAnswer(invocation -> {
            DiscoveryRun run = invocation.getArgument(0);
            run.id = 90L;
            return run;
        });
        when(agentRepository.findByFingerprint(any())).thenReturn(Optional.empty());
        String expectedIdentityKey = sha256("identity|docker:worker-01:boss-agent:backend|worker-01");
        when(identityLinkRepository.findByIdentityKey(expectedIdentityKey)).thenReturn(Optional.of(link));
        when(agentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(observationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.ingestEndpoint(new AgentDiscoveryDtos.EndpointObservationRequest(
                "host-observer-01",
                List.of(new AgentDiscoveryDtos.EndpointEvidenceRequest(
                        "host-flow-fingerprint", "boss-agent-backend", "DeepSeek", "Model Network Client", null,
                        DiscoveryAssetType.AGENT, DiscoverySourceType.HOST_OBSERVER,
                        "host-observer://process-network-scan/worker-01/boss-agent-backend/api.deepseek.com",
                        "svc-agent", "boss-agent-backend", "/usr/bin/python", null, null, 0, 90,
                        RiskLevel.LOW, "PROCESS_NETWORK_SCAN_NETWORK_FLOW",
                        Map.of("identityKey", "docker:worker-01:boss-agent:backend", "sensorType", "PROCESS_NETWORK_SCAN")
                ))
        ));

        assertThat(existing.fingerprint).isEqualTo("docker-fingerprint");
        assertThat(existing.name).isEqualTo("HelloBoss @ worker-01");
        assertThat(existing.vendor).isEqualTo("深圳矩维智能");
        assertThat(existing.product).isEqualTo("HelloBoss");
        assertThat(existing.processName).isEqualTo("boss-agent-backend");
        assertThat(existing.sourceTypes).contains("DOCKER", "HOST_OBSERVER");
        verify(identityLinkRepository, atLeastOnce()).findByIdentityKey(expectedIdentityKey);
    }

    private String sha256(String value) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }
}
