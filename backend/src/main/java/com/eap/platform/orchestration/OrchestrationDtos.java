package com.eap.platform.orchestration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class OrchestrationDtos {
    private OrchestrationDtos() {
    }

    public record TeamRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 512) String objective,
            @NotBlank @Size(max = 32) String coordinationMode,
            @NotBlank @Size(max = 128) String workspacePolicy
    ) {
    }

    public record MemberRequest(@NotNull Long agentId, @NotBlank @Size(max = 64) String role, @NotBlank @Size(max = 128) String responsibility) {
    }

    public record PlanRequest(@NotNull Long teamId, @NotBlank @Size(max = 128) String goal, @Size(max = 1024) String contextSummary) {
    }

    public record StepRequest(@NotNull @jakarta.validation.constraints.Min(1) Integer stepOrder, @NotBlank @Size(max = 128) String title, @NotBlank @Size(max = 128) String requiredCapability,
            @jakarta.validation.constraints.Pattern(regexp="RUNTIME|AX") String executionBackend, @Size(max = 8192) String outputContractJson) {
        public StepRequest(Integer stepOrder,String title,String requiredCapability){this(stepOrder,title,requiredCapability,"RUNTIME",null);}
        public StepRequest(Integer stepOrder,String title,String requiredCapability,String executionBackend){this(stepOrder,title,requiredCapability,executionBackend,null);}
    }

    public record OutputContractRequest(@Size(max = 8192) String outputContractJson) {}

    public record AssignRequest(Long assignedAgentId, @NotNull StepStatus status) {
    }

    public record RevisionRequest(@NotNull @jakarta.validation.constraints.Min(0) Long expectedRevision) {}

    public record TeamUpdateRequest(@NotNull @jakarta.validation.constraints.Min(0) Long expectedRevision,
            @NotBlank @Size(max = 128) String name, @Size(max = 512) String objective,
            @NotBlank @Size(max = 32) String coordinationMode, @NotBlank @Size(max = 128) String workspacePolicy) {}

    public record MemberUpdateRequest(@NotNull @jakarta.validation.constraints.Min(0) Long expectedRevision,
            @NotBlank @Size(max = 64) String role, @NotBlank @Size(max = 128) String responsibility) {}

    public record PlanUpdateRequest(@NotNull @jakarta.validation.constraints.Min(0) Long expectedRevision,
            @NotBlank @Size(max = 128) String goal, @Size(max = 1024) String contextSummary) {}

    public record StepUpdateRequest(@NotNull @jakarta.validation.constraints.Min(0) Long expectedRevision,
            @NotNull @jakarta.validation.constraints.Min(1) Integer stepOrder,
            @NotBlank @Size(max = 128) String title, @NotBlank @Size(max = 128) String requiredCapability,
            @NotNull @jakarta.validation.constraints.Pattern(regexp="RUNTIME|AX") String executionBackend,
            @Size(max = 8192) String outputContractJson) {}

    public record ReorderRequest(@NotNull @jakarta.validation.constraints.Min(0) Long expectedRevision,
            @NotNull @Size(min = 1, max = 62) List<@NotNull Long> stepIds) {}

    public record TeamResponse(Long id, String code, String name, String objective, String coordinationMode, String workspacePolicy, TeamStatus status, Instant createdAt, Instant updatedAt, long revision) {
    }

    public record MemberResponse(Long id, Long teamId, Long agentId, String role, String responsibility, Instant createdAt, Instant updatedAt) {
    }

    public record PlanResponse(Long id, Long teamId, String goal, String contextSummary, PlanStatus status, Instant createdAt, Instant updatedAt, Long executionWorkflowId, String executionRunKey, long revision) {
    }

    public record StepResponse(Long id, Long planId, Integer stepOrder, String title, String requiredCapability, Long assignedAgentId, StepStatus status, Instant createdAt, Instant updatedAt, String executionBackend, String outputContractJson) {
    }

    public record PlanDetail(PlanResponse plan, List<StepResponse> steps) {
    }

    static TeamResponse from(AgentTeam team) {
        return new TeamResponse(team.id, team.code, team.name, team.objective, team.coordinationMode, team.workspacePolicy, team.status, team.createdAt, team.updatedAt, team.revision);
    }

    static MemberResponse from(AgentTeamMember member) {
        return new MemberResponse(member.id, member.teamId, member.agentId, member.role, member.responsibility, member.createdAt, member.updatedAt);
    }

    static PlanResponse from(AgentPlan plan) {
        return new PlanResponse(plan.id, plan.teamId, plan.goal, plan.contextSummary, plan.status, plan.createdAt, plan.updatedAt, plan.executionWorkflowId, plan.executionRunKey, plan.revision);
    }

    static StepResponse from(AgentPlanStep step) {
        return new StepResponse(step.id, step.planId, step.stepOrder, step.title, step.requiredCapability, step.assignedAgentId, step.status, step.createdAt, step.updatedAt, step.executionBackend, step.outputContractJson);
    }
}

