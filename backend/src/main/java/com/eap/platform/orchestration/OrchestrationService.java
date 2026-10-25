package com.eap.platform.orchestration;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
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
        return OrchestrationDtos.from(teamRepository.save(team));
    }

    @Transactional(readOnly = true)
    public List<OrchestrationDtos.MemberResponse> listMembers(Long teamId) {
        return memberRepository.findByTeamId(teamId).stream().map(OrchestrationDtos::from).toList();
    }

    @Transactional
    public OrchestrationDtos.MemberResponse addMember(Long teamId, OrchestrationDtos.MemberRequest request) {
        ensureTeam(teamId);
        if (!agentRepository.existsById(request.agentId())) {
            throw new BusinessException("智能体不存在");
        }
        AgentTeamMember member = new AgentTeamMember();
        member.teamId = teamId;
        member.agentId = request.agentId();
        member.role = request.role();
        member.responsibility = request.responsibility();
        return OrchestrationDtos.from(memberRepository.save(member));
    }

    @Transactional(readOnly = true)
    public List<OrchestrationDtos.PlanResponse> listPlans(Long teamId) {
        return planRepository.findByTeamId(teamId).stream().map(OrchestrationDtos::from).toList();
    }

    @Transactional
    public OrchestrationDtos.PlanResponse createPlan(OrchestrationDtos.PlanRequest request) {
        ensureTeam(request.teamId());
        AgentPlan plan = new AgentPlan();
        plan.teamId = request.teamId();
        plan.goal = request.goal();
        plan.contextSummary = request.contextSummary();
        plan.status = PlanStatus.READY;
        return OrchestrationDtos.from(planRepository.save(plan));
    }

    @Transactional
    public OrchestrationDtos.PlanDetail getPlan(Long planId) {
        executions.synchronize(planId);
        AgentPlan plan = findPlan(planId);
        List<OrchestrationDtos.StepResponse> steps = stepRepository.findByPlanIdOrderByStepOrder(planId).stream()
                .map(OrchestrationDtos::from)
                .toList();
        return new OrchestrationDtos.PlanDetail(OrchestrationDtos.from(plan), steps);
    }

    @Transactional
    public OrchestrationDtos.StepResponse addStep(Long planId, OrchestrationDtos.StepRequest request) {
        editablePlan(planId);
        if (request.stepOrder() == null || request.stepOrder() < 1 || stepRepository.findByPlanIdOrderByStepOrder(planId).stream().anyMatch(s -> s.stepOrder.equals(request.stepOrder()))) throw new BusinessException("步骤顺序必须为不重复的正整数");
        AgentPlanStep step = new AgentPlanStep();
        step.planId = planId;
        step.stepOrder = request.stepOrder();
        step.title = request.title();
        step.requiredCapability = request.requiredCapability();
        step.executionBackend = request.executionBackend()==null?"RUNTIME":request.executionBackend();
        if(!java.util.Set.of("RUNTIME","AX").contains(step.executionBackend))throw new BusinessException("执行后端仅支持 RUNTIME 或 AX");
        step.outputContractJson = WorkflowOutputContract.validateDefinition(request.outputContractJson(), step.executionBackend);
        step.status = StepStatus.PENDING;
        return OrchestrationDtos.from(stepRepository.save(step));
    }

    @Transactional
    public OrchestrationDtos.StepResponse updateOutputContract(Long stepId, OrchestrationDtos.OutputContractRequest request) {
        editablePlan(stepPlanId(stepId));
        AgentPlanStep step = lockedStep(stepId);
        step.outputContractJson = WorkflowOutputContract.validateDefinition(request.outputContractJson(), step.executionBackend);
        return OrchestrationDtos.from(step);
    }

    @Transactional
    public OrchestrationDtos.StepResponse assignStep(Long stepId, OrchestrationDtos.AssignRequest request) {
        var plan = editablePlan(stepPlanId(stepId));
        AgentPlanStep step = lockedStep(stepId);
        if (request.status() != StepStatus.PENDING && request.status() != StepStatus.ASSIGNED) throw new BusinessException("运行状态由执行引擎回写，不能手工标记");
        if (request.assignedAgentId() == null && request.status() == StepStatus.ASSIGNED) throw new BusinessException("分配状态必须指定团队成员");
        if (request.assignedAgentId() != null && memberRepository.findByTeamId(plan.teamId).stream().noneMatch(m -> m.agentId.equals(request.assignedAgentId()))) throw new BusinessException("只能分配给本团队成员");
        if (request.assignedAgentId() != null && !agentRepository.existsById(request.assignedAgentId())) {
            throw new BusinessException("智能体不存在");
        }
        step.assignedAgentId = request.assignedAgentId();
        step.status = request.status();
        return OrchestrationDtos.from(step);
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
        var plan = planRepository.findForUpdate(id).orElseThrow(() -> new BusinessException("任务计划不存在"));
        if (plan.executionRunKey != null) throw new com.eap.platform.common.ConflictException("计划已提交执行，步骤与分配已冻结");
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

