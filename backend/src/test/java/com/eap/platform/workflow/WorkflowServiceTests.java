package com.eap.platform.workflow;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.safety.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowServiceTests {
    private final WorkflowDefinitionRepository workflows = mock(WorkflowDefinitionRepository.class);
    private final WorkflowNodeRepository nodes = mock(WorkflowNodeRepository.class);
    private final WorkflowEdgeRepository edges = mock(WorkflowEdgeRepository.class);
    private final GenseeSafetyService safety = mock(GenseeSafetyService.class);
    private final WorkflowService service = new WorkflowService(workflows, nodes, edges, mock(AgentRepository.class), safety, mock(com.eap.platform.orchestration.AgentPlanRepository.class));

    @BeforeEach
    void setup() {
        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.id = 1L;
        workflow.name = "test";
        when(workflows.findById(1L)).thenReturn(Optional.of(workflow));
        when(safety.evaluate(any())).thenReturn(evaluation(SafetyDecision.ALLOW));
        when(nodes.findByWorkflowId(1L)).thenReturn(List.of(node("start", "START"), node("agent", "AGENT"), node("end", "END")));
    }

    @Test
    void onlyMatchingBranchIsPreviewedAndNoTaskSuccessIsClaimed() {
        when(edges.findByWorkflowId(1L)).thenReturn(List.of(edge("start", "agent", "severity >= high"),
                edge("start", "end", "always")));
        var result = service.run(1L, new WorkflowDtos.RunRequest("preview", Map.of("severity", "low")));
        assertThat(result.executionMode()).isEqualTo("SAFETY_PREVIEW");
        assertThat(result.agentsInvoked()).isFalse();
        assertThat(result.status()).isEqualTo("PREVIEW_COMPLETED");
        assertThat(result.steps()).extracting(WorkflowDtos.RunStepResponse::nodeKey).containsExactly("start", "end");
        assertThat(result.steps()).extracting(WorkflowDtos.RunStepResponse::status).containsOnly("CHECK_PASSED");
    }

    @Test
    void falseApprovalConditionStopsWithoutVisitingAgent() {
        when(edges.findByWorkflowId(1L)).thenReturn(List.of(edge("start", "agent", "approved")));
        var result = service.run(1L, new WorkflowDtos.RunRequest("preview", Map.of("approved", false)));
        assertThat(result.status()).isEqualTo("PREVIEW_NO_MATCH");
        assertThat(result.steps()).hasSize(1);
    }

    @Test
    void missingContextUnknownExpressionsAndAmbiguousBranchesAreRejected() {
        when(edges.findByWorkflowId(1L)).thenReturn(List.of(edge("start", "agent", "approved")));
        assertThatThrownBy(() -> service.run(1L, new WorkflowDtos.RunRequest("preview")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("approved");
        when(edges.findByWorkflowId(1L)).thenReturn(List.of(edge("start", "agent", "T(java.lang.Runtime).getRuntime()")));
        assertThatThrownBy(() -> service.run(1L, new WorkflowDtos.RunRequest("preview")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不支持");
        when(edges.findByWorkflowId(1L)).thenReturn(List.of(edge("start", "agent", "always"), edge("start", "end", "always")));
        assertThatThrownBy(() -> service.run(1L, new WorkflowDtos.RunRequest("preview")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("歧义");
    }

    @Test
    void safetyDenialPreventsFollowingAnyBranch() {
        when(safety.evaluate(any())).thenReturn(evaluation(SafetyDecision.DENY));
        when(edges.findByWorkflowId(1L)).thenReturn(List.of(edge("start", "agent", "always")));
        var result = service.run(1L, new WorkflowDtos.RunRequest("preview"));
        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.steps()).hasSize(1);
        assertThat(result.agentsInvoked()).isFalse();
    }

    private WorkflowNode node(String key, String type) {
        WorkflowNode node = new WorkflowNode(); node.id = (long) key.length(); node.workflowId = 1L;
        node.nodeKey = key; node.nodeType = type; node.label = key;
        return node;
    }

    private SafetyDtos.SafetyEvaluationResponse evaluation(SafetyDecision decision) {
        return new SafetyDtos.SafetyEvaluationResponse(null, new SafetyDtos.DecisionResponse(
                1L, 1L, "preview", decision, null, null, "test", null, null, null));
    }

    private WorkflowEdge edge(String from, String to, String condition) {
        WorkflowEdge edge = new WorkflowEdge(); edge.sourceNodeKey = from; edge.targetNodeKey = to;
        edge.conditionExpression = condition; return edge;
    }
}
