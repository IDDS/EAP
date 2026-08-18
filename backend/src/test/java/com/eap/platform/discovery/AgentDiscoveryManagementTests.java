package com.eap.platform.discovery;

import com.eap.platform.agent.Agent;
import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.agent.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentDiscoveryManagementTests {
    @Mock private LocalAgentScanner scanner;
    @Mock private DiscoveredAgentRepository discoveredRepository;
    @Mock private DiscoveryEndpointRepository endpointRepository;
    @Mock private DiscoveryRunRepository runRepository;
    @Mock private DiscoveryObservationRepository observationRepository;
    @Mock private DiscoveryIdentityLinkRepository identityLinkRepository;
    @Mock private AgentRepository agentRepository;

    private AgentDiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new AgentDiscoveryService(
                new DiscoveryProperties(), scanner, discoveredRepository, endpointRepository,
                runRepository, observationRepository, identityLinkRepository, agentRepository, new ObjectMapper()
        );
    }

    @Test
    void createsFormalAgentAndLinksDiscoveredAsset() {
        DiscoveredAgent discovered = discoveredAgent();
        when(discoveredRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(discovered));
        when(agentRepository.existsByCode(any())).thenReturn(false);
        when(agentRepository.save(any())).thenAnswer(invocation -> {
            Agent agent = invocation.getArgument(0);
            ReflectionTestUtils.setField(agent, "id", 88L);
            return agent;
        });

        AgentDiscoveryDtos.DiscoveredAgentResponse response = service.manage(1L);

        assertThat(response.status()).isEqualTo(DiscoveryAssetStatus.MANAGED);
        assertThat(response.managedAgentId()).isEqualTo(88L);
        ArgumentCaptor<Agent> captor = ArgumentCaptor.forClass(Agent.class);
        verify(agentRepository).save(captor.capture());
        Agent managed = captor.getValue();
        assertThat(managed.getName()).isEqualTo("HelloBoss");
        assertThat(managed.getLifecycleStatus()).isEqualTo(AgentLifecycleStatus.ACTIVE);
        assertThat(managed.getOwnerDepartment()).isEqualTo("自动发现");
        assertThat(managed.getOwnerUser()).isEqualTo("EAP Discovery");
        assertThat(managed.getDescription()).contains("VM-0-15-ubuntu", "boss-agent-backend", "DOCKER");
    }

    @Test
    void returnsExistingLinkWithoutCreatingDuplicateAgent() {
        DiscoveredAgent discovered = discoveredAgent();
        discovered.managedAgentId = 88L;
        discovered.status = DiscoveryAssetStatus.CONFIRMED;
        when(discoveredRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(discovered));
        when(agentRepository.existsById(88L)).thenReturn(true);

        AgentDiscoveryDtos.DiscoveredAgentResponse response = service.manage(1L);

        assertThat(response.status()).isEqualTo(DiscoveryAssetStatus.MANAGED);
        assertThat(response.managedAgentId()).isEqualTo(88L);
        verify(agentRepository, never()).save(any());
    }

    private DiscoveredAgent discoveredAgent() {
        DiscoveredAgent discovered = new DiscoveredAgent();
        discovered.id = 1L;
        discovered.fingerprint = "b0a26f1234567890";
        discovered.name = "HelloBoss @ VM-0-15-ubuntu";
        discovered.vendor = "深圳矩维智能";
        discovered.product = "HelloBoss";
        discovered.assetType = DiscoveryAssetType.AGENT;
        discovered.status = DiscoveryAssetStatus.DISCOVERED;
        discovered.riskLevel = RiskLevel.MEDIUM;
        discovered.confidence = 94;
        discovered.sourceTypes = "DOCKER";
        discovered.hostName = "VM-0-15-ubuntu";
        discovered.accountName = "ubuntu";
        discovered.processName = "boss-agent-backend";
        discovered.executablePath = "boss-agent-backend";
        discovered.mcpServerCount = 0;
        discovered.evidenceCount = 3;
        return discovered;
    }
}
