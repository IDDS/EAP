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

    @Test
    void nativeTaskLeaseIgnoresMissingHeartbeatAndPreservesExplicitOffline() {
        Agent agent = new Agent();agent.setCode("LEASE-NATIVE");agent.setName("Native lease test");
        agent.setOwnerDepartment("test");agent.setOwnerUser("test");agent.setCategory("test");
        agent = agents.saveAndFlush(agent);
        var task = runtime(agent.getId(), "lease-native-task", RuntimeStatus.ONLINE, 0);
        task.protocol = "SUBSTRATE_TASK";task.lastHeartbeatAt = null;
        var degraded = runtime(agent.getId(), "lease-native-task-degraded", RuntimeStatus.DEGRADED, 0);
        degraded.protocol = "SUBSTRATE_TASK";degraded.lastHeartbeatAt = Instant.now().minusSeconds(3600);
        var managed = runtime(agent.getId(), "lease-native-managed", RuntimeStatus.ONLINE, 0);
        managed.protocol = "SUBSTRATE";managed.lastHeartbeatAt = null;
        var observed = runtime(agent.getId(), "lease-native-observed", RuntimeStatus.ONLINE, 0);
        observed.protocol = "SUBSTRATE_OBSERVED";observed.lastHeartbeatAt = null;
        var offline = runtime(agent.getId(), "lease-native-offline", RuntimeStatus.OFFLINE, 0);
        offline.protocol = "SUBSTRATE_TASK";offline.lastHeartbeatAt = null;
        var http = runtime(agent.getId(), "lease-native-http-stale", RuntimeStatus.ONLINE, 0);
        http.lastHeartbeatAt = null;
        repository.saveAllAndFlush(List.of(task, degraded, managed, observed, offline, http));
        // Initial persistence fills a missing ordinary heartbeat. Clear it after insert to test the real NULL row.
        for (var missing : List.of(task, managed, observed, offline, http)) missing.lastHeartbeatAt = null;
        repository.saveAllAndFlush(List.of(task, managed, observed, offline, http));
        scheduler.expireSilentRuntimes();
        assertThat(repository.findByInstanceKey(task.instanceKey).orElseThrow().status).isEqualTo(RuntimeStatus.ONLINE);
        assertThat(repository.findByInstanceKey(degraded.instanceKey).orElseThrow().status).isEqualTo(RuntimeStatus.DEGRADED);
        assertThat(repository.findByInstanceKey(managed.instanceKey).orElseThrow().status).isEqualTo(RuntimeStatus.ONLINE);
        assertThat(repository.findByInstanceKey(observed.instanceKey).orElseThrow().status).isEqualTo(RuntimeStatus.ONLINE);
        assertThat(repository.findByInstanceKey(offline.instanceKey).orElseThrow().status).isEqualTo(RuntimeStatus.OFFLINE);
        assertThat(repository.findByInstanceKey(http.instanceKey).orElseThrow().status).isEqualTo(RuntimeStatus.OFFLINE);
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
