package com.eap.platform.observability;

import com.eap.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

/** Metadata only, governed by the central VIEW_CONSOLE GET permission. */
@RestController
@RequestMapping("/api/workflows/{workflowId}/executions/{runKey}/observations")
public class WorkflowObservationController {
    private final WorkflowObservationService service;
    public WorkflowObservationController(WorkflowObservationService service) { this.service = service; }
    @GetMapping
    public Object page(@PathVariable long workflowId, @PathVariable String runKey,
            @RequestParam(defaultValue = "20") int limit, @RequestParam(required = false) String cursor) {
        return ApiResponse.ok(service.page(workflowId, runKey, limit, cursor));
    }
    @GetMapping("/{id}")
    public Object detail(@PathVariable long workflowId, @PathVariable String runKey, @PathVariable long id) {
        return ApiResponse.ok(service.detail(workflowId, runKey, id));
    }
}
