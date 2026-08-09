package com.eap.platform.workflow;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {
    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
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
    public ApiResponse<WorkflowDtos.RunResponse> run(@PathVariable Long workflowId, @RequestBody WorkflowDtos.RunRequest request) {
        return ApiResponse.ok(workflowService.run(workflowId, request));
    }
}
