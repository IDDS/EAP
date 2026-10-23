package com.eap.platform.workflow;

import com.eap.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkflowSchedulingController {
    private final WorkflowSchedulingService scheduling;
    public WorkflowSchedulingController(WorkflowSchedulingService scheduling){this.scheduling=scheduling;}
    @GetMapping("/api/workflows/scheduling")
    public ApiResponse<WorkflowSchedulingService.Summary> report(){return ApiResponse.ok(scheduling.report());}
}
