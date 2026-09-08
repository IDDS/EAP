package com.eap.platform.workflow;

import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.registry.*;
import com.eap.platform.safety.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service
public class WorkflowExecutionService {
    private final WorkflowExecutionRepository repository;
    private final WorkflowService workflows;
    private final WorkflowDefinitionRepository definitions;
    private final AgentRepository agents;
    private final AgentRuntimeInstanceRepository runtimes;
    private final RuntimeLeaseProperties lease;
    private final WorkflowExecutionProperties properties;
    private final WorkflowTaskClient client;
    private final GenseeSafetyService safety;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public WorkflowExecutionService(WorkflowExecutionRepository repository, WorkflowService workflows, WorkflowDefinitionRepository definitions,
            AgentRepository agents, AgentRuntimeInstanceRepository runtimes, RuntimeLeaseProperties lease,
            WorkflowExecutionProperties properties, WorkflowTaskClient client, GenseeSafetyService safety,
            ObjectMapper mapper, PlatformTransactionManager manager) {
        this.repository = repository; this.workflows = workflows; this.definitions = definitions; this.agents = agents; this.runtimes = runtimes;
        this.lease = lease; this.properties = properties; this.client = client; this.safety = safety; this.mapper = mapper;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Snapshot(List<WorkflowDtos.NodeResponse> nodes, List<WorkflowDtos.EdgeResponse> edges, Map<String, Object> context) {}
    public record Step(int sequence, String nodeKey, String label, Long agentId, String taskKey,
                       String runtimeInstanceKey, String status, String safetyDecision, String reason, Integer httpStatus) {}
    public record Response(String runKey, Long workflowId, String status, String executionMode, String objective,
                           String requester, List<Step> steps, String reason, Instant createdAt, Instant startedAt, Instant completedAt) {}
    public record Availability(boolean enabled, String protocol, String message) {}

    public Availability availability() {
        return new Availability(properties.ready(), "EAP_HTTP_TASK_V1", properties.ready()
                ? "仅执行已注册的 EAP HTTP 任务 Runtime；不自动重试结果未知的任务"
                : "真实执行未启用或未配置认证与目标白名单；可使用安全预演");
    }

    @Transactional
    public Response submit(Long workflowId, WorkflowDtos.RunRequest request, String requester) {
        definitions.findForUpdate(workflowId).orElseThrow(() -> new BusinessException("工作流不存在"));
        String submissionKey = null;
        String fingerprint = null;
        if (request.requestKey() != null) {
            if (!request.requestKey().matches("[A-Za-z0-9_-]{1,64}")) throw new BusinessException("requestKey 必须是 1–64 位字母、数字、下划线或短横线");
            submissionKey = digest((requester == null ? "console" : requester) + ":" + request.requestKey());
            try {
                fingerprint = digest(mapper.writer(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                        .writeValueAsString(Map.of("objective", request.objective() == null ? "" : request.objective(),
                                "context", request.context() == null ? Map.of() : request.context())));
            } catch (Exception exception) { throw new BusinessException("执行请求无法序列化"); }
            var existing = repository.findByWorkflowIdAndSubmissionKey(workflowId, submissionKey);
            if (existing.isPresent()) {
                if (!fingerprint.equals(existing.get().requestFingerprint)) {
                    throw new com.eap.platform.common.ConflictException("同一 requestKey 的执行参数已改变，请核对原执行记录");
                }
                return response(existing.get());
            }
        }
        if (!properties.ready()) throw new BusinessException(availability().message());
        var detail = workflows.detail(workflowId);
        if (detail.nodes().isEmpty() || detail.nodes().size() > 64 || detail.edges().size() > 256) {
            throw new BusinessException("真实执行要求 1–64 个节点，最多 256 条连线");
        }
        Map<String, Object> context = request.context() == null ? Map.of() : request.context();
        // Resolve the complete route before any external side effect; user context is not approval authority.
        List<WorkflowDtos.NodeResponse> path = route(new Snapshot(detail.nodes(), detail.edges(), context));
        for (var node : path) {
            if (!Set.of("START", "END", "DECISION", "AGENT", "ARTIFACT").contains(node.nodeType().toUpperCase(Locale.ROOT))) {
                throw new BusinessException("不支持的执行节点类型");
            }
            if (isTaskNode(node) && (node.agentId() == null || selectRuntime(node.agentId()) == null)) {
                throw new BusinessException("节点缺少有效 Agent 或新鲜的 EAP_HTTP_TASK_V1 Runtime：" + node.label());
            }
        }
        WorkflowExecution run = new WorkflowExecution();
        run.submissionKey = submissionKey; run.requestFingerprint = fingerprint;
        run.workflowId = workflowId; run.runKey = "wf-exec-" + UUID.randomUUID(); run.status = "QUEUED";
        run.objective = request.objective() == null || request.objective().isBlank() ? detail.workflow().name() : request.objective();
        run.requester = requester == null || requester.isBlank() ? "console" : requester.substring(0, Math.min(requester.length(), 128));
        run.snapshotJson = json(new Snapshot(path, List.of(), context));
        if (run.snapshotJson.getBytes(StandardCharsets.UTF_8).length > 60000) throw new BusinessException("工作流快照超过 60KB，请精简配置");
        run.createdAt = Instant.now();
        return response(repository.save(run));
    }

    @Transactional(readOnly = true)
    public Response get(Long workflowId, String runKey) {
        return response(repository.findByWorkflowIdAndRunKey(workflowId, runKey)
                .orElseThrow(() -> new BusinessException("执行记录不存在")));
    }

    @Transactional(readOnly = true)
    public List<Response> list(Long workflowId) {
        return repository.findByWorkflowIdOrderByCreatedAtDescIdDesc(workflowId, PageRequest.of(0, 20))
                .stream().map(this::response).toList();
    }

    public void processNext() {
        WorkflowExecution run = transaction.execute(status -> {
            Instant now = Instant.now();
            repository.expire(now.minusSeconds(properties.getTimeoutSeconds()), now);
            if (!properties.ready()) return null;
            WorkflowExecution queued = repository.findFirstByStatusOrderByCreatedAtAscIdAsc("QUEUED").orElse(null);
            if (queued == null) return null;
            queued.status = "RUNNING"; queued.startedAt = now;
            return repository.save(queued);
        });
        if (run == null) return;
        List<Step> steps = new ArrayList<>();
        try {
            Snapshot snapshot = mapper.readValue(run.snapshotJson, Snapshot.class);
            safety.createSession(new SafetyDtos.SessionRequest(run.runKey, null, run.objective));
            for (var node : snapshot.nodes()) {
                if (!progress(run, steps, "RUNNING", null, false)) return;
                AgentRuntimeInstance runtime = isTaskNode(node) ? selectRuntime(node.agentId()) : null;
                if (isTaskNode(node) && runtime == null) {
                    progress(run, steps, "FAILED", "Runtime 已离线或 Agent 不再启用，未派发下一节点", true); return;
                }
                var decision = safety.evaluate(new SafetyDtos.SafetyEventRequest(run.runKey, node.agentId(), null,
                        "ARTIFACT".equalsIgnoreCase(node.nodeType()) ? SafetyEventType.ARTIFACT_WRITE : SafetyEventType.AGENT_MESSAGE,
                        "ARTIFACT".equalsIgnoreCase(node.nodeType()) ? "write_artifact" : isTaskNode(node) ? "invoke_agent" : "workflow_transition",
                        runtime != null ? runtime.getEndpoint() : "workflow://" + run.workflowId + "/" + node.nodeKey(),
                        "workflow execution " + run.runKey,
                        json(Map.of("objective", run.objective, "context", snapshot.context(),
                                "config", node.configJson() == null ? "" : node.configJson())))).decision().decision();
                int sequence = steps.size();
                String taskKey = run.runKey + ":" + sequence;
                if (decision != SafetyDecision.ALLOW) {
                    String status = decision == SafetyDecision.DENY ? "BLOCKED" : "WAITING_APPROVAL";
                    steps.add(new Step(sequence, node.nodeKey(), node.label(), node.agentId(), null, null, status, decision.name(),
                            "策略未允许执行；需处理治理事项后显式新建执行", null));
                    progress(run, steps, status, "未派发此节点", true); return;
                }
                if (!isTaskNode(node)) {
                    steps.add(new Step(sequence, node.nodeKey(), node.label(), node.agentId(), null, null, "CHECK_PASSED", "ALLOW", "控制节点检查通过", null));
                    if (!progress(run, steps, "RUNNING", null, false)) return;
                    continue;
                }
                steps.add(new Step(sequence, node.nodeKey(), node.label(), node.agentId(), taskKey, runtime.getInstanceKey(), "DISPATCHING", "ALLOW", "已记录派发意图", null));
                if (!progress(run, steps, "RUNNING", null, false)) return;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("protocol", "EAP_HTTP_TASK_V1"); payload.put("taskKey", taskKey); payload.put("workflowRunKey", run.runKey);
                payload.put("agentId", node.agentId()); payload.put("nodeKey", node.nodeKey()); payload.put("nodeType", node.nodeType());
                payload.put("capability", node.capability()); payload.put("objective", run.objective); payload.put("context", snapshot.context());
                payload.put("configJson", node.configJson());
                var receipt = client.invoke(runtime, payload, run.startedAt.plusSeconds(properties.getTimeoutSeconds()));
                steps.set(sequence, new Step(sequence, node.nodeKey(), node.label(), node.agentId(), taskKey, runtime.getInstanceKey(),
                        receipt.status(), "ALLOW", receipt.reason(), receipt.httpStatus()));
                boolean terminal = !"SUCCEEDED".equals(receipt.status());
                if (!progress(run, steps, terminal ? receipt.status() : "RUNNING", receipt.reason(), terminal) || terminal) return;
            }
            progress(run, steps, "SUCCEEDED", "所选路径全部完成；任务节点均由 Runtime 返回匹配标识的终态", true);
        } catch (Exception exception) {
            progress(run, steps, "EXECUTION_UNKNOWN", "执行中断，已发生的外部效果未回滚；请按任务标识核对", true);
        }
    }

    private boolean progress(WorkflowExecution run, List<Step> steps, String status, String reason, boolean terminal) {
        return Boolean.TRUE.equals(transaction.execute(ignored -> {
            Instant now = Instant.now();
            if (!now.isBefore(run.startedAt.plusSeconds(properties.getTimeoutSeconds()))) {
                repository.progress(run.id, json(steps), "EXECUTION_UNKNOWN",
                        "执行超过期限，外部效果可能已发生；请按任务标识核对，未自动重试", now);
                return false;
            }
            return repository.progress(run.id, json(steps), status, reason, terminal ? now : null) == 1;
        }));
    }

    private AgentRuntimeInstance selectRuntime(Long id) {
        if (id == null || agents.findById(id).filter(agent -> agent.getLifecycleStatus() == AgentLifecycleStatus.ACTIVE).isEmpty()) return null;
        Instant cutoff = lease.cutoff();
        return runtimes.findByAgentId(id).stream().filter(runtime -> "EAP_HTTP_TASK_V1".equals(runtime.getProtocol())
                        && lease.effectiveStatus(runtime, cutoff) == RuntimeStatus.ONLINE)
                .min(Comparator.comparing(AgentRuntimeInstance::getLoadScore)).orElse(null);
    }
    private boolean isTaskNode(WorkflowDtos.NodeResponse node) {
        return "AGENT".equalsIgnoreCase(node.nodeType()) || "ARTIFACT".equalsIgnoreCase(node.nodeType());
    }
    private List<WorkflowDtos.NodeResponse> route(Snapshot snapshot) {
        Map<String, WorkflowDtos.NodeResponse> nodes = new LinkedHashMap<>();
        snapshot.nodes().forEach(node -> nodes.put(node.nodeKey(), node));
        var starts = snapshot.nodes().stream().filter(node -> "START".equalsIgnoreCase(node.nodeType())).toList();
        if (starts.size() != 1) throw new BusinessException("真实执行需要且仅允许一个 START 节点");
        var current = starts.get(0);
        List<WorkflowDtos.NodeResponse> path = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        for (var edge : snapshot.edges()) WorkflowPreviewCondition.validate(edge.conditionExpression());
        while (current != null) {
            if (!visited.add(current.nodeKey())) throw new BusinessException("真实执行暂不支持循环路径");
            path.add(current);
            if ("END".equalsIgnoreCase(current.nodeType())) break;
            String key = current.nodeKey();
            var outgoing = snapshot.edges().stream().filter(edge -> edge.sourceNodeKey().equals(key)).toList();
            var matched = outgoing.stream().filter(edge -> !WorkflowPreviewCondition.isFallback(edge.conditionExpression()))
                    .filter(edge -> WorkflowPreviewCondition.matches(edge.conditionExpression(), snapshot.context())).toList();
            if (matched.isEmpty()) matched = outgoing.stream().filter(edge -> WorkflowPreviewCondition.isFallback(edge.conditionExpression())).toList();
            if (matched.size() > 1) throw new BusinessException("真实执行存在歧义分支：" + key);
            if (matched.isEmpty()) {
                if (!outgoing.isEmpty()) throw new BusinessException("真实执行无匹配分支：" + key);
                break;
            }
            current = nodes.get(matched.get(0).targetNodeKey());
            if (current == null) throw new BusinessException("连线指向不存在的节点");
        }
        return path;
    }
    private String digest(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new BusinessException("工作流执行数据无法序列化"); }
    }
    private Response response(WorkflowExecution run) {
        try {
            return new Response(run.runKey, run.workflowId, run.status, "EAP_HTTP_TASK_V1", run.objective, run.requester,
                    mapper.readValue(run.stepsJson, new TypeReference<List<Step>>() {}), run.reason, run.createdAt, run.startedAt, run.completedAt);
        } catch (Exception exception) { throw new BusinessException("执行记录无法读取"); }
    }
}
