package com.eap.platform.orchestration;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.eap.platform.workflow.WorkflowOutputContract;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrchestrationService {
    private final AgentTeamRepository teamRepository;
    private final AgentTeamMemberRepository memberRepository;
    private final AgentPlanRepository planRepository;
    private final AgentPlanStepRepository stepRepository;
    private final AgentRepository agentRepository;
    private final PlanExecutionService executions;

    public OrchestrationService(
            AgentTeamRepository teamRepository,
            AgentTeamMemberRepository memberRepository,
            AgentPlanRepository planRepository,
            AgentPlanStepRepository stepRepository,
            AgentRepository agentRepository, PlanExecutionService executions
    ) {
        this.teamRepository = teamRepository;
        this.memberRepository = memberRepository;
        this.planRepository = planRepository;
        this.stepRepository = stepRepository;
        this.agentRepository = agentRepository;
        this.executions = executions;
    }

    @Transactional(readOnly = true)
    public List<OrchestrationDtos.TeamResponse> listTeams() {
        return teamRepository.findAll().stream().map(OrchestrationDtos::from).toList();
    }

    @Transactional
    public OrchestrationDtos.TeamResponse createTeam(OrchestrationDtos.TeamRequest request) {
        if (teamRepository.existsByCode(request.code())) {
            throw new BusinessException("团队编码已存在");
        }
        AgentTeam team = new AgentTeam();
        team.code = request.code();
        team.name = request.name();
        team.objective = request.objective();
        team.coordinationMode = request.coordinationMode();
        team.workspacePolicy = request.workspacePolicy();
        team.status = TeamStatus.ACTIVE;
        return OrchestrationDtos.from(teamRepository.saveAndFlush(team));
    }

    @Transactional(readOnly = true)
    public List<OrchestrationDtos.MemberResponse> listMembers(Long teamId) {
        return memberRepository.findByTeamId(teamId).stream().map(OrchestrationDtos::from).toList();
    }

    @Transactional
    public OrchestrationDtos.MemberResponse addMember(Long teamId, OrchestrationDtos.MemberRequest request) {
        var team = lockedTeam(teamId);
        if (!agentRepository.existsById(request.agentId())) {
            throw new BusinessException("智能体不存在");
        }
        if (memberRepository.findCurrentByTeamId(teamId).stream().anyMatch(m -> m.agentId.equals(request.agentId()))) {
            throw new ConflictException("该智能体已经是团队成员");
        }
        AgentTeamMember member = new AgentTeamMember();
        member.teamId = teamId;
        member.agentId = request.agentId();
        member.role = request.role();
        member.responsibility = request.responsibility();
        touch(team);
        return OrchestrationDtos.from(memberRepository.saveAndFlush(member));
    }

    @Transactional(readOnly = true)
    public List<OrchestrationDtos.PlanResponse> listPlans(Long teamId) {
        return planRepository.findByTeamId(teamId).stream().map(OrchestrationDtos::from).toList();
    }

    @Transactional
    public OrchestrationDtos.PlanResponse createPlan(OrchestrationDtos.PlanRequest request) {
        lockedTeam(request.teamId());
        AgentPlan plan = new AgentPlan();
        plan.teamId = request.teamId();
        plan.goal = request.goal();
        plan.contextSummary = request.contextSummary();
        plan.status = PlanStatus.READY;
        return OrchestrationDtos.from(planRepository.saveAndFlush(plan));
    }

    @Transactional
    public OrchestrationDtos.PlanDetail getPlan(Long planId) {
        executions.synchronize(planId);
        AgentPlan plan = findPlan(planId);
        planRepository.flush();
        List<OrchestrationDtos.StepResponse> steps = stepRepository.findByPlanIdOrderByStepOrder(planId).stream()
                .map(OrchestrationDtos::from)
                .toList();
        return new OrchestrationDtos.PlanDetail(OrchestrationDtos.from(plan), steps);
    }

    @Transactional
    public OrchestrationDtos.StepResponse addStep(Long planId, OrchestrationDtos.StepRequest request) {
        var plan = editablePlan(planId);
        var current = stepRepository.findCurrentByPlanId(planId);
        if (current.size() >= 62) throw new BusinessException("计划最多包含62个步骤");
        if (request.stepOrder() == null || request.stepOrder() < 1 || current.stream().anyMatch(s -> s.stepOrder.equals(request.stepOrder()))) throw new BusinessException("步骤顺序必须为不重复的正整数");
        AgentPlanStep step = new AgentPlanStep();
        step.planId = planId;
        step.stepOrder = request.stepOrder();
        step.title = request.title();
        step.requiredCapability = request.requiredCapability();
        step.executionBackend = request.executionBackend()==null?"RUNTIME":request.executionBackend();
        if(!java.util.Set.of("RUNTIME","AX").contains(step.executionBackend))throw new BusinessException("执行后端仅支持 RUNTIME 或 AX");
        step.outputContractJson = WorkflowOutputContract.validateDefinition(request.outputContractJson(), step.executionBackend);
        step.status = StepStatus.PENDING;
        touch(plan);
        return OrchestrationDtos.from(stepRepository.saveAndFlush(step));
    }

    @Transactional
    public OrchestrationDtos.StepResponse updateOutputContract(Long stepId, OrchestrationDtos.OutputContractRequest request) {
        var plan = editablePlan(stepPlanId(stepId));
        AgentPlanStep step = lockedStep(stepId);
        step.outputContractJson = WorkflowOutputContract.validateDefinition(request.outputContractJson(), step.executionBackend);
        touch(plan);
        return OrchestrationDtos.from(step);
    }

    @Transactional
    public OrchestrationDtos.StepResponse assignStep(Long stepId, OrchestrationDtos.AssignRequest request) {
        var plan = editablePlan(stepPlanId(stepId));
        AgentPlanStep step = lockedStep(stepId);
        if (request.status() != StepStatus.PENDING && request.status() != StepStatus.ASSIGNED) throw new BusinessException("运行状态由执行引擎回写，不能手工标记");
        if (request.assignedAgentId() == null && request.status() == StepStatus.ASSIGNED) throw new BusinessException("分配状态必须指定团队成员");
        if (request.assignedAgentId() != null && memberRepository.findCurrentByTeamId(plan.teamId).stream().noneMatch(m -> m.agentId.equals(request.assignedAgentId()))) throw new BusinessException("只能分配给本团队成员");
        if (request.assignedAgentId() != null && !agentRepository.existsById(request.assignedAgentId())) {
            throw new BusinessException("智能体不存在");
        }
        step.assignedAgentId = request.assignedAgentId();
        step.status = request.status();
        touch(plan);
        return OrchestrationDtos.from(step);
    }

    @Transactional
    public OrchestrationDtos.TeamResponse updateTeam(Long id, OrchestrationDtos.TeamUpdateRequest request) {
        var team = lockedTeam(id);
        revision(team.revision, request.expectedRevision());
        team.name = request.name().trim();
        team.objective = request.objective();
        team.coordinationMode = request.coordinationMode().trim();
        team.workspacePolicy = request.workspacePolicy().trim();
        touch(team);
        return OrchestrationDtos.from(team);
    }

    @Transactional
    public void deleteTeam(Long id, OrchestrationDtos.RevisionRequest request) {
        var team = lockedTeam(id);
        revision(team.revision, request.expectedRevision());
        if (!memberRepository.findCurrentByTeamId(id).isEmpty() || !planRepository.findByTeamId(id).isEmpty())
            throw new ConflictException("只能删除没有成员和计划的空团队");
        teamRepository.delete(team);
    }

    @Transactional
    public OrchestrationDtos.MemberResponse updateMember(Long teamId, Long memberId, OrchestrationDtos.MemberUpdateRequest request) {
        var team = lockedTeam(teamId);
        revision(team.revision, request.expectedRevision());
        var member = memberRepository.findCurrentByTeamId(teamId).stream().filter(m -> m.id.equals(memberId))
                .findFirst().orElseThrow(() -> new BusinessException("团队成员不存在"));
        member.role = request.role().trim();
        member.responsibility = request.responsibility().trim();
        touch(team);
        return OrchestrationDtos.from(member);
    }

    @Transactional
    public void removeMember(Long teamId, Long memberId, OrchestrationDtos.RevisionRequest request) {
        var team = lockedTeam(teamId);
        revision(team.revision, request.expectedRevision());
        var member = memberRepository.findCurrentByTeamId(teamId).stream().filter(m -> m.id.equals(memberId))
                .findFirst().orElseThrow(() -> new BusinessException("团队成员不存在"));
        if (stepRepository.countUnsubmittedAssignments(teamId, member.agentId) != 0)
            throw new ConflictException("该成员仍被未提交计划使用，请先取消或调整步骤分配");
        memberRepository.delete(member);
        touch(team);
    }

    @Transactional
    public OrchestrationDtos.PlanResponse updatePlan(Long id, OrchestrationDtos.PlanUpdateRequest request) {
        var plan = editablePlan(id);
        revision(plan.revision, request.expectedRevision());
        plan.goal = request.goal().trim();
        plan.contextSummary = request.contextSummary();
        touch(plan);
        return OrchestrationDtos.from(plan);
    }

    @Transactional
    public void deletePlan(Long id, OrchestrationDtos.RevisionRequest request) {
        var plan = editablePlan(id);
        revision(plan.revision, request.expectedRevision());
        stepRepository.deleteAll(stepRepository.findCurrentByPlanId(id));
        stepRepository.flush();
        planRepository.delete(plan);
    }

    @Transactional
    public OrchestrationDtos.PlanResponse copyPlan(Long id, OrchestrationDtos.PlanUpdateRequest request) {
        var original = lockedPlan(id);
        revision(original.revision, request.expectedRevision());
        var originalSteps = stepRepository.findCurrentByPlanId(id);
        if (originalSteps.size() > 62) throw new BusinessException("计划最多包含62个步骤");
        var copy = new AgentPlan();
        copy.teamId = original.teamId;
        copy.goal = request.goal().trim();
        copy.contextSummary = request.contextSummary();
        copy.status = PlanStatus.READY;
        planRepository.saveAndFlush(copy);
        for (var source : originalSteps) {
            var step = new AgentPlanStep();
            step.planId = copy.id;
            step.stepOrder = source.stepOrder;
            step.title = source.title;
            step.requiredCapability = source.requiredCapability;
            step.executionBackend = source.executionBackend;
            step.outputContractJson = WorkflowOutputContract.validateDefinition(source.outputContractJson, source.executionBackend);
            step.status = StepStatus.PENDING;
            stepRepository.save(step);
        }
        stepRepository.flush();
        return OrchestrationDtos.from(copy);
    }

    @Transactional
    public OrchestrationDtos.StepResponse updateStep(Long id, OrchestrationDtos.StepUpdateRequest request) {
        var plan = editablePlan(stepPlanId(id));
        revision(plan.revision, request.expectedRevision());
        var step = lockedStep(id);
        if (stepRepository.findCurrentByPlanId(plan.id).stream().anyMatch(s -> !s.id.equals(id) && s.stepOrder.equals(request.stepOrder())))
            throw new ConflictException("步骤顺序已被占用，请使用上移或下移调整顺序");
        step.stepOrder = request.stepOrder();
        step.title = request.title().trim();
        step.requiredCapability = request.requiredCapability().trim();
        step.outputContractJson = WorkflowOutputContract.validateDefinition(request.outputContractJson(), request.executionBackend());
        step.executionBackend = request.executionBackend();
        touch(plan);
        return OrchestrationDtos.from(step);
    }

    @Transactional
    public void deleteStep(Long id, OrchestrationDtos.RevisionRequest request) {
        var plan = editablePlan(stepPlanId(id));
        revision(plan.revision, request.expectedRevision());
        stepRepository.delete(lockedStep(id));
        touch(plan);
    }

    @Transactional
    public OrchestrationDtos.PlanDetail reorderSteps(Long id, OrchestrationDtos.ReorderRequest request) {
        var plan = editablePlan(id);
        revision(plan.revision, request.expectedRevision());
        var current = stepRepository.findCurrentByPlanId(id);
        var ids = request.stepIds();
        if (ids == null || ids.isEmpty() || ids.size() > 62 || ids.size() != current.size()
                || new java.util.HashSet<>(ids).size() != current.size()
                || !new java.util.HashSet<>(ids).equals(current.stream().map(s -> s.id).collect(java.util.stream.Collectors.toSet())))
            throw new ConflictException("步骤列表已变化，请刷新后重新排序");
        for (var step : current) step.stepOrder = ids.indexOf(step.id) + 1;
        touch(plan);
        return new OrchestrationDtos.PlanDetail(OrchestrationDtos.from(plan), current.stream()
                .sorted(java.util.Comparator.comparing(s -> s.stepOrder)).map(OrchestrationDtos::from).toList());
    }

    private void revision(long actual, Long expected) {
        if (expected == null || actual != expected) throw new ConflictException("数据已被更新，请刷新后重新操作");
    }

    private AgentTeam lockedTeam(Long id) {
        return teamRepository.findForUpdate(id).orElseThrow(() -> new BusinessException("智能体团队不存在"));
    }

    private AgentPlan lockedPlan(Long id) {
        Long teamId = planRepository.findTeamIdById(id).orElseThrow(() -> new BusinessException("任务计划不存在"));
        lockedTeam(teamId);
        return planRepository.findForUpdate(id).orElseThrow(() -> new BusinessException("任务计划不存在"));
    }

    private void touch(AgentTeam team) {
        team.updatedAt = java.time.Instant.now();
        teamRepository.saveAndFlush(team);
    }

    private void touch(AgentPlan plan) {
        plan.updatedAt = java.time.Instant.now();
        planRepository.saveAndFlush(plan);
    }

    private Long stepPlanId(Long id) {
        // Do not hydrate a stale entity before waiting for the parent plan lock.
        return stepRepository.findPlanIdById(id).orElseThrow(() -> new BusinessException("计划步骤不存在"));
    }

    private AgentPlanStep lockedStep(Long id) {
        // Lock order is plan -> step; a current read also avoids a MySQL RR snapshot.
        return stepRepository.findForUpdate(id).orElseThrow(() -> new BusinessException("计划步骤不存在"));
    }

    private AgentPlan editablePlan(Long id) {
        var plan = lockedPlan(id);
        if (plan.executionRunKey != null || plan.executionWorkflowId != null || !java.util.Set.of(PlanStatus.DRAFT, PlanStatus.READY).contains(plan.status))
            throw new ConflictException("计划已提交执行或存在历史运行状态，配置已冻结");
        return plan;
    }

    private void ensureTeam(Long teamId) {
        if (!teamRepository.existsById(teamId)) {
            throw new BusinessException("智能体团队不存在");
        }
    }

    private AgentPlan findPlan(Long planId) {
        return planRepository.findById(planId).orElseThrow(() -> new BusinessException("任务计划不存在"));
    }
}

