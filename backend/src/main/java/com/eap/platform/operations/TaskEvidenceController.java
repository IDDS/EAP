package com.eap.platform.operations;

import com.eap.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TaskEvidenceController {
    private final TaskEvidenceService service;
    public TaskEvidenceController(TaskEvidenceService service) { this.service = service; }
    @GetMapping("/api/tasks/{id}/evidence")
    public ApiResponse<TaskEvidenceService.Response> evidence(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }
}
