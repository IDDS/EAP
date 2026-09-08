package com.eap.platform.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class WorkflowDtos {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private WorkflowDtos() {
    }

    public record WorkflowRequest(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 128) String name, @Size(max = 512) String description) {
    }

    public record NodeRequest(
            @NotBlank @Size(max = 64) String nodeKey,
            @NotBlank @Size(max = 128) String label,
            @NotBlank @Size(max = 32) String nodeType,
            Long agentId,
            @Size(max = 128) String capability,
            @NotNull Integer positionX,
            @NotNull Integer positionY,
            String configJson,
            @jakarta.validation.constraints.Min(0) Long expectedVersion
    ) {
        public NodeRequest(String nodeKey, String label, String nodeType, Long agentId, String capability,
                           Integer positionX, Integer positionY, String configJson) {
            this(nodeKey, label, nodeType, agentId, capability, positionX, positionY, configJson, null);
        }
    }

    public record EdgeRequest(
            @NotBlank @Size(max = 64) String sourceNodeKey,
            @NotBlank @Size(max = 64) String targetNodeKey,
            @Size(max = 128) String conditionExpression
    ) {
    }

    public record RunRequest(@Size(max = 128) String objective, Map<String, Object> context,
                             @Size(max = 64) String requestKey) {
        public RunRequest(String objective) { this(objective, Map.of(), null); }
        public RunRequest(String objective, Map<String, Object> context) { this(objective, context, null); }
    }

    public record WorkflowResponse(Long id, String code, String name, String description, WorkflowStatus status, Instant createdAt, Instant updatedAt) {
    }

    public record NodeResponse(Long id, Long workflowId, String nodeKey, String label, String nodeType, Long agentId, String capability, Integer positionX, Integer positionY, String configJson, Instant createdAt, Instant updatedAt, Long version) {
    }

    public record EdgeResponse(Long id, Long workflowId, String sourceNodeKey, String targetNodeKey, String conditionExpression, Instant createdAt, Instant updatedAt) {
    }

    public record WorkflowDetail(WorkflowResponse workflow, List<NodeResponse> nodes, List<EdgeResponse> edges) {
    }

    public record RunStepResponse(String nodeKey, String label, String nodeType, Long agentId, String status, String safetyDecision, String reason) {
    }

    public record RunResponse(String runKey, String status, String objective, List<RunStepResponse> steps,
                              String executionMode, boolean agentsInvoked) {
    }

    static WorkflowResponse from(WorkflowDefinition workflow) {
        return new WorkflowResponse(workflow.id, workflow.code, workflow.name, workflow.description, workflow.status, workflow.createdAt, workflow.updatedAt);
    }

    static NodeResponse from(WorkflowNode node) {
        return new NodeResponse(node.id, node.workflowId, node.nodeKey, node.label, node.nodeType, node.agentId, node.capability, node.positionX, node.positionY, normalizeJson(node.configJson), node.createdAt, node.updatedAt, node.version);
    }

    static EdgeResponse from(WorkflowEdge edge) {
        return new EdgeResponse(edge.id, edge.workflowId, edge.sourceNodeKey, edge.targetNodeKey, edge.conditionExpression, edge.createdAt, edge.updatedAt);
    }

    private static String normalizeJson(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return value;
        }
        try {
            return OBJECT_MAPPER.readValue(value, String.class);
        } catch (JsonProcessingException ignored) {
            return value;
        }
    }
}
