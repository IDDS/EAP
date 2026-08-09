package com.eap.platform.dashboard;

import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.ApiResponse;
import com.eap.platform.control.AgentControlCommandRepository;
import com.eap.platform.operations.AgentTaskRepository;
import com.eap.platform.operations.AgentTaskStatus;
import com.eap.platform.registry.AgentCapabilityRepository;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.safety.SafetyDecision;
import com.eap.platform.safety.SafetyDecisionLogRepository;
import com.eap.platform.safety.SafetySeverity;
import com.eap.platform.workflow.WorkflowDefinitionRepository;
import com.eap.platform.workflow.WorkflowEdgeRepository;
import com.eap.platform.workflow.WorkflowNode;
import com.eap.platform.workflow.WorkflowNodeRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final AgentRepository agentRepository;
    private final AgentTaskRepository taskRepository;
    private final SafetyDecisionLogRepository decisionRepository;
    private final AgentRuntimeInstanceRepository runtimeRepository;
    private final AgentCapabilityRepository capabilityRepository;
    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowNodeRepository workflowNodeRepository;
    private final WorkflowEdgeRepository workflowEdgeRepository;
    private final AgentControlCommandRepository commandRepository;

    public DashboardController(
            AgentRepository agentRepository,
            AgentTaskRepository taskRepository,
            SafetyDecisionLogRepository decisionRepository,
            AgentRuntimeInstanceRepository runtimeRepository,
            AgentCapabilityRepository capabilityRepository,
            WorkflowDefinitionRepository workflowRepository,
            WorkflowNodeRepository workflowNodeRepository,
            WorkflowEdgeRepository workflowEdgeRepository,
            AgentControlCommandRepository commandRepository
    ) {
        this.agentRepository = agentRepository;
        this.taskRepository = taskRepository;
        this.decisionRepository = decisionRepository;
        this.runtimeRepository = runtimeRepository;
        this.capabilityRepository = capabilityRepository;
        this.workflowRepository = workflowRepository;
        this.workflowNodeRepository = workflowNodeRepository;
        this.workflowEdgeRepository = workflowEdgeRepository;
        this.commandRepository = commandRepository;
    }

    @GetMapping("/summary")
    public ApiResponse<DashboardSummary> summary() {
        long agentCount = agentRepository.count();
        long activeAgentCount = agentRepository.findAll().stream()
                .filter(agent -> agent.getLifecycleStatus() == AgentLifecycleStatus.ACTIVE)
                .count();
        long totalTasks = taskRepository.count();
        long successTasks = taskRepository.countByStatus(AgentTaskStatus.SUCCEEDED);
        double successRate = totalTasks == 0 ? 0.0 : (double) successTasks / totalTasks;
        long runningTaskCount = taskRepository.countByStatus(AgentTaskStatus.RUNNING);
        long riskEventCount = decisionRepository.countByDecision(SafetyDecision.DENY) + decisionRepository.countBySeverity(SafetySeverity.CRITICAL);
        return ApiResponse.ok(new DashboardSummary(agentCount, activeAgentCount, runningTaskCount, riskEventCount, successRate, 0));
    }

    @GetMapping("/network")
    public ApiResponse<DashboardNetwork> network() {
        List<DashboardNetwork.NetworkNode> nodes = new ArrayList<>();
        List<DashboardNetwork.NetworkEdge> edges = new ArrayList<>();

        nodes.add(new DashboardNetwork.NetworkNode("control:eap", "EAP 控制面", "CONTROL_PLANE", "CONTROL", "ONLINE", "LOW", commandRepository.count() + " command", 440, 80));
        nodes.add(new DashboardNetwork.NetworkNode("control:registry", "Agent 注册中心", "REGISTRY", "CONTROL", "ONLINE", "LOW", runtimeRepository.count() + " runtime", 210, 210));
        nodes.add(new DashboardNetwork.NetworkNode("control:safety", "安全策略网关", "SAFETY_GATEWAY", "CONTROL", "ONLINE", "MEDIUM", decisionRepository.count() + " decisions", 440, 210));
        nodes.add(new DashboardNetwork.NetworkNode("control:workflow", "工作流编排器", "ORCHESTRATOR", "CONTROL", "ONLINE", "MEDIUM", workflowRepository.count() + " workflow", 670, 210));

        edges.add(edge("control:eap", "control:registry", "注册/发现", "CONTROL", "MANAGES", "ACTIVE"));
        edges.add(edge("control:eap", "control:safety", "策略下发", "CONTROL", "GOVERNS", "ACTIVE"));
        edges.add(edge("control:eap", "control:workflow", "编排配置", "CONTROL", "PROGRAMS", "ACTIVE"));

        List<com.eap.platform.agent.Agent> agents = agentRepository.findAll().stream()
                .sorted(Comparator.comparing(com.eap.platform.agent.Agent::getId))
                .toList();
        for (int i = 0; i < agents.size(); i++) {
            var agent = agents.get(i);
            String agentNodeId = agentNodeId(agent.getId());
            int x = 130 + (i % 4) * 230;
            int y = 380 + (i / 4) * 150;
            long runtimeCount = runtimeRepository.findByAgentId(agent.getId()).size();
            long capabilityCount = capabilityRepository.findByAgentId(agent.getId()).size();
            nodes.add(new DashboardNetwork.NetworkNode(
                    agentNodeId,
                    agent.getName(),
                    "AGENT",
                    "DATA",
                    agent.getLifecycleStatus().name(),
                    agent.getRiskLevel().name(),
                    runtimeCount + " runtime / " + capabilityCount + " cap",
                    x,
                    y
            ));
            edges.add(edge("control:eap", agentNodeId, "控制命令", "CONTROL", "COMMANDS", "READY"));
            edges.add(edge("control:registry", agentNodeId, "注册心跳", "CONTROL", "OBSERVES", runtimeCount > 0 ? "ONLINE" : "NO_RUNTIME"));
            edges.add(edge("control:safety", agentNodeId, "策略评估", "CONTROL", "ENFORCES", "ACTIVE"));
        }

        Map<String, WorkflowNode> workflowNodeIndex = new HashMap<>();
        for (WorkflowNode node : workflowNodeRepository.findAll()) {
            workflowNodeIndex.put(node.getWorkflowId() + ":" + node.getNodeKey(), node);
        }
        for (var workflow : workflowRepository.findAll()) {
            edges.add(edge("control:workflow", "control:eap", workflow.getName(), "CONTROL", "DECLARES_WORKFLOW", workflow.getStatus().name()));
            for (var workflowEdge : workflowEdgeRepository.findByWorkflowId(workflow.getId())) {
                WorkflowNode source = workflowNodeIndex.get(workflowEdge.getWorkflowId() + ":" + workflowEdge.getSourceNodeKey());
                WorkflowNode target = workflowNodeIndex.get(workflowEdge.getWorkflowId() + ":" + workflowEdge.getTargetNodeKey());
                if (source == null || target == null || source.getAgentId() == null || target.getAgentId() == null) {
                    continue;
                }
                edges.add(edge(
                        agentNodeId(source.getAgentId()),
                        agentNodeId(target.getAgentId()),
                        workflow.getCode() + " / " + (workflowEdge.getConditionExpression() == null ? "next" : workflowEdge.getConditionExpression()),
                        "DATA",
                        "WORKFLOW_FLOW",
                        "PROGRAMMED"
                ));
            }
        }

        long controlEdges = edges.stream().filter(edge -> "CONTROL".equals(edge.plane())).count();
        long dataEdges = edges.stream().filter(edge -> "DATA".equals(edge.plane())).count();
        DashboardNetwork.NetworkStats stats = new DashboardNetwork.NetworkStats(
                4,
                agents.size(),
                controlEdges,
                dataEdges
        );
        return ApiResponse.ok(new DashboardNetwork(nodes, edges, stats));
    }

    private DashboardNetwork.NetworkEdge edge(String source, String target, String label, String plane, String relationType, String status) {
        return new DashboardNetwork.NetworkEdge(source + "->" + target + ":" + relationType + ":" + label, source, target, label, plane, relationType, status);
    }

    private String agentNodeId(Long agentId) {
        return "agent:" + agentId;
    }
}
