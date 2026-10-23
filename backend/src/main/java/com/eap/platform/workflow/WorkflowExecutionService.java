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
    @org.springframework.beans.factory.annotation.Autowired private com.eap.platform.observability.ObservationCoverageService coverage;
    @org.springframework.beans.factory.annotation.Autowired private WorkflowExecutionConfigurationService configurations;
    private final WorkflowExecutionRepository repository;
    private final WorkflowService workflows;
    private final WorkflowDefinitionRepository definitions;
    private final AgentRepository agents;
    private final AgentRuntimeInstanceRepository runtimes;
    private final com.eap.platform.registry.RuntimeConnectionService connections;
    private final RuntimeLeaseProperties lease;
    private final WorkflowExecutionProperties properties;
    private final RuntimeWorkService work;
    private final WorkflowTaskClient client;
    private final WorkflowToolRecoveryService toolRecovery;
    private final WorkflowSchedulingService scheduling;
    private final com.eap.platform.ax.AxService ax;
    private final GenseeSafetyService safety;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public WorkflowExecutionService(WorkflowExecutionRepository repository, WorkflowService workflows, WorkflowDefinitionRepository definitions,
            AgentRepository agents, AgentRuntimeInstanceRepository runtimes, com.eap.platform.registry.RuntimeConnectionService connections, RuntimeLeaseProperties lease,
            WorkflowExecutionProperties properties, WorkflowTaskClient client, GenseeSafetyService safety,
            ObjectMapper mapper, PlatformTransactionManager manager, com.eap.platform.ax.AxService ax, WorkflowToolRecoveryService toolRecovery, WorkflowSchedulingService scheduling, RuntimeWorkService work) {
        this.work = work;
        this.scheduling = scheduling;
        this.toolRecovery = toolRecovery;
        this.ax = ax;
        this.repository = repository; this.workflows = workflows; this.definitions = definitions; this.agents = agents; this.runtimes = runtimes; this.connections = connections;
        this.lease = lease; this.properties = properties; this.client = client; this.safety = safety; this.mapper = mapper;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record Snapshot(List<WorkflowDtos.NodeResponse> nodes, List<WorkflowDtos.EdgeResponse> edges, Map<String, Object> context) {}
    public record Step(int sequence, String nodeKey, String label, Long agentId, String taskKey,
                       String runtimeInstanceKey, String status, String safetyDecision, String reason, Integer httpStatus,
                       com.fasterxml.jackson.databind.JsonNode output, String outputSha256, String reclaimStatus,
                       WorkflowExecutionConfigurationService.RuntimeBinding runtimeBinding, WorkflowOutputContract.Validation resultValidation) {
        public Step(int sequence, String nodeKey, String label, Long agentId, String taskKey,
                    String runtimeInstanceKey, String status, String safetyDecision, String reason, Integer httpStatus,
                    com.fasterxml.jackson.databind.JsonNode output, String outputSha256, String reclaimStatus,
                    WorkflowExecutionConfigurationService.RuntimeBinding runtimeBinding) {
            this(sequence, nodeKey, label, agentId, taskKey, runtimeInstanceKey, status, safetyDecision, reason, httpStatus,
                    output, outputSha256, reclaimStatus, runtimeBinding, null);
        }
        public Step(int sequence, String nodeKey, String label, Long agentId, String taskKey,
                    String runtimeInstanceKey, String status, String safetyDecision, String reason, Integer httpStatus,
                    com.fasterxml.jackson.databind.JsonNode output, String outputSha256, String reclaimStatus) {
            this(sequence, nodeKey, label, agentId, taskKey, runtimeInstanceKey, status, safetyDecision, reason, httpStatus,
                    output, outputSha256, reclaimStatus, null);
        }
        public Step(int sequence, String nodeKey, String label, Long agentId, String taskKey,
                    String runtimeInstanceKey, String status, String safetyDecision, String reason, Integer httpStatus,
                    com.fasterxml.jackson.databind.JsonNode output, String outputSha256) {
            this(sequence, nodeKey, label, agentId, taskKey, runtimeInstanceKey, status, safetyDecision, reason, httpStatus, output, outputSha256, null);
        }
        Step reclaimed(String state) {
            return new Step(sequence, nodeKey, label, agentId, taskKey, runtimeInstanceKey, status, safetyDecision,
                    reason, httpStatus, output, outputSha256, state, runtimeBinding, resultValidation);
        }
        Step bound(WorkflowExecutionConfigurationService.RuntimeBinding binding) {
            return new Step(sequence, nodeKey, label, agentId, taskKey, runtimeInstanceKey, status, safetyDecision,
                    reason, httpStatus, output, outputSha256, reclaimStatus, binding, resultValidation);
        }
        public Step(int sequence, String nodeKey, String label, Long agentId, String taskKey,
                    String runtimeInstanceKey, String status, String safetyDecision, String reason, Integer httpStatus) {
            this(sequence, nodeKey, label, agentId, taskKey, runtimeInstanceKey, status, safetyDecision, reason, httpStatus, null, null);
        }
        Step validated(WorkflowOutputContract.Validation validation) {
            return new Step(sequence, nodeKey, label, agentId, taskKey, runtimeInstanceKey, status, safetyDecision,
                    reason, httpStatus, output, outputSha256, reclaimStatus, runtimeBinding, validation);
        }
        Step metadata() { return new Step(sequence, nodeKey, label, agentId, taskKey, runtimeInstanceKey,
                status, safetyDecision, reason, httpStatus, null, outputSha256, reclaimStatus, runtimeBinding, resultValidation); }
    }
    public record Response(String runKey, Long workflowId, String status, String executionMode, String objective,
                           String requester, List<Step> steps, String reason, Instant createdAt, Instant startedAt, Instant completedAt) {}
    public record Availability(boolean enabled, String protocol, String message) {}

    public Availability availability() {
        return new Availability(properties.ready(), "EAP_HTTP_TASK_V1", properties.ready()
                ? "支持 Runtime 与 AX 串行混合执行；结果未知不自动重试"
                : "真实执行未启用或未配置认证与目标白名单；可使用安全预演");
    }

    @Transactional
    public Response submit(Long workflowId, WorkflowDtos.RunRequest request, String requester) {
        definitions.findForUpdate(workflowId).orElseThrow(() -> new BusinessException("工作流不存在"));
        workflows.requireUnmanaged(workflowId);
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
        if (context.containsKey("eapTool")) throw new BusinessException("工具参数请保存到节点配置 eapTool；执行上下文不再接受全局工具指令");
        if (context.containsKey("eapPreviousResults")) throw new BusinessException("eapPreviousResults 为执行器保留上下文");
        // Resolve the complete route before any external side effect; user context is not approval authority.
        List<WorkflowDtos.NodeResponse> path = route(new Snapshot(detail.nodes(), detail.edges(), context));
        Set<String> previousTasks = new HashSet<>();
        for (var node : path) {
            taskObjective(node, "validation");
            WorkflowToolConfig.read(node);
            WorkflowOutputContract.read(node);
            WorkflowObservationContract.expectedModels(node);
            for (String source : inputFrom(node)) {
                if (!previousTasks.contains(source)) throw new BusinessException("inputFrom 必须引用所选路径中已在前面的任务节点：" + source);
            }
            if (suspendAfterTask(node) && !usesAx(node)) {
                var runtime = selectRuntime(node);
                if (runtime == null || !com.eap.platform.substrate.SubstrateActorRef.isSubstrate(runtime.getProtocol())) {
                    throw new BusinessException("suspendAfterTask 仅支持绑定可用 Substrate Runtime 的任务节点");
                }
            }
            if (isTaskNode(node)) previousTasks.add(node.nodeKey());
            if (!Set.of("START", "END", "DECISION", "AGENT", "ARTIFACT").contains(node.nodeType().toUpperCase(Locale.ROOT))) {
                throw new BusinessException("不支持的执行节点类型");
            }
            if (usesAx(node)) { ax.validateWorkflowAgent(node.agentId()); axMode(node); }
            if (isTaskNode(node) && !usesAx(node) && (node.agentId() == null || selectRuntime(node) == null)) {
                throw new BusinessException("节点缺少有效智能体或可用运行实例（工具任务需要已验证的实例专属接入）：" + node.label());
            }
        }
        WorkflowExecution run = new WorkflowExecution();
        run.submissionKey = submissionKey; run.requestFingerprint = fingerprint;
        run.workflowId = workflowId; run.runKey = "wf-exec-" + UUID.randomUUID(); run.status = "QUEUED";
        run.objective = request.objective() == null || request.objective().isBlank() ? detail.workflow().name() : request.objective();
        run.requester = requester == null || requester.isBlank() ? "console" : requester.substring(0, Math.min(requester.length(), 128));
        run.snapshotJson = json(new Snapshot(path, List.of(), context));
        if (run.snapshotJson.getBytes(StandardCharsets.UTF_8).length > 60000) throw new BusinessException("工作流快照超过 60KB，请精简配置");
        run.createdAt = Instant.now(); run.recoveryVersion = 1;
        scheduling.lockEnrollment();
        repository.saveAndFlush(run);
        scheduling.enroll(run, requester == null || requester.isBlank() ? "console" : requester);
        coverage.enrollControls(run.id,run.runKey,path);
        for (int i = 0; i < path.size(); i++) {
            var node = path.get(i);
            if (isTaskNode(node) && !usesAx(node) && WorkflowToolConfig.read(node) == null)
                coverage.enrollRuntimeTask(run.id, i, run.runKey + ":" + i, node.agentId(),
                        WorkflowObservationContract.expectedModels(node));
        }
        return response(run);
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
        WorkflowExecution run = scheduling.claim();
        if (run == null) return;
        List<Step> steps = new ArrayList<>();
        try {
            Snapshot snapshot = mapper.readValue(run.snapshotJson, Snapshot.class);
            safety.createSession(new SafetyDtos.SessionRequest(run.runKey, null, run.objective));
            for (var node : snapshot.nodes()) {
                if (!progress(run, steps, "RUNNING", null, false)) return;
                boolean axNode = usesAx(node);
                AgentRuntimeInstance runtime = isTaskNode(node) && !axNode ? selectRuntime(node) : null;
                if (isTaskNode(node) && !axNode && runtime == null) {
                    progress(run, steps, "FAILED", "Runtime 已离线或 Agent 不再启用，未派发下一节点", true); return;
                }
                String objective = taskObjective(node, run.objective);
                Map<String, Object> context = new LinkedHashMap<>(snapshot.context());
                var tool = WorkflowToolConfig.read(node);
                var outputContract = WorkflowOutputContract.read(node);
                if (tool != null) context.put("eapTool", tool);
                Map<String, Object> previous = new LinkedHashMap<>();
                for (String source : inputFrom(node)) {
                    Step result = steps.stream().filter(step -> step.nodeKey().equals(source)
                            && "SUCCEEDED".equals(step.status())).findFirst().orElse(null);
                    if (result == null || result.output() == null || result.output().isNull()) {
                        progress(run, steps, "FAILED", "上游节点未提供 output，未派发下一节点：" + source, true); return;
                    }
                    var sourceNode = snapshot.nodes().stream().filter(n -> n.nodeKey().equals(source)).findFirst().orElseThrow();
                    var accepted = WorkflowOutputContract.check(WorkflowOutputContract.read(sourceNode), result.status(), result.output());
                    if (accepted.validation() != null && (!"PASSED".equals(accepted.validation().status())
                            || !accepted.validation().equals(result.resultValidation()))) {
                        progress(run, steps, "FAILED", "上游结果未通过原契约核验，未派发下一节点", true); return;
                    }
                    previous.put(source, Map.of("taskKey", result.taskKey(), "agentId", result.agentId(),
                            "output", accepted.accepted(), "outputSha256", digest(json(accepted.accepted())),
                            "originalOutputSha256", result.outputSha256()));
                }
                if (!previous.isEmpty()) context.put("eapPreviousResults", previous);
                int sequence = steps.size();
                String taskKey = run.runKey + ":" + sequence;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("protocol", "EAP_HTTP_TASK_V1"); payload.put("taskKey", taskKey); payload.put("workflowRunKey", run.runKey);
                payload.put("agentId", node.agentId()); payload.put("nodeKey", node.nodeKey()); payload.put("nodeType", node.nodeType());
                payload.put("capability", node.capability()); payload.put("objective", objective); payload.put("context", context);
                payload.put("configJson", node.configJson());
                if (json(payload).getBytes(StandardCharsets.UTF_8).length > 60000) {
                    progress(run, steps, "FAILED", "合并上游结果后的任务请求超过 60KB，未派发下一节点", true); return;
                }
                var decision = safety.evaluate(new SafetyDtos.SafetyEventRequest(run.runKey, node.agentId(), null,
                        "ARTIFACT".equalsIgnoreCase(node.nodeType()) ? SafetyEventType.ARTIFACT_WRITE : SafetyEventType.AGENT_MESSAGE,
                        "ARTIFACT".equalsIgnoreCase(node.nodeType()) ? "write_artifact" : isTaskNode(node) ? "invoke_agent" : "workflow_transition",
                        runtime != null ? runtime.getEndpoint() : "workflow://" + run.workflowId + "/" + node.nodeKey(),
                        "workflow execution " + run.runKey,
                        json(Map.of("objective", objective, "context", context,
                                "config", node.configJson() == null ? "" : node.configJson())))).decision().decision();
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
                if (axNode) {
                    // Durable task includes workflow/node linkage before the AX worker can create any sandbox.
                    scheduling.begin(run, sequence, "ASYNC_WAIT");
                    com.eap.platform.ax.AxService.View task;
                    try { task = ax.submitWorkflow(run.workflowId, run.runKey, node.nodeKey(), node.agentId(),
                            objective, context, axMode(node), run.requester, run.startedAt.plusSeconds(properties.getTimeoutSeconds())); }
                    catch (BusinessException ex) { scheduling.finish(run, sequence, "ASYNC_WAIT", "FAILED"); progress(run, steps, "FAILED", ex.getMessage(), true); return; }
                    String instance = "ax://" + task.actor();
                    steps.add(new Step(sequence,node.nodeKey(),node.label(),node.agentId(),task.taskKey(),instance,
                            "DISPATCHING","ALLOW","AX 任务已关联，等待业务回执与物理休眠",null));
                    if (!progress(run, steps, "RUNNING", null, false)) return;
                    while (true) {
                        task = ax.get(task.taskKey());
                        if (Set.of("SUCCEEDED","FAILED","EXECUTION_UNKNOWN","BLOCKED","WAITING_APPROVAL").contains(task.status())) {
                            var output = ax.content(task.taskKey()).path("result").get("output");
                            steps.set(sequence,new Step(sequence,node.nodeKey(),node.label(),node.agentId(),task.taskKey(),instance,
                                    task.status(),task.status().equals("BLOCKED")?"DENY":task.status().equals("WAITING_APPROVAL")?"REQUIRE_APPROVAL":"ALLOW",
                                    task.reason(),null,output,output==null?null:digest(json(output))));
                            if (!"SUCCEEDED".equals(task.status())) { scheduling.finish(run, sequence, "ASYNC_WAIT", task.status()); progress(run,steps,task.status(),task.reason(),true); return; }
                            // AX sandboxes are dedicated; always confirm resource release before advancing.
                            if (task.suspended()) { scheduling.finish(run, sequence, "ASYNC_WAIT", "CONFIRMED"); if(!progress(run,steps,"RUNNING",null,false))return; break; }
                        }
                        if (!progress(run,steps,"RUNNING","等待 AX 回执或沙箱休眠；未重复派发",false))return;
                        Thread.sleep(500);
                    }
                    continue;
                }
                var binding = configurations.capture(runtime);
                steps.add(new Step(sequence, node.nodeKey(), node.label(), node.agentId(), taskKey, runtime.getInstanceKey(), "DISPATCHING", "ALLOW", "已记录派发意图", null).bound(binding));
                if (!progress(run, steps, "RUNNING", null, false)) return;
                if (run.recoveryVersion == 1 && tool != null && ScopedToolService.TOOL.equals(tool.path("name").asText())) {
                    var args = tool.path("arguments");
                    toolRecovery.prepare(run.id, run.ownerEpoch, run.ownerId, sequence, node.nodeKey(), taskKey,
                            runtime.getInstanceKey(), new ScopedToolDtos.Execute(args.path("grantId").asText(),
                            args.path("operationId").asText(), args.path("resourceId").asText(), args.path("delta").longValue()));
                    // Ownership may expire while binding the intent; never dispatch after losing it.
                    if (!progress(run, steps, "RUNNING", null, false)) return;
                }
                RuntimeWorkService.Ticket admission;
                try {
                    admission = com.eap.platform.substrate.SubstrateActorRef.isSubstrate(runtime.getProtocol())
                            ? work.start(runtime.getEndpoint(), "WORKFLOW") : null;
                } catch (BusinessException rejected) {
                    steps.set(sequence, new Step(sequence, node.nodeKey(), node.label(), node.agentId(), taskKey,
                            runtime.getInstanceKey(), "FAILED", "ALLOW", "实例回收尚未确认，未派发此任务", null).bound(binding));
                    progress(run, steps, "FAILED", "实例回收尚未确认，未派发此任务", true); return;
                }
                try (var occupancy = admission) {
                    scheduling.begin(run, sequence, "PREPARATION");
                    boolean[] dispatched = {false};
                    var receipt = client.invoke(runtime, payload, run.startedAt.plusSeconds(properties.getTimeoutSeconds()), () -> {
                        scheduling.finish(run, sequence, "PREPARATION", "CONFIRMED");
                        scheduling.begin(run, sequence, "TASK_WAIT");
                        dispatched[0] = true;
                    });
                    scheduling.finish(run, sequence, dispatched[0] ? "TASK_WAIT" : "PREPARATION", receipt.status());
                    var validation = WorkflowOutputContract.check(outputContract, receipt.status(), receipt.output());
                    steps.set(sequence, new Step(sequence, node.nodeKey(), node.label(), node.agentId(), taskKey, runtime.getInstanceKey(),
                            receipt.status(), "ALLOW", receipt.reason(), receipt.httpStatus(), receipt.output(),
                            receipt.output() == null ? null : digest(json(receipt.output()))).bound(binding).validated(validation.validation()));
                    boolean known = Set.of("SUCCEEDED", "FAILED").contains(receipt.status());
                    if (occupancy != null) occupancy.complete(known);
                    if (!known) { progress(run, steps, receipt.status(), receipt.reason(), true); return; }
                    // Save the business receipt while responsibility remains RUNNING so RECLAIM can be audited separately.
                    if (!progress(run, steps, "RUNNING", receipt.reason(), false)) return;
                    if (suspendAfterTask(node) && occupancy != null && dispatched[0] && Integer.valueOf(200).equals(receipt.httpStatus())) {
                        scheduling.begin(run, sequence, "RECLAIM");
                        String reclaimed = scheduling.reclaim(run, sequence, () -> occupancy.reclaim(() -> client.suspend(runtime)));
                        steps.set(sequence, steps.get(sequence).reclaimed(reclaimed));
                        if (!"CONFIRMED".equals(reclaimed)) {
                            String reason = "FAILED".equals(receipt.status()) ? receipt.reason()
                                    : "任务已完成并保存结果，但 Actor 休眠未确认；未派发下一节点，请核对实例";
                            progress(run, steps, "FAILED", reason, true); return;
                        }
                    }
                    if (!"SUCCEEDED".equals(receipt.status())) { progress(run, steps, receipt.status(), receipt.reason(), true); return; }
                    if (validation.validation() != null && !"PASSED".equals(validation.validation().status())) {
                        progress(run, steps, "FAILED", "任务已完成，但结果未通过结构契约；原始结果已保存，未派发下一节点", true); return;
                    }
                    if (!progress(run, steps, "RUNNING", null, false)) return;
                }
            }
            progress(run, steps, "SUCCEEDED", "所选路径全部完成；所有任务均已收到匹配标识的业务终态", true);
        } catch (Exception exception) {
            progress(run, steps, "EXECUTION_UNKNOWN", "执行中断，已发生的外部效果未回滚；请按任务标识核对", true);
        }
    }

    private boolean progress(WorkflowExecution run, List<Step> steps, String status, String reason, boolean terminal) {
        return Boolean.TRUE.equals(transaction.execute(ignored -> {
            Instant now = Instant.now();
            if (!now.isBefore(run.startedAt.plusSeconds(properties.getTimeoutSeconds()))) {
                updateProgress(run, steps, "EXECUTION_UNKNOWN",
                        "执行超过期限，外部效果可能已发生；请按任务标识核对，未自动重试", now);
                return false;
            }
            return updateProgress(run, steps, status, reason, terminal ? now : null) == 1;
        }));
    }

    private int updateProgress(WorkflowExecution run,List<Step> steps,String status,String reason,Instant completedAt) {
        int updated = run.recoveryVersion == 1
                ? repository.progressOwned(run.id,run.ownerEpoch,run.ownerId,json(steps),status,reason,completedAt)
                : repository.progress(run.id,json(steps),status,reason,completedAt);
        if (updated == 1) coverage.controlsProgress(run.id,run.ownerEpoch,steps);
        return updated;
    }

    private AgentRuntimeInstance selectRuntime(WorkflowDtos.NodeResponse node) {
        Long id = node.agentId();
        boolean tool = WorkflowToolConfig.read(node) != null;
        if (id == null || agents.findById(id).filter(agent -> agent.getLifecycleStatus() == AgentLifecycleStatus.ACTIVE).isEmpty()) return null;
        Instant cutoff = lease.cutoff();
        // Suspended Substrate actors send no heartbeats; the task client wakes them before dispatch.
        return runtimes.findByAgentId(id).stream().filter(runtime -> !tool || runtime.hasBoundIdentity())
                .filter(runtime -> !runtime.hasBoundIdentity() || connections.executable(runtime)).filter(runtime -> "EAP_HTTP_TASK_V1".equals(runtime.getProtocol())
                        && lease.effectiveStatus(runtime, cutoff) == RuntimeStatus.ONLINE
                        || com.eap.platform.substrate.SubstrateActorRef.isSubstrate(runtime.getProtocol())
                        && runtime.getStatus() != RuntimeStatus.OFFLINE)
                .min(Comparator.comparing(AgentRuntimeInstance::getLoadScore)).orElse(null);
    }
    private boolean usesAx(WorkflowDtos.NodeResponse node) {
        try {
            var config=mapper.readTree(node.configJson()==null?"{}":node.configJson());
            var backend=config.get("executionBackend");
            if(backend==null)return false;
            if(!isTaskNode(node) || !backend.isTextual() || !Set.of("RUNTIME","AX").contains(backend.asText()))throw new IllegalArgumentException();
            return "AX".equals(backend.asText());
        } catch(Exception ex){throw new BusinessException("executionBackend 必须为任务节点的 RUNTIME 或 AX");}
    }
    private String axMode(WorkflowDtos.NodeResponse node) {
        try {
            var value=mapper.readTree(node.configJson()==null?"{}":node.configJson()).get("axMode");
            if(value==null)return "LLM";
            if(!value.isTextual() || !Set.of("LLM","ECHO","FAIL").contains(value.asText()))throw new IllegalArgumentException();
            return value.asText();
        } catch(Exception ex){throw new BusinessException("axMode 必须为 LLM、ECHO 或 FAIL");}
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
    /** Raw outputs are deliberately separate from console-visible execution metadata. */
    @Transactional(readOnly = true)
    public Map<String, com.fasterxml.jackson.databind.JsonNode> outputs(Long workflowId, String runKey) {
        var run = repository.findByWorkflowIdAndRunKey(workflowId, runKey)
                .orElseThrow(() -> new BusinessException("执行记录不存在"));
        try {
            Map<String, com.fasterxml.jackson.databind.JsonNode> outputs = new LinkedHashMap<>();
            for (Step step : mapper.readerFor(new TypeReference<List<Step>>() {})
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).<List<Step>>readValue(run.stepsJson)) {
                if ("SUCCEEDED".equals(step.status()) && step.output() != null && !step.output().isNull()) outputs.put(step.nodeKey(), step.output());
            }
            return outputs;
        } catch (Exception exception) { throw new BusinessException("执行结果无法读取"); }
    }

    private String taskObjective(WorkflowDtos.NodeResponse node, String fallback) {
        try {
            var value = mapper.readTree(node.configJson() == null ? "{}" : node.configJson()).get("taskObjective");
            if (value == null) return fallback;
            if (!isTaskNode(node) || !value.isTextual() || value.asText().isBlank() || value.asText().length() > 128) throw new IllegalArgumentException();
            return value.asText();
        } catch (Exception ex) { throw new BusinessException("taskObjective 必须是任务节点的 1–128 字符目标"); }
    }

    private boolean suspendAfterTask(WorkflowDtos.NodeResponse node) {
        try {
            var value = mapper.readTree(node.configJson() == null ? "{}" : node.configJson()).get("suspendAfterTask");
            if (value == null) return false;
            if (!isTaskNode(node) || !value.isBoolean()) throw new IllegalArgumentException();
            return value.booleanValue();
        } catch (Exception exception) { throw new BusinessException("suspendAfterTask 必须是任务节点的布尔值配置"); }
    }

    private List<String> inputFrom(WorkflowDtos.NodeResponse node) {
        try {
            var config = mapper.readTree(node.configJson() == null ? "{}" : node.configJson());
            var sources = config.get("inputFrom");
            if (sources == null) return List.of();
            if (!isTaskNode(node) || !sources.isArray() || sources.size() > 64) throw new IllegalArgumentException();
            List<String> keys = new ArrayList<>();
            for (var source : sources) {
                if (!source.isTextual() || source.asText().isBlank() || keys.contains(source.asText())) throw new IllegalArgumentException();
                keys.add(source.asText());
            }
            return keys;
        } catch (Exception exception) { throw new BusinessException("inputFrom 必须是无重复的上游任务 nodeKey 数组"); }
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
            return new Response(run.runKey, run.workflowId, run.status, mapper.readValue(run.snapshotJson, Snapshot.class).nodes().stream().anyMatch(this::usesAx) ? "EAP_MIXED_TASK_V1" : "EAP_HTTP_TASK_V1", run.objective, run.requester,
                    mapper.readValue(run.stepsJson, new TypeReference<List<Step>>() {}).stream().map(Step::metadata).toList(), run.reason, run.createdAt, run.startedAt, run.completedAt);
        } catch (Exception exception) { throw new BusinessException("执行记录无法读取"); }
    }
}
