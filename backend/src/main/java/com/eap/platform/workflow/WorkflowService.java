package com.eap.platform.workflow;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.safety.GenseeSafetyService;
import com.eap.platform.safety.SafetyDecision;
import com.eap.platform.safety.SafetyDtos;
import com.eap.platform.safety.SafetyEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkflowService {
    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowNodeRepository nodeRepository;
    private final WorkflowEdgeRepository edgeRepository;
    private final AgentRepository agentRepository;
    private final GenseeSafetyService safetyService;

    public WorkflowService(
            WorkflowDefinitionRepository workflowRepository,
            WorkflowNodeRepository nodeRepository,
            WorkflowEdgeRepository edgeRepository,
            AgentRepository agentRepository,
            GenseeSafetyService safetyService
    ) {
        this.workflowRepository = workflowRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.agentRepository = agentRepository;
        this.safetyService = safetyService;
    }

    @Transactional(readOnly = true)
    public List<WorkflowDtos.WorkflowResponse> list() {
        return workflowRepository.findAll().stream().map(WorkflowDtos::from).toList();
    }

    @Transactional
    public WorkflowDtos.WorkflowResponse create(WorkflowDtos.WorkflowRequest request) {
        if (workflowRepository.existsByCode(request.code())) {
            throw new BusinessException("工作流编码已存在");
        }
        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.code = request.code();
        workflow.name = request.name();
        workflow.description = request.description();
        workflow.status = WorkflowStatus.DRAFT;
        return WorkflowDtos.from(workflowRepository.save(workflow));
    }

    @Transactional(readOnly = true)
    public WorkflowDtos.WorkflowDetail detail(Long workflowId) {
        WorkflowDefinition workflow = findWorkflow(workflowId);
        List<WorkflowDtos.NodeResponse> nodes = nodeRepository.findByWorkflowId(workflowId).stream().map(WorkflowDtos::from).toList();
        List<WorkflowDtos.EdgeResponse> edges = edgeRepository.findByWorkflowId(workflowId).stream().map(WorkflowDtos::from).toList();
        return new WorkflowDtos.WorkflowDetail(WorkflowDtos.from(workflow), nodes, edges);
    }

    @Transactional
    public WorkflowDtos.NodeResponse upsertNode(Long workflowId, WorkflowDtos.NodeRequest request) {
        findWorkflow(workflowId);
        if (request.agentId() != null && !agentRepository.existsById(request.agentId())) {
            throw new BusinessException("智能体不存在");
        }
        WorkflowNode node = nodeRepository.findByWorkflowIdAndNodeKey(workflowId, request.nodeKey()).orElseGet(WorkflowNode::new);
        node.workflowId = workflowId;
        node.nodeKey = request.nodeKey();
        node.label = request.label();
        node.nodeType = request.nodeType();
        node.agentId = request.agentId();
        node.capability = request.capability();
        node.positionX = request.positionX();
        node.positionY = request.positionY();
        node.configJson = request.configJson();
        return WorkflowDtos.from(nodeRepository.save(node));
    }

    @Transactional
    public WorkflowDtos.EdgeResponse addEdge(Long workflowId, WorkflowDtos.EdgeRequest request) {
        findWorkflow(workflowId);
        nodeRepository.findByWorkflowIdAndNodeKey(workflowId, request.sourceNodeKey())
                .orElseThrow(() -> new BusinessException("源节点不存在"));
        nodeRepository.findByWorkflowIdAndNodeKey(workflowId, request.targetNodeKey())
                .orElseThrow(() -> new BusinessException("目标节点不存在"));
        WorkflowEdge edge = new WorkflowEdge();
        edge.workflowId = workflowId;
        edge.sourceNodeKey = request.sourceNodeKey();
        edge.targetNodeKey = request.targetNodeKey();
        edge.conditionExpression = request.conditionExpression();
        return WorkflowDtos.from(edgeRepository.save(edge));
    }

    @Transactional
    public WorkflowDtos.RunResponse run(Long workflowId, WorkflowDtos.RunRequest request) {
        WorkflowDefinition workflow = findWorkflow(workflowId);
        List<WorkflowNode> nodes = nodeRepository.findByWorkflowId(workflowId);
        List<WorkflowEdge> edges = edgeRepository.findByWorkflowId(workflowId);
        if (nodes.isEmpty()) {
            throw new BusinessException("工作流没有可执行节点");
        }

        Map<String, WorkflowNode> nodeMap = nodes.stream().collect(Collectors.toMap(node -> node.nodeKey, Function.identity()));
        Map<String, List<WorkflowEdge>> outgoingEdges = edges.stream()
                .collect(Collectors.groupingBy(edge -> edge.sourceNodeKey));
        WorkflowNode current = findStartNode(nodes);
        String runKey = "WF-" + workflow.id + "-" + UUID.randomUUID().toString().substring(0, 8);
        String objective = request.objective() == null || request.objective().isBlank()
                ? workflow.name + " 执行"
                : request.objective();
        Long rootAgentId = nodes.stream()
                .filter(node -> node.agentId != null)
                .map(node -> node.agentId)
                .findFirst()
                .orElse(null);
        safetyService.createSession(new SafetyDtos.SessionRequest(runKey, rootAgentId, objective));

        List<WorkflowDtos.RunStepResponse> steps = new ArrayList<>();
        String status = "SUCCEEDED";
        for (int count = 0; count < 64 && current != null; count++) {
            WorkflowDtos.RunStepResponse step = executeNode(runKey, current, objective);
            steps.add(step);
            if ("BLOCKED".equals(step.status())) {
                status = "BLOCKED";
                break;
            }
            if ("APPROVAL_REQUIRED".equals(step.status())) {
                status = "WAITING_APPROVAL";
                break;
            }
            if ("END".equalsIgnoreCase(current.nodeType)) {
                break;
            }
            Optional<WorkflowEdge> nextEdge = outgoingEdges.getOrDefault(current.nodeKey, List.of()).stream()
                    .sorted(Comparator.comparing(edge -> edge.id))
                    .findFirst();
            current = nextEdge.map(edge -> nodeMap.get(edge.targetNodeKey)).orElse(null);
        }
        if (steps.size() >= 64) {
            status = "FAILED_LOOP_GUARD";
        }
        return new WorkflowDtos.RunResponse(runKey, status, objective, steps);
    }

    private WorkflowDefinition findWorkflow(Long workflowId) {
        return workflowRepository.findById(workflowId).orElseThrow(() -> new BusinessException("工作流不存在"));
    }

    private WorkflowNode findStartNode(List<WorkflowNode> nodes) {
        return nodes.stream()
                .filter(node -> "START".equalsIgnoreCase(node.nodeType))
                .findFirst()
                .orElseGet(() -> nodes.stream()
                        .min(Comparator.comparing(node -> node.id))
                        .orElseThrow(() -> new BusinessException("工作流没有可执行节点")));
    }

    private WorkflowDtos.RunStepResponse executeNode(String runKey, WorkflowNode node, String objective) {
        SafetyDtos.SafetyEvaluationResponse evaluation = safetyService.evaluate(new SafetyDtos.SafetyEventRequest(
                runKey,
                node.agentId,
                null,
                eventTypeOf(node.nodeType),
                actionOf(node.nodeType),
                targetOf(node),
                "workflow node=" + node.nodeKey + ", objective=" + objective,
                node.configJson
        ));
        SafetyDecision decision = evaluation.decision().decision();
        String status = switch (decision) {
            case DENY -> "BLOCKED";
            case ASK -> "APPROVAL_REQUIRED";
            case ALLOW -> "DONE";
        };
        return new WorkflowDtos.RunStepResponse(
                node.nodeKey,
                node.label,
                node.nodeType,
                node.agentId,
                status,
                decision.name(),
                evaluation.decision().reason()
        );
    }

    private SafetyEventType eventTypeOf(String nodeType) {
        return switch (nodeType.toUpperCase()) {
            case "AGENT" -> SafetyEventType.AGENT_MESSAGE;
            case "ARTIFACT" -> SafetyEventType.ARTIFACT_WRITE;
            case "DECISION" -> SafetyEventType.POLICY_CHECK;
            default -> SafetyEventType.AGENT_MESSAGE;
        };
    }

    private String actionOf(String nodeType) {
        return switch (nodeType.toUpperCase()) {
            case "AGENT" -> "invoke_agent";
            case "ARTIFACT" -> "write_artifact";
            case "DECISION" -> "evaluate_condition";
            default -> "workflow_transition";
        };
    }

    private String targetOf(WorkflowNode node) {
        if ("ARTIFACT".equalsIgnoreCase(node.nodeType)) {
            return "artifact://workflow/" + node.workflowId + "/" + node.nodeKey;
        }
        if (node.agentId != null) {
            return "agent://" + node.agentId + "/" + node.nodeKey;
        }
        return "workflow://" + node.workflowId + "/" + node.nodeKey;
    }
}
