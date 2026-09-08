package com.eap.platform.agentops;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.config.SecurityProperties;
import com.eap.platform.discovery.*;
import com.eap.platform.evaluation.*;
import com.eap.platform.governance.*;
import com.eap.platform.management.ManagementValueConnectorRepository;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkItemSignalWindowTests {
    @Mock AgentWorkItemRepository repository;
    @Mock AgentWorkItemEventRepository eventRepository;
    @Mock AgentRepository agentRepository;
    @Mock DiscoveredAgentRepository discoveredRepository;
    @Mock DiscoveryEndpointRepository endpointRepository;
    @Mock AgentRuntimeInstanceRepository runtimeRepository;
    @Mock GovernancePreflightDecisionRepository governanceDecisionRepository;
    @Mock GovernanceDataEventRepository governanceEventRepository;
    @Mock ManagementValueConnectorRepository connectorRepository;
    @Mock EvaluationResultRepository evaluationResultRepository;
    @Mock EvaluatorDefinitionRepository evaluatorDefinitionRepository;
    @Mock AuditLogRepository auditLogRepository;
    @Mock ObjectMapper objectMapper;
    @Mock SecurityProperties securityProperties;
    @Mock AgentOpsSignalSyncStateRepository syncStateRepository;
    @Mock com.eap.platform.registry.RuntimeLeaseProperties leaseProperties;
    @InjectMocks WorkItemService service;

    @org.junit.jupiter.api.BeforeEach
    void syncState() {
        when(syncStateRepository.lockState()).thenReturn(Optional.of(new AgentOpsSignalSyncState()));
    }

    @Test
    void pendingApprovalOutsideRecentScanStaysOpenUntilSourceIsApproved() {
        AgentWorkItem item = openItem(WorkItemType.GOVERNANCE_APPROVAL, "old-decision");
        GovernancePreflightDecision decision = mock(GovernancePreflightDecision.class);
        when(repository.findByStatusIn(anyList())).thenReturn(List.of(item));
        when(governanceDecisionRepository.findByDecisionKey("old-decision")).thenReturn(Optional.of(decision));
        when(decision.getApprovalStatus()).thenReturn(GovernanceApprovalStatus.PENDING);

        service.synchronizeSignals();
        assertThat(item.status).isEqualTo(WorkItemStatus.OPEN);
        verify(repository, never()).save(item);

        when(decision.getApprovalStatus()).thenReturn(GovernanceApprovalStatus.APPROVED);
        service.synchronizeSignals();
        assertThat(item.status).isEqualTo(WorkItemStatus.RESOLVED);
    }

    @Test
    void failedEvaluationOutsideRecentScanIsNotEvidenceOfRecovery() {
        AgentWorkItem item = openItem(WorkItemType.EVALUATION_FAILURE, "old-result");
        EvaluationResult result = mock(EvaluationResult.class);
        when(repository.findByStatusIn(anyList())).thenReturn(List.of(item));
        when(evaluationResultRepository.findByResultKey("old-result")).thenReturn(Optional.of(result));
        when(result.getStatus()).thenReturn(EvaluationStatus.COMPLETED);

        service.synchronizeSignals();
        assertThat(item.status).isEqualTo(WorkItemStatus.OPEN);
        verify(repository, never()).save(item);
    }

    private AgentWorkItem openItem(WorkItemType type, String sourceId) {
        AgentWorkItem item = new AgentWorkItem();
        item.id = 1L;
        item.itemKey = type + ":" + sourceId;
        item.itemType = type;
        item.sourceId = sourceId;
        item.status = WorkItemStatus.OPEN;
        return item;
    }
}
