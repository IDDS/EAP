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

    private DiscoveryEndpoint receiptEndpoint() {
        DiscoveryEndpoint endpoint = new DiscoveryEndpoint();
        endpoint.id = 701L;
        endpoint.endpointKey = "receipt-host";
        endpoint.hostName = "receipt-host";
        when(endpointRepository.findByEndpointKey(endpoint.endpointKey)).thenReturn(Optional.of(endpoint));
        when(runRepository.save(any())).thenAnswer(call -> {
            DiscoveryRun run = call.getArgument(0);
            run.id = 702L;
            return run;
        });
        return endpoint;
    }

    private void knownReceiptIdentity(boolean mcp) throws Exception {
        DiscoveredAgent existing = new DiscoveredAgent();
        existing.id = 703L;
        existing.fingerprint = "independent-inventory";
        existing.name = "Independent inventory";
        existing.assetType = mcp ? DiscoveryAssetType.MCP_CONFIGURATION : DiscoveryAssetType.AGENT;
        existing.sourceTypes = "PROCESS";
        existing.riskLevel = RiskLevel.LOW;
        existing.managedAgentId = mcp ? null : 900L;
        DiscoveryIdentityLink link = new DiscoveryIdentityLink();
        link.discoveredAgent = existing;
        when(identityLinkRepository.findByIdentityKey(sha256("identity|known-workload|receipt-host")))
                .thenReturn(Optional.of(link));
        when(agentRepository.save(any())).thenAnswer(call -> call.getArgument(0));
        if (!mcp) when(managedAgentRepository.existsById(900L)).thenReturn(true);
    }

    private HostObservationService.HostObservationDtos.HostNetworkObservation receiptFlow(String identity, boolean mcp, int port) {
        return new HostObservationService.HostObservationDtos.HostNetworkObservation(
                mcp ? "mcp-client" : "agent-worker", 704L, "/fixture/agent-worker", null, null, null,
                null, 0, "127.0.0.1", port, null, "OUTBOUND", null,
                null, null, null, null, null, identity,
                HostObservationService.HostObservationDtos.NetworkObservationKind.CONNECT_ATTEMPT);
    }

    private AgentDiscoveryDtos.HostObservationResponse receiptIngest(DiscoveryEndpoint endpoint,
            List<HostObservationService.HostObservationDtos.HostNetworkObservation> flows) {
        return new HostObservationService(new HostObservationProperties(), service)
                .ingestForEndpoint(endpoint, HostObservationSensorType.EBPF, "eap-ebpf-bpftrace/1.1.1", flows);
    }

    @Test
    void hostReceiptDoesNotReportUnassociatedCandidateAsRecorded() {
        var response = receiptIngest(receiptEndpoint(), List.of(receiptFlow("unknown-workload", false, 41001)));
        assertThat(response.observationsInspected()).isEqualTo(1);
        assertThat(response.observationsRecorded()).isZero();
        assertThat(response.agentsDiscovered()).isZero();
        assertThat(response.warnings()).anyMatch(message -> message.contains("1") && message.contains("身份"));
        verify(observationRepository, org.mockito.Mockito.never()).save(any());
        verify(agentRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void hostReceiptCountsSavedRowsAndDistinctMatchedAgents() throws Exception {
        var endpoint = receiptEndpoint();
        knownReceiptIdentity(false);
        var response = receiptIngest(endpoint, List.of(receiptFlow("known-workload", false, 41001),
                receiptFlow("unknown-workload", false, 41002), receiptFlow("known-workload", false, 41003)));
        assertThat(response.observationsInspected()).isEqualTo(3);
        assertThat(response.observationsRecorded()).isEqualTo(2);
        assertThat(response.agentsDiscovered()).isEqualTo(1);
        assertThat(response.mcpServersDiscovered()).isZero();
        verify(observationRepository, org.mockito.Mockito.times(2)).save(any());
        var runs = ArgumentCaptor.forClass(DiscoveryRun.class);
        verify(runRepository, org.mockito.Mockito.times(2)).save(runs.capture());
        assertThat(runs.getValue().observationsCount).isEqualTo(3);
        assertThat(runs.getValue().discoveredCount).isEqualTo(1);
    }

    @Test
    void hostReceiptCountsDistinctCanonicalMcpAssets() throws Exception {
        var endpoint = receiptEndpoint();
        knownReceiptIdentity(true);
        var response = receiptIngest(endpoint, List.of(receiptFlow("known-workload", true, 41001),
                receiptFlow("known-workload", true, 41002), receiptFlow("unknown-workload", true, 41003)));
        assertThat(response.observationsRecorded()).isEqualTo(2);
        assertThat(response.agentsDiscovered()).isZero();
        assertThat(response.mcpServersDiscovered()).isEqualTo(1);
        verify(observationRepository, org.mockito.Mockito.times(2)).save(any());
    }

    @Test
    void hostReceiptDoesNotAcknowledgePersistenceFailure() throws Exception {
        var endpoint = receiptEndpoint();
        knownReceiptIdentity(false);
        when(observationRepository.save(any())).thenThrow(new IllegalStateException("fixture write failed"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> receiptIngest(endpoint,
                List.of(receiptFlow("known-workload", false, 41001))))
                .isInstanceOf(IllegalStateException.class).hasMessage("fixture write failed");
    }

    @Test
    void hostReceiptCountsOnlyNormalizedAgentCandidates() {
        var endpoint = new DiscoveryEndpoint();
        endpoint.endpointKey = "receipt-host";
        endpoint.hostName = "receipt-host";
        var ignored = new HostObservationService.HostObservationDtos.HostNetworkObservation(
                "tat_agent", 704L, "/qcloud/tat_agent", null, null, null,
                null, 0, "127.0.0.1", 41001, null, "OUTBOUND", null,
                null, null, null, null, null, "not-an-agent",
                HostObservationService.HostObservationDtos.NetworkObservationKind.CONNECT_ATTEMPT);
        var response = receiptIngest(endpoint, List.of(ignored));
        assertThat(response.observationsInspected()).isEqualTo(1);
        assertThat(response.observationsRecorded()).isZero();
        assertThat(response.agentsDiscovered()).isZero();
        verify(runRepository, org.mockito.Mockito.never()).save(any());
    }

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
