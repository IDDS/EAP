package com.eap.platform.discovery;

import com.eap.platform.agent.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private AgentDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new AgentDiscoveryService(
                new DiscoveryProperties(), scanner, agentRepository, endpointRepository,
                runRepository, observationRepository, identityLinkRepository, new ObjectMapper()
        );
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
}
