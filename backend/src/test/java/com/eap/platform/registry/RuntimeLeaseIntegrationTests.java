package com.eap.platform.registry;

import com.eap.platform.agent.Agent;
import com.eap.platform.agent.AgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.registry.runtime.heartbeat-ttl-seconds=60"})
@ActiveProfiles("test")
@Transactional
class RuntimeLeaseIntegrationTests {
    @Autowired AgentRuntimeInstanceRepository repository;
    @Autowired AgentRepository agents;
    @Autowired AgentRegistryService registry;
    @Autowired RuntimeLeaseProperties properties;
    @Autowired RuntimeLeaseScheduler scheduler;

    @Test
    void expiredRuntimesAreNotSelectedAndHeartbeatRestoresLease() {
        Agent agent = new Agent();
        agent.setCode("LEASE-TEST");
        agent.setName("Lease test");
        agent.setOwnerDepartment("test");
        agent.setOwnerUser("test");
        agent.setCategory("test");
        agent = agents.saveAndFlush(agent);
        AgentRuntimeInstance stale = runtime(agent.getId(), "lease-stale", RuntimeStatus.ONLINE, 0);
        AgentRuntimeInstance degraded = runtime(agent.getId(), "lease-degraded", RuntimeStatus.DEGRADED, 10);
        AgentRuntimeInstance fresh = runtime(agent.getId(), "lease-fresh", RuntimeStatus.ONLINE, 30);
        stale.lastHeartbeatAt = Instant.now().minusSeconds(120);
        degraded.lastHeartbeatAt = Instant.now().minusSeconds(120);
        repository.saveAllAndFlush(List.of(stale, degraded, fresh));

        // Selection and list views must be correct even before the scheduler runs.
        assertThat(repository.findFirstByAgentIdAndStatusAndLastHeartbeatAtAfterOrderByLoadScoreAsc(
                agent.getId(), RuntimeStatus.ONLINE, properties.cutoff()).orElseThrow().instanceKey).isEqualTo("lease-fresh");
        assertThat(registry.listRuntimeInstances(agent.getId())).filteredOn(item -> !item.instanceKey().equals("lease-fresh"))
                .allSatisfy(item -> assertThat(item.status()).isEqualTo(RuntimeStatus.OFFLINE));
        scheduler.expireSilentRuntimes();
        assertThat(repository.findByInstanceKey("lease-stale").orElseThrow().status).isEqualTo(RuntimeStatus.OFFLINE);
        assertThat(repository.findByInstanceKey("lease-degraded").orElseThrow().status).isEqualTo(RuntimeStatus.OFFLINE);
        registry.heartbeat("lease-stale", new AgentRegistryDtos.HeartbeatRequest(RuntimeStatus.ONLINE, 0));
        assertThat(repository.findFirstByAgentIdAndStatusAndLastHeartbeatAtAfterOrderByLoadScoreAsc(
                agent.getId(), RuntimeStatus.ONLINE, properties.cutoff()).orElseThrow().instanceKey).isEqualTo("lease-stale");
    }

    @Test
    void exactExpiryBoundaryAndMissingHeartbeatAreOffline() {
        AgentRuntimeInstance instance = new AgentRuntimeInstance();
        Instant cutoff = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(properties.effectiveStatus(instance, cutoff)).isEqualTo(RuntimeStatus.OFFLINE);
        instance.lastHeartbeatAt = cutoff;
        assertThat(properties.effectiveStatus(instance, cutoff)).isEqualTo(RuntimeStatus.OFFLINE);
        instance.lastHeartbeatAt = cutoff.plusNanos(1);
        assertThat(properties.effectiveStatus(instance, cutoff)).isEqualTo(RuntimeStatus.ONLINE);
    }

    private AgentRuntimeInstance runtime(Long agentId, String key, RuntimeStatus status, int load) {
        AgentRuntimeInstance instance = new AgentRuntimeInstance();
        instance.agentId = agentId;
        instance.instanceKey = key;
        instance.endpoint = "https://runtime.example";
        instance.protocol = "HTTP";
        instance.status = status;
        instance.loadScore = load;
        instance.lastHeartbeatAt = Instant.now();
        return instance;
    }
}
