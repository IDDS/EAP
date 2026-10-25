package com.eap.platform.workflow;

import com.eap.platform.agent.AgentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.nio.charset.StandardCharsets;
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
import java.util.UUID;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WorkflowService {
    private static final ObjectMapper CONFIG_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> NODE_TYPES = Set.of("START", "END", "AGENT", "ARTIFACT", "DECISION");
    private final WorkflowDefinitionRepository workflowRepository;
    private final com.eap.platform.orchestration.AgentPlanRepository plans;
    private final WorkflowNodeRepository nodeRepository;
    private final WorkflowEdgeRepository edgeRepository;
    private final AgentRepository agentRepository;
    private final GenseeSafetyService safetyService;

    public WorkflowService(
            WorkflowDefinitionRepository workflowRepository,
            WorkflowNodeRepository nodeRepository,
            WorkflowEdgeRepository edgeRepository,
            AgentRepository agentRepository,
            GenseeSafetyService safetyService, com.eap.platform.orchestration.AgentPlanRepository plans
    ) {
        this.workflowRepository = workflowRepository;
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.agentRepository = agentRepository;
        this.safetyService = safetyService;
        this.plans = plans;
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
        validateConfig(request.configJson());
        WorkflowToolConfig.read(request.configJson(), request.nodeType(), request.agentId());
        WorkflowOutputContract.read(request.configJson(), request.nodeType(), request.agentId());
        WorkflowObservationContract.expectedModels(request.configJson(), request.nodeType(), request.agentId());
        lockWorkflow(workflowId);
        validateNodeType(request.nodeType());
        if (request.agentId() != null && !agentRepository.existsById(request.agentId())) {
            throw new BusinessException("智能体不存在");
        }
        WorkflowNode node = nodeRepository.findByWorkflowIdAndNodeKey(workflowId, request.nodeKey()).orElseGet(WorkflowNode::new);
        if (request.expectedVersion() != null && !request.expectedVersion().equals(node.version)) {
            throw new com.eap.platform.common.ConflictException("节点已被其他编辑修改，请重新加载流程后再编辑；本次修改未保存");
        }
        node.workflowId = workflowId;
        node.nodeKey = request.nodeKey();
        node.label = request.label();
        node.nodeType = request.nodeType();
        node.agentId = request.agentId();
        node.capability = request.capability();
        node.positionX = request.positionX();
        node.positionY = request.positionY();
        node.configJson = request.configJson();
        return WorkflowDtos.from(nodeRepository.saveAndFlush(node));
    }

    @Transactional
    public WorkflowDtos.EdgeResponse addEdge(Long workflowId, WorkflowDtos.EdgeRequest request) {
        lockWorkflow(workflowId);
        WorkflowPreviewCondition.validate(request.conditionExpression());
        nodeRepository.findByWorkflowIdAndNodeKey(workflowId, request.sourceNodeKey())
                .orElseThrow(() -> new BusinessException("源节点不存在"));
        nodeRepository.findByWorkflowIdAndNodeKey(workflowId, request.targetNodeKey())
                .orElseThrow(() -> new BusinessException("目标节点不存在"));
        String condition = request.conditionExpression() == null || request.conditionExpression().isBlank()
                ? "always" : request.conditionExpression().trim();
        var existing = edgeRepository.findByWorkflowId(workflowId).stream()
                .filter(edge -> edge.sourceNodeKey.equals(request.sourceNodeKey()) && edge.targetNodeKey.equals(request.targetNodeKey()))
                .filter(edge -> condition.equals(edge.conditionExpression == null || edge.conditionExpression.isBlank()
                        ? "always" : edge.conditionExpression.trim())).findFirst();
        if (existing.isPresent()) return WorkflowDtos.from(existing.get());
        WorkflowEdge edge = new WorkflowEdge();
        edge.workflowId = workflowId;
        edge.sourceNodeKey = request.sourceNodeKey();
        edge.targetNodeKey = request.targetNodeKey();
        edge.conditionExpression = condition;
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
        nodes.forEach(node -> validateNodeType(node.nodeType));
        edges.forEach(edge -> WorkflowPreviewCondition.validate(edge.conditionExpression));

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
        String status = "PREVIEW_COMPLETED";
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
            List<WorkflowEdge> outgoing = outgoingEdges.getOrDefault(current.nodeKey, List.of());
            List<WorkflowEdge> matching = outgoing.stream()
                    .filter(edge -> !WorkflowPreviewCondition.isFallback(edge.conditionExpression))
                    .filter(edge -> WorkflowPreviewCondition.matches(edge.conditionExpression, request.context()))
                    .toList();
            if (matching.isEmpty()) matching = outgoing.stream()
                    .filter(edge -> WorkflowPreviewCondition.isFallback(edge.conditionExpression)).toList();
            if (matching.size() > 1) throw new BusinessException("预演分支存在多个匹配条件，请消除歧义：" + current.nodeKey);
            if (matching.isEmpty()) {
                if (!outgoing.isEmpty()) status = "PREVIEW_NO_MATCH";
                current = null;
            } else {
                current = nodeMap.get(matching.get(0).targetNodeKey);
                if (current == null) throw new BusinessException("工作流连线指向不存在的节点");
            }
        }
        if (steps.size() >= 64 && "PREVIEW_COMPLETED".equals(status) && current != null && !"END".equalsIgnoreCase(current.nodeType)) {
            status = "FAILED_LOOP_GUARD";
        }
        return new WorkflowDtos.RunResponse(runKey, status, objective, steps, "SAFETY_PREVIEW", false);
    }

    private void lockWorkflow(Long id) {
        workflowRepository.findForUpdate(id).orElseThrow(() -> new BusinessException("工作流不存在"));
        requireUnmanaged(id);
    }

    public void requireUnmanaged(Long workflowId) {
        if (plans.existsByExecutionWorkflowId(workflowId)) throw new com.eap.platform.common.ConflictException("此工作流是计划执行快照，不能编辑或独立重跑");
    }

    private void validateConfig(String config) {
        if (config == null) return;
        if (config.getBytes(StandardCharsets.UTF_8).length > 60000) {
            throw new BusinessException("节点配置超过 60KB，请精简配置");
        }
        try {
            var parsed = CONFIG_MAPPER.readTree(config);
            if (parsed == null || !parsed.isObject()) throw new IllegalArgumentException();
        } catch (Exception exception) {
            throw new BusinessException("节点配置必须是有效的 JSON 对象");
        }
    }

    private void validateNodeType(String type) {
        if (type == null || !NODE_TYPES.contains(type.toUpperCase(Locale.ROOT))) {
            throw new BusinessException("不支持的工作流节点类型：" + type);
        }
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
            case ALLOW -> "CHECK_PASSED";
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
