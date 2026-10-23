package com.eap.platform.orchestration;

import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.eap.platform.workflow.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.validation.constraints.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One immutable execution per plan. Failed/unknown plans require a new plan, never an implicit replay. */
@Service
public class PlanExecutionService {
    private final AgentPlanRepository plans;
    private final AgentPlanStepRepository steps;
    private final AgentTeamRepository teams;
    private final AgentTeamMemberRepository members;
    private final WorkflowService workflows;
    private final WorkflowExecutionService executions;
    private final ObjectMapper mapper;
    public PlanExecutionService(AgentPlanRepository plans, AgentPlanStepRepository steps, AgentTeamRepository teams,
            AgentTeamMemberRepository members, WorkflowService workflows, WorkflowExecutionService executions, ObjectMapper mapper) {
        this.plans = plans; this.steps = steps; this.teams = teams; this.members = members;
        this.workflows = workflows; this.executions = executions; this.mapper = mapper;
    }
    public record Request(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String requestKey,
                          Map<String, Object> context, boolean suspendAfterTask) {}
    public record Response(Long planId, Long workflowId, WorkflowExecutionService.Response execution) {}

    @Transactional
    public Response submit(Long planId, Request request, String requester) {
        var plan = locked(planId);
        if (request.requestKey() == null || !request.requestKey().matches("[A-Za-z0-9_-]{1,64}"))
            throw new BusinessException("requestKey 必须是 1–64 位字母、数字、下划线或短横线");
        Map<String, Object> context = request.context() == null ? Map.of() : request.context();
        if (context.containsKey("eapPlan") || context.containsKey("eapPreviousResults")) throw new BusinessException("上下文包含执行器保留字段");
        String fingerprint = fingerprint(Map.of("context", context, "suspendAfterTask", request.suspendAfterTask()));
        if (plan.executionRunKey != null) {
            if (!request.requestKey().equals(plan.executionRequestKey) || !fingerprint.equals(plan.executionFingerprint))
                throw new ConflictException("计划已有执行，不能改变参数或重复派发；请查看原执行，另建计划才能重新执行");
            return project(plan);
        }
        if (plan.status != PlanStatus.READY) throw new BusinessException("只有 READY 计划可以提交真实执行");
        var team = teams.findById(plan.teamId).orElseThrow(() -> new BusinessException("团队不存在"));
        if (team.status != TeamStatus.ACTIVE) throw new BusinessException("团队未启用");
        var ordered = steps.findByPlanIdOrderByStepOrder(planId);
        if (ordered.isEmpty() || ordered.size() > 62) throw new BusinessException("计划要求 1–62 个步骤");
        var membership = members.findByTeamId(plan.teamId);
        Set<Integer> orders = new HashSet<>();
        Map<Long, com.fasterxml.jackson.databind.JsonNode> outputContracts = new HashMap<>();
        for (var step : ordered) {
            if (step.stepOrder == null || step.stepOrder < 1 || !orders.add(step.stepOrder)) throw new BusinessException("步骤顺序必须为不重复的正整数");
            if (step.assignedAgentId == null || membership.stream().noneMatch(m -> m.agentId.equals(step.assignedAgentId)))
                throw new BusinessException("每个步骤必须分配给本团队成员");
            String contract = WorkflowOutputContract.validateDefinition(step.outputContractJson, step.executionBackend);
            if (contract != null) {
                try { outputContracts.put(step.id, mapper.readTree(contract)); }
                catch (Exception ex) { throw new BusinessException("结果契约无法读取"); }
            }
            if (step.status != StepStatus.ASSIGNED && step.status != StepStatus.PENDING) throw new BusinessException("步骤已有人工执行状态，请核对后另建计划");
        }
        var workflow = workflows.create(new WorkflowDtos.WorkflowRequest("plan-" + planId + "-" + UUID.randomUUID(), plan.goal, "计划 " + planId + " 的只读执行快照"));
        node(workflow.id(), "start", "START", null, "开始", null, Map.of(), 0);
        String previous = "start";
        int position = 1;
        for (var step : ordered) {
            String key = "step-" + step.id;
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("taskObjective", step.title);
            config.put("executionBackend", step.executionBackend);
            if (outputContracts.containsKey(step.id)) config.put("eapOutput", outputContracts.get(step.id));
            config.put("suspendAfterTask", request.suspendAfterTask());
            if (!previous.equals("start")) config.put("inputFrom", List.of(previous));
            node(workflow.id(), key, "AGENT", step.assignedAgentId, step.title, step.requiredCapability, config, position++);
            workflows.addEdge(workflow.id(), new WorkflowDtos.EdgeRequest(previous, key, "always"));
            previous = key;
        }
        node(workflow.id(), "end", "END", null, "结束", null, Map.of(), position);
        workflows.addEdge(workflow.id(), new WorkflowDtos.EdgeRequest(previous, "end", "always"));
        Map<String, Object> merged = new LinkedHashMap<>(context);
        merged.put("eapPlan", Map.of("planId", plan.id, "teamId", plan.teamId, "goal", plan.goal,
                "contextSummary", plan.contextSummary == null ? "" : plan.contextSummary,
                "coordinationMode", team.coordinationMode, "workspacePolicy", team.workspacePolicy,
                "members", membership.stream().map(OrchestrationDtos::from).toList()));
        var run = executions.submit(workflow.id(), new WorkflowDtos.RunRequest(plan.goal, merged, request.requestKey()), requester);
        // This transaction commits the frozen workflow, queued execution and plan link together.
        plan.executionWorkflowId = workflow.id(); plan.executionRunKey = run.runKey();
        plan.executionRequestKey = request.requestKey(); plan.executionFingerprint = fingerprint;
        plan.status = PlanStatus.QUEUED;
        plans.saveAndFlush(plan);
        return new Response(plan.id, workflow.id(), run);
    }

    @Transactional
    public Response synchronize(Long planId) {
        var plan = locked(planId);
        return plan.executionRunKey == null ? null : project(plan);
    }
    private Response project(AgentPlan plan) {
        var run = executions.get(plan.executionWorkflowId, plan.executionRunKey);
        plan.status = switch (run.status()) {
            case "SUCCEEDED" -> PlanStatus.COMPLETED;
            case "QUEUED" -> PlanStatus.QUEUED;
            case "RUNNING" -> PlanStatus.RUNNING;
            case "BLOCKED" -> PlanStatus.BLOCKED;
            case "WAITING_APPROVAL" -> PlanStatus.WAITING_APPROVAL;
            case "EXECUTION_UNKNOWN" -> PlanStatus.EXECUTION_UNKNOWN;
            default -> PlanStatus.FAILED;
        };
        boolean terminal = !Set.of("QUEUED", "RUNNING").contains(run.status());
        for (var step : steps.findByPlanIdOrderByStepOrder(plan.id)) {
            var execution = run.steps().stream().filter(s -> s.nodeKey().equals("step-" + step.id)).findFirst();
            step.status = execution.map(s -> {
                if (s.resultValidation() != null && "REJECTED".equals(s.resultValidation().status())) return StepStatus.FAILED;
                return switch (s.status()) {
                case "SUCCEEDED" -> StepStatus.DONE;
                case "DISPATCHING" -> terminal ? StepStatus.EXECUTION_UNKNOWN : StepStatus.RUNNING;
                case "BLOCKED" -> StepStatus.BLOCKED;
                case "WAITING_APPROVAL" -> StepStatus.WAITING_APPROVAL;
                case "EXECUTION_UNKNOWN" -> StepStatus.EXECUTION_UNKNOWN;
                default -> StepStatus.FAILED;
                };
            }).orElse(terminal ? StepStatus.SKIPPED : StepStatus.ASSIGNED);
        }
        return new Response(plan.id, plan.executionWorkflowId, run);
    }
    private AgentPlan locked(Long id) { return plans.findForUpdate(id).orElseThrow(() -> new BusinessException("任务计划不存在")); }
    private void node(Long id, String key, String type, Long agentId, String title, String capability, Map<String, Object> config, int position) {
        workflows.upsertNode(id, new WorkflowDtos.NodeRequest(key, title, type, agentId, capability, 80, 60 + position * 160, json(config)));
    }
    private String json(Object value) {
        try { return mapper.writer(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value); }
        catch (Exception ex) { throw new BusinessException("计划数据无法序列化"); }
    }
    private String fingerprint(Object value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json(value).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
