package com.eap.platform.registry;

import com.eap.platform.agent.Agent;
import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.agent.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentRegistryDispatchTests {
    @Mock private AgentRepository agentRepository;
    @Mock private AgentCapabilityRepository capabilityRepository;
    @Mock private AgentRuntimeInstanceRepository runtimeRepository;

    private AgentRegistryService service;
    private AgentRuntimeInstance runtime;

    @BeforeEach
    void setUp() {
        service = new AgentRegistryService(agentRepository, capabilityRepository, runtimeRepository, new RuntimeLeaseProperties());
        Agent agent = new Agent();
        ReflectionTestUtils.setField(agent, "id", 1L);
        agent.setCode("AUTO-HERMES");
        agent.setName("Hermes Agent");
        agent.setOwnerDepartment("自动发现");
        agent.setLifecycleStatus(AgentLifecycleStatus.ACTIVE);
        agent.setRiskLevel(RiskLevel.MEDIUM);

        AgentCapability capability = new AgentCapability();
        capability.agentId = 1L;
        capability.capability = "research.assist";
        capability.name = "Research";

        runtime = new AgentRuntimeInstance();
        runtime.agentId = 1L;
        runtime.instanceKey = "hermes-a2a-1";
        runtime.endpoint = "https://agents.example/hermes";
        runtime.protocol = "A2A";
        runtime.status = RuntimeStatus.ONLINE;
        runtime.lastHeartbeatAt = Instant.now();

        when(capabilityRepository.findByCapability("research.assist")).thenReturn(List.of(capability));
        when(runtimeRepository.findByAgentId(1L)).thenReturn(List.of(runtime));
        when(agentRepository.findById(1L)).thenReturn(Optional.of(agent));
    }

    @Test
    void excludesA2aRuntimeUntilDispatchAuthenticationIsConfigured() {
        runtime.dispatchAuthenticated = false;

        var response = service.discover(new AgentRegistryDtos.DiscoveryRequest(
                "research.assist", RiskLevel.HIGH, null, "A2A"));

        assertThat(response.candidates()).isEmpty();
    }

    @Test
    void schedulesOnlineA2aRuntimeAfterDispatchAuthentication() {
        runtime.dispatchAuthenticated = true;

        var response = service.discover(new AgentRegistryDtos.DiscoveryRequest(
                "research.assist", RiskLevel.HIGH, null, "A2A"));

        assertThat(response.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.agentId()).isEqualTo(1L);
            assertThat(candidate.constraints()).contains("a2a-authenticated");
        });
    }
    @Test
    void excludesExpiredOnlineRuntimeBeforeSchedulerRuns() {
        runtime.dispatchAuthenticated = true;
        runtime.lastHeartbeatAt = Instant.now().minusSeconds(181);
        assertThat(service.discover(new AgentRegistryDtos.DiscoveryRequest(
                "research.assist", RiskLevel.HIGH, null, "A2A")).candidates()).isEmpty();
    }

}
