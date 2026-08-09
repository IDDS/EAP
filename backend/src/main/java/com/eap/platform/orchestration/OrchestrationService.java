package com.eap.platform.orchestration;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
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

    public OrchestrationService(
            AgentTeamRepository teamRepository,
            AgentTeamMemberRepository memberRepository,
            AgentPlanRepository planRepository,
            AgentPlanStepRepository stepRepository,
            AgentRepository agentRepository
    ) {
        this.teamRepository = teamRepository;
        this.memberRepository = memberRepository;
        this.planRepository = planRepository;
        this.stepRepository = stepRepository;
        this.agentRepository = agentRepository;
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

    @Transactional(readOnly = true)
    public OrchestrationDtos.PlanDetail getPlan(Long planId) {
        AgentPlan plan = findPlan(planId);
        List<OrchestrationDtos.StepResponse> steps = stepRepository.findByPlanIdOrderByStepOrder(planId).stream()
                .map(OrchestrationDtos::from)
                .toList();
        return new OrchestrationDtos.PlanDetail(OrchestrationDtos.from(plan), steps);
    }

    @Transactional
    public OrchestrationDtos.StepResponse addStep(Long planId, OrchestrationDtos.StepRequest request) {
        findPlan(planId);
        AgentPlanStep step = new AgentPlanStep();
        step.planId = planId;
        step.stepOrder = request.stepOrder();
        step.title = request.title();
        step.requiredCapability = request.requiredCapability();
        step.status = StepStatus.PENDING;
        return OrchestrationDtos.from(stepRepository.save(step));
    }

    @Transactional
    public OrchestrationDtos.StepResponse assignStep(Long stepId, OrchestrationDtos.AssignRequest request) {
        AgentPlanStep step = stepRepository.findById(stepId).orElseThrow(() -> new BusinessException("计划步骤不存在"));
        if (request.assignedAgentId() != null && !agentRepository.existsById(request.assignedAgentId())) {
            throw new BusinessException("智能体不存在");
        }
        step.assignedAgentId = request.assignedAgentId();
        step.status = request.status();
        return OrchestrationDtos.from(step);
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

