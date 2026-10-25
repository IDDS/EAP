package com.eap.platform.workflow;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;

import java.util.List;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {
    private final WorkflowService workflowService;
    private final WorkflowExecutionService executions;
    private final WorkflowToolRecoveryService toolRecovery;
    @org.springframework.beans.factory.annotation.Autowired private WorkflowExecutionConfigurationService configurations;
    @org.springframework.beans.factory.annotation.Autowired private WorkflowStopService stops;

    public WorkflowController(WorkflowService workflowService, WorkflowExecutionService executions, WorkflowToolRecoveryService toolRecovery) {
        this.toolRecovery = toolRecovery;
        this.workflowService = workflowService;
        this.executions = executions;
    }

    @GetMapping
    public ApiResponse<List<WorkflowDtos.WorkflowResponse>> list() {
        return ApiResponse.ok(workflowService.list());
    }

    @PostMapping
    public ApiResponse<WorkflowDtos.WorkflowResponse> create(@Valid @RequestBody WorkflowDtos.WorkflowRequest request) {
        return ApiResponse.ok(workflowService.create(request));
    }

    @GetMapping("/{workflowId}")
    public ApiResponse<WorkflowDtos.WorkflowDetail> detail(@PathVariable Long workflowId) {
        return ApiResponse.ok(workflowService.detail(workflowId));
    }

    @PostMapping("/{workflowId}/nodes")
    public ApiResponse<WorkflowDtos.NodeResponse> upsertNode(@PathVariable Long workflowId, @Valid @RequestBody WorkflowDtos.NodeRequest request) {
        return ApiResponse.ok(workflowService.upsertNode(workflowId, request));
    }

    @PostMapping("/{workflowId}/edges")
    public ApiResponse<WorkflowDtos.EdgeResponse> addEdge(@PathVariable Long workflowId, @Valid @RequestBody WorkflowDtos.EdgeRequest request) {
        return ApiResponse.ok(workflowService.addEdge(workflowId, request));
    }

    @PostMapping("/{workflowId}/runs")
    public ApiResponse<WorkflowDtos.RunResponse> run(@PathVariable Long workflowId, @Valid @RequestBody WorkflowDtos.RunRequest request) {
        return ApiResponse.ok(workflowService.run(workflowId, request));
    }

    @GetMapping("/execution-status")
    public ApiResponse<WorkflowExecutionService.Availability> executionStatus() { return ApiResponse.ok(executions.availability()); }

    @PostMapping("/{workflowId}/executions")
    public ApiResponse<WorkflowExecutionService.Response> execute(@PathVariable Long workflowId,
            @Valid @RequestBody WorkflowDtos.RunRequest request, Authentication authentication) {
        return ApiResponse.ok(executions.submit(workflowId, request, authentication == null ? "console" : authentication.getName()));
    }

    @GetMapping("/{workflowId}/executions")
    public ApiResponse<List<WorkflowExecutionService.Response>> executions(@PathVariable Long workflowId) {
        return ApiResponse.ok(executions.list(workflowId));
    }

    @GetMapping("/{workflowId}/executions/{runKey}/tool-recovery")
    public ApiResponse<WorkflowToolRecoveryService.Report> toolRecovery(@PathVariable Long workflowId,@PathVariable String runKey) {
        return ApiResponse.ok(toolRecovery.report(workflowId,runKey));
    }

    @GetMapping("/{workflowId}/executions/{runKey}/stop")
    public ApiResponse<WorkflowStopService.Report> stopStatus(@PathVariable Long workflowId, @PathVariable String runKey) {
        return ApiResponse.ok(stops.get(workflowId, runKey));
    }

    @PostMapping("/{workflowId}/executions/{runKey}/stop")
    public ApiResponse<WorkflowStopService.Report> stop(@PathVariable Long workflowId, @PathVariable String runKey,
            @Valid @RequestBody WorkflowStopService.Request request, Authentication authentication) {
        return ApiResponse.ok(stops.request(workflowId, runKey, request, authentication == null ? "console" : authentication.getName()));
    }

    @GetMapping("/{workflowId}/executions/{runKey}/outputs")
    public ApiResponse<java.util.Map<String, com.fasterxml.jackson.databind.JsonNode>> outputs(
            @PathVariable Long workflowId, @PathVariable String runKey) {
        return ApiResponse.ok(executions.outputs(workflowId, runKey));
    }

    @GetMapping("/{workflowId}/executions/{runKey}/configuration")
    public ApiResponse<WorkflowExecutionConfigurationService.Report> configuration(@PathVariable Long workflowId, @PathVariable String runKey) {
        return ApiResponse.ok(configurations.get(workflowId, runKey));
    }

    @GetMapping("/{workflowId}/executions/{runKey}")
    public ApiResponse<WorkflowExecutionService.Response> execution(@PathVariable Long workflowId, @PathVariable String runKey) {
        return ApiResponse.ok(executions.get(workflowId, runKey));
    }
}
