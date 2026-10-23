package com.eap.platform.agentops;

import com.eap.platform.agent.AgentLifecycleStatus;
import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agentops")
public class AgentOpsController {
    private final AgentOpsService service;
    private final WorkItemService workItemService;

    public AgentOpsController(AgentOpsService service, WorkItemService workItemService) {
        this.service = service;
        this.workItemService = workItemService;
    }

    @GetMapping("/dashboard")
    public ApiResponse<AgentOpsDtos.DashboardResponse> dashboard(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(required = false) String organizationScope
    ) {
        return ApiResponse.ok(service.dashboard(hours, organizationScope));
    }

    @GetMapping("/agents")
    public ApiResponse<AgentOpsDtos.PagedAgentResponse> agents(
            @RequestParam(defaultValue = "24") int hours,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) AgentLifecycleStatus lifecycleStatus,
            @RequestParam(required = false) OperationalHealth health,
            @RequestParam(required = false) ObservationCoverage coverage,
            @RequestParam(required = false) RiskPosture risk
    ) {
        return ApiResponse.ok(service.listAgents(hours, page, size, query, department, lifecycleStatus, health, coverage, risk));
    }

    @GetMapping("/agents/{id}")
    public ApiResponse<AgentOpsDtos.AgentStateResponse> agent(
            @PathVariable Long id,
            @RequestParam(defaultValue = "24") int hours
    ) {
        return ApiResponse.ok(service.getAgent(id, hours));
    }

    @GetMapping("/work-items")
    public ApiResponse<List<AgentOpsDtos.WorkItemResponse>> workItems(
            @RequestParam(required = false) WorkItemStatus status,
            @RequestParam(required = false) WorkItemSeverity severity,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String organizationScope,
            @RequestParam(defaultValue = "100") int limit
    ) {
        return ApiResponse.ok(workItemService.list(status, severity, agentId, organizationScope, limit));
    }

    @GetMapping("/work-items/{id}/events")
    public ApiResponse<List<AgentOpsDtos.WorkItemEventResponse>> workItemEvents(@PathVariable Long id) {
        return ApiResponse.ok(workItemService.events(id));
    }

    @PostMapping("/work-items/synchronize")
    public ApiResponse<Integer> synchronizeWorkItems() {
        return ApiResponse.ok(workItemService.synchronizeSignals());
    }

    @PostMapping("/work-items/{id}/transition")
    public ApiResponse<AgentOpsDtos.WorkItemResponse> transition(
            @PathVariable Long id,
            @Valid @RequestBody AgentOpsDtos.WorkItemTransitionRequest request
    ) {
        return ApiResponse.ok(workItemService.transition(id, request));
    }
}
