package com.eap.platform.agentops;

import com.eap.platform.agent.Agent;
import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.agent.RiskLevel;
import com.eap.platform.config.SecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentOpsIntegrationTests {
    @Autowired
    private AgentRepository agentRepository;
    @Autowired
    private AgentWorkItemRepository workItemRepository;
    @Autowired
    private WorkItemService workItemService;
    @Autowired
    private AgentOpsService agentOpsService;
    @Autowired
    private SecurityProperties securityProperties;

    @Test
    void createsTraceableSignalsForUnownedActiveAgentWithoutRuntime() {
        Agent agent = new Agent();
        agent.setCode("TEST-UNOWNED-ACTIVE");
        agent.setName("待接入测试 Agent");
        agent.setDescription("验证 AgentOps 信号聚合");
        agent.setOwnerDepartment("");
        agent.setOwnerUser("");
        agent.setCategory("测试");
        agent.setLifecycleStatus(AgentLifecycleStatus.ACTIVE);
        agent.setRiskLevel(RiskLevel.HIGH);
        agent = agentRepository.save(agent);
        Long agentId = agent.getId();

        AgentOpsDtos.DashboardResponse dashboard = agentOpsService.dashboard(24, null);

        AgentWorkItem ownerItem = workItemRepository.findByItemKey("OWNER:" + agentId).orElseThrow();
        AgentWorkItem coverageItem = workItemRepository.findByItemKey("OBSERVABILITY:" + agentId).orElseThrow();
        assertThat(ownerItem.evidenceBasis).isEqualTo(EvidenceBasis.HUMAN_CONFIRMED);
        assertThat(coverageItem.evidenceBasis).isEqualTo(EvidenceBasis.DERIVED);
        assertThat(coverageItem.slaDueAt).isAfter(coverageItem.firstDetectedAt);
        assertThat(dashboard.demoDataIncluded()).isTrue();
        assertThat(dashboard.metrics()).anyMatch(metric -> metric.basis() == EvidenceBasis.DEMO);
        assertThat(dashboard.attentionAgents()).anyMatch(state ->
                state.id().equals(agentId)
                        && state.operationalHealth() == OperationalHealth.NOT_CONNECTED
                        && state.observationCoverage() == ObservationCoverage.MISSING
                        && state.onboardingState() == OnboardingState.NEEDS_OWNER);

        agent.setOwnerDepartment("平台部");
        agent.setOwnerUser("测试负责人");
        agentRepository.save(agent);
        workItemService.synchronizeSignals();
        assertThat(workItemRepository.findByItemKey("OWNER:" + agentId).orElseThrow().status)
                .isEqualTo(WorkItemStatus.RESOLVED);

        agent.setOwnerDepartment("");
        agent.setOwnerUser("");
        agentRepository.save(agent);
        workItemService.synchronizeSignals();
        AgentWorkItem reopened = workItemRepository.findByItemKey("OWNER:" + agentId).orElseThrow();
        assertThat(reopened.status).isEqualTo(WorkItemStatus.OPEN);
        assertThat(reopened.occurrenceCount).isEqualTo(2);
    }

    @Test
    void excludesDevelopmentSeedAgentsFromJwtProductionViews() {
        securityProperties.setMode(SecurityProperties.Mode.JWT);
        try {
            AgentOpsDtos.DashboardResponse dashboard = agentOpsService.dashboard(24, null);

            assertThat(dashboard.agentCount()).isZero();
            assertThat(dashboard.demoDataIncluded()).isFalse();
            assertThat(dashboard.metrics()).noneMatch(metric -> metric.basis() == EvidenceBasis.DEMO);
        } finally {
            securityProperties.setMode(SecurityProperties.Mode.DEVELOPMENT);
        }
    }

    @Test
    void recordsWorkItemLifecycleAndResolutionEvidence() {
        AgentWorkItem item = new AgentWorkItem();
        item.itemKey = "TEST:WORK-ITEM-LIFECYCLE";
        item.itemType = WorkItemType.GENERAL;
        item.title = "验证运营事项闭环";
        item.description = "测试状态流转、责任人、原因和事件记录";
        item.severity = WorkItemSeverity.P2;
        item.status = WorkItemStatus.OPEN;
        item.sourceType = "TEST";
        item.evidenceBasis = EvidenceBasis.MEASURED;
        item.evidenceJson = "{\"signal\":\"measured\"}";
        item = workItemRepository.save(item);

        workItemService.transition(item.id, new AgentOpsDtos.WorkItemTransitionRequest(
                WorkItemStatus.CLAIMED, "operator@example.com", "AgentOps", "integration-test", null, null));
        workItemService.transition(item.id, new AgentOpsDtos.WorkItemTransitionRequest(
                WorkItemStatus.IN_PROGRESS, null, null, "integration-test", null, null));
        workItemService.transition(item.id, new AgentOpsDtos.WorkItemTransitionRequest(
                WorkItemStatus.RESOLVED, null, null, "integration-test", "已恢复观测链路", "{\"traceId\":\"trace-1\"}"));
        AgentOpsDtos.WorkItemResponse closed = workItemService.transition(item.id, new AgentOpsDtos.WorkItemTransitionRequest(
                WorkItemStatus.CLOSED, null, null, "integration-test", "复核通过", "{\"ticket\":\"OPS-1\"}"));

        assertThat(closed.status()).isEqualTo(WorkItemStatus.CLOSED);
        assertThat(closed.assignee()).isEqualTo("operator@example.com");
        assertThat(closed.resolution()).isEqualTo("已恢复观测链路");
        assertThat(closed.resolutionEvidenceJson()).contains("trace-1");
        assertThat(closed.closedAt()).isNotNull();
        assertThat(workItemService.events(item.id)).hasSize(4);
    }
}
