package com.eap.platform.orchestration;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orchestration")
public class OrchestrationController {
    private final OrchestrationService orchestrationService;
    private final PlanExecutionService executions;

    public OrchestrationController(OrchestrationService orchestrationService, PlanExecutionService executions) {
        this.orchestrationService = orchestrationService;
        this.executions = executions;
    }

    @PostMapping("/plans/{planId}/executions")
    public ApiResponse<PlanExecutionService.Response> execute(@PathVariable Long planId,
            @Valid @RequestBody PlanExecutionService.Request request, org.springframework.security.core.Authentication authentication) {
        return ApiResponse.ok(executions.submit(planId, request, authentication == null ? "console" : authentication.getName()));
    }

    @GetMapping("/plans/{planId}/execution")
    public ApiResponse<PlanExecutionService.Response> execution(@PathVariable Long planId) {
        return ApiResponse.ok(executions.synchronize(planId));
    }

    @GetMapping("/plans/{planId}/reclaim-readiness")
    public ApiResponse<PlanExecutionService.ReclaimReadiness> reclaimReadiness(@PathVariable Long planId) {
        return ApiResponse.ok(executions.reclaimReadiness(planId));
    }

    @GetMapping("/teams")
    public ApiResponse<List<OrchestrationDtos.TeamResponse>> listTeams() {
        return ApiResponse.ok(orchestrationService.listTeams());
    }

    @PostMapping("/teams")
    public ApiResponse<OrchestrationDtos.TeamResponse> createTeam(@Valid @RequestBody OrchestrationDtos.TeamRequest request) {
        return ApiResponse.ok(orchestrationService.createTeam(request));
    }

    @GetMapping("/teams/{teamId}/members")
    public ApiResponse<List<OrchestrationDtos.MemberResponse>> listMembers(@PathVariable Long teamId) {
        return ApiResponse.ok(orchestrationService.listMembers(teamId));
    }

    @PostMapping("/teams/{teamId}/members")
    public ApiResponse<OrchestrationDtos.MemberResponse> addMember(@PathVariable Long teamId, @Valid @RequestBody OrchestrationDtos.MemberRequest request) {
        return ApiResponse.ok(orchestrationService.addMember(teamId, request));
    }

    @GetMapping("/teams/{teamId}/plans")
    public ApiResponse<List<OrchestrationDtos.PlanResponse>> listPlans(@PathVariable Long teamId) {
        return ApiResponse.ok(orchestrationService.listPlans(teamId));
    }

    @PostMapping("/plans")
    public ApiResponse<OrchestrationDtos.PlanResponse> createPlan(@Valid @RequestBody OrchestrationDtos.PlanRequest request) {
        return ApiResponse.ok(orchestrationService.createPlan(request));
    }

    @GetMapping("/plans/{planId}")
    public ApiResponse<OrchestrationDtos.PlanDetail> getPlan(@PathVariable Long planId) {
        return ApiResponse.ok(orchestrationService.getPlan(planId));
    }

    @PostMapping("/plans/{planId}/steps")
    public ApiResponse<OrchestrationDtos.StepResponse> addStep(@PathVariable Long planId, @Valid @RequestBody OrchestrationDtos.StepRequest request) {
        return ApiResponse.ok(orchestrationService.addStep(planId, request));
    }

    @PostMapping("/steps/{stepId}/output-contract")
    public ApiResponse<OrchestrationDtos.StepResponse> outputContract(@PathVariable Long stepId,
            @Valid @RequestBody OrchestrationDtos.OutputContractRequest request) {
        return ApiResponse.ok(orchestrationService.updateOutputContract(stepId, request));
    }

    @PostMapping("/steps/{stepId}/assignment")
    public ApiResponse<OrchestrationDtos.StepResponse> assignStep(@PathVariable Long stepId, @Valid @RequestBody OrchestrationDtos.AssignRequest request) {
        return ApiResponse.ok(orchestrationService.assignStep(stepId, request));
    }

    @PatchMapping("/teams/{teamId}")
    public ApiResponse<OrchestrationDtos.TeamResponse> updateTeam(@PathVariable Long teamId, @Valid @RequestBody OrchestrationDtos.TeamUpdateRequest request) {
        return ApiResponse.ok(orchestrationService.updateTeam(teamId, request));
    }

    @DeleteMapping("/teams/{teamId}")
    public ApiResponse<Void> deleteTeam(@PathVariable Long teamId, @Valid @RequestBody OrchestrationDtos.RevisionRequest request) {
        orchestrationService.deleteTeam(teamId, request);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/teams/{teamId}/members/{memberId}")
    public ApiResponse<OrchestrationDtos.MemberResponse> updateMember(@PathVariable Long teamId, @PathVariable Long memberId, @Valid @RequestBody OrchestrationDtos.MemberUpdateRequest request) {
        return ApiResponse.ok(orchestrationService.updateMember(teamId, memberId, request));
    }

    @DeleteMapping("/teams/{teamId}/members/{memberId}")
    public ApiResponse<Void> removeMember(@PathVariable Long teamId, @PathVariable Long memberId, @Valid @RequestBody OrchestrationDtos.RevisionRequest request) {
        orchestrationService.removeMember(teamId, memberId, request);
        return ApiResponse.ok(null);
    }

    @PatchMapping("/plans/{planId}")
    public ApiResponse<OrchestrationDtos.PlanResponse> updatePlan(@PathVariable Long planId, @Valid @RequestBody OrchestrationDtos.PlanUpdateRequest request) {
        return ApiResponse.ok(orchestrationService.updatePlan(planId, request));
    }

    @DeleteMapping("/plans/{planId}")
    public ApiResponse<Void> deletePlan(@PathVariable Long planId, @Valid @RequestBody OrchestrationDtos.RevisionRequest request) {
        orchestrationService.deletePlan(planId, request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/plans/{planId}/copies")
    public ApiResponse<OrchestrationDtos.PlanResponse> copyPlan(@PathVariable Long planId, @Valid @RequestBody OrchestrationDtos.PlanUpdateRequest request) {
        return ApiResponse.ok(orchestrationService.copyPlan(planId, request));
    }

    @PatchMapping("/steps/{stepId}")
    public ApiResponse<OrchestrationDtos.StepResponse> updateStep(@PathVariable Long stepId, @Valid @RequestBody OrchestrationDtos.StepUpdateRequest request) {
        return ApiResponse.ok(orchestrationService.updateStep(stepId, request));
    }

    @DeleteMapping("/steps/{stepId}")
    public ApiResponse<Void> deleteStep(@PathVariable Long stepId, @Valid @RequestBody OrchestrationDtos.RevisionRequest request) {
        orchestrationService.deleteStep(stepId, request);
        return ApiResponse.ok(null);
    }

    @PostMapping("/plans/{planId}/steps/order")
    public ApiResponse<OrchestrationDtos.PlanDetail> reorderSteps(@PathVariable Long planId, @Valid @RequestBody OrchestrationDtos.ReorderRequest request) {
        return ApiResponse.ok(orchestrationService.reorderSteps(planId, request));
    }
}

