package com.eap.platform.agentops;

import com.eap.platform.agent.Agent;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.registry.AgentRegistryDtos;
import com.eap.platform.registry.AgentRegistryService;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.registry.runtime.heartbeat-ttl-seconds=60"})
@ActiveProfiles("test")
@Transactional
class AgentOpsReadModelTests {
    @Autowired AgentOpsService service;
    @Autowired AgentRepository agents;
    @Autowired AgentWorkItemRepository workItems;
    @Autowired AgentWorkItemEventRepository events;
    @Autowired WorkItemService synchronization;
    @Autowired AgentOpsSignalSyncStateRepository syncStates;
    @Autowired AgentRegistryService registry;
    @Autowired AgentRuntimeInstanceRepository runtimes;
    @Autowired JdbcTemplate jdbc;

    @Test
    void repeatedDashboardReadsDoNotWriteSignalsOrInventFreshness() {
        var state = syncStates.findById(1L).orElseThrow();
        state.lastCompletedAt = null;
        syncStates.saveAndFlush(state);
        long itemCount = workItems.count();
        long eventCount = events.count();
        var first = service.dashboard(24, null);
        var second = service.dashboard(24, null);
        assertThat(first.lastSignalSyncAt()).isNull();
        assertThat(second.signalSyncFreshness()).isEqualTo("NO_DATA");
        assertThat(second.metrics()).filteredOn(metric -> metric.key().equals("workitems.open"))
                .allSatisfy(metric -> { assertThat(metric.measuredAt()).isNull(); assertThat(metric.freshness()).isEqualTo("NO_DATA"); });
        assertThat(workItems.count()).isEqualTo(itemCount);
        assertThat(events.count()).isEqualTo(eventCount);

        synchronization.synchronizeSignals();
        Instant syncedAt = syncStates.findById(1L).orElseThrow().lastCompletedAt;
        assertThat(syncedAt).isNotNull();
        assertThat(service.dashboard(24, null).lastSignalSyncAt()).isEqualTo(syncedAt);
        state.lastCompletedAt = Instant.now().minusSeconds(7200);
        syncStates.saveAndFlush(state);
        assertThat(service.dashboard(24, null).signalSyncFreshness()).isEqualTo("STALE");
    }

    @Test
    void coverageIncludesQuietAgentOutsideGlobalTop500AndExcludesOldWindow() {
        Agent quiet = agent("quiet");
        Agent busy = agent("busy");
        Agent old = agent("old");
        Instant now = Instant.now();
        List<Object[]> rows = new ArrayList<>();
        rows.add(span(quiet.getId(), now.minusSeconds(3600)));
        rows.add(span(old.getId(), now.minusSeconds(172800)));
        for (int i = 0; i < 501; i++) rows.add(span(busy.getId(), now.minusSeconds(i)));
        jdbc.batchUpdate("insert into trace_span_observations "
                + "(agent_id,trace_id,span_id,endpoint_key,span_name,span_kind,duration_ms,observed_at,created_at) "
                + "values (?,?,?,?,?,?,?,?,?)", rows);

        jdbc.update("insert into agent_tasks (agent_id,task_type,status,created_at,updated_at) values (?,?,?,?,?)",
                quiet.getId(), "test", "SUCCEEDED", Timestamp.from(now.minusSeconds(172800)), Timestamp.from(now.minusSeconds(60)));
        jdbc.update("insert into agent_tasks (agent_id,task_type,status,created_at,updated_at) values (?,?,?,?,?)",
                old.getId(), "test", "SUCCEEDED", Timestamp.from(now.minusSeconds(172800)), Timestamp.from(now.minusSeconds(172800)));
        var quietState = service.getAgent(quiet.getId(), 24);
        assertThat(quietState.coverageSignals()).contains("TRACE", "TASKS");
        assertThat(quietState.observationCoverage()).isEqualTo(ObservationCoverage.PARTIAL);
        assertThat(service.getAgent(old.getId(), 24).coverageSignals()).doesNotContain("TRACE", "TASKS");
        var page = service.listAgents(24, Integer.MAX_VALUE, 100, null, null, null, null, null, null);
        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void healthAndOnlineCountUseSameLeaseAsDispatchBeforeScheduler() {
        Agent agent = agent("lease");
        String key = "ops-lease-" + UUID.randomUUID();
        registry.registerRuntime(agent.getId(), new AgentRegistryDtos.RuntimeInstanceRequest(key,
                "https://runtime.example", "HTTP", false));
        var runtime = runtimes.findByInstanceKey(key).orElseThrow();
        ReflectionTestUtils.setField(runtime, "lastHeartbeatAt", Instant.now().minusSeconds(90));
        runtimes.saveAndFlush(runtime);
        var state = service.getAgent(agent.getId(), 24);
        assertThat(state.operationalHealth()).isEqualTo(OperationalHealth.OFFLINE);
        assertThat(state.onlineRuntimeCount()).isZero();
        synchronization.synchronizeSignals();
        assertThat(workItems.findByItemKey("RUNTIME:" + key).orElseThrow().itemType).isEqualTo(WorkItemType.RUNTIME_OFFLINE);
    }

    @Test
    void detailReadsOnlyOpenItemsForRequestedAgent() {
        Agent first = agent("items-first");
        Agent other = agent("items-other");
        item(first.getId(), WorkItemStatus.OPEN);
        item(first.getId(), WorkItemStatus.CLOSED);
        item(other.getId(), WorkItemStatus.OPEN);
        assertThat(service.getAgent(first.getId(), 24).openWorkItemCount()).isEqualTo(1);
    }

    private void item(Long agentId, WorkItemStatus status) {
        var item = new AgentWorkItem();
        item.itemKey = UUID.randomUUID().toString();
        item.agentId = agentId;
        item.itemType = WorkItemType.GENERAL;
        item.title = "test";
        item.severity = WorkItemSeverity.P3;
        item.status = status;
        item.sourceType = "TEST";
        item.evidenceBasis = EvidenceBasis.MEASURED;
        workItems.saveAndFlush(item);
    }

    private Object[] span(Long agentId, Instant observedAt) {
        return new Object[]{agentId, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "test-endpoint",
                "test-span", "AGENT", 0L, Timestamp.from(observedAt), Timestamp.from(observedAt)};
    }

    private Agent agent(String name) {
        var agent = new Agent();
        agent.setCode("ops-" + UUID.randomUUID());
        agent.setName(name);
        agent.setOwnerDepartment("test");
        agent.setOwnerUser("test");
        agent.setCategory("test");
        return agents.saveAndFlush(agent);
    }
}
