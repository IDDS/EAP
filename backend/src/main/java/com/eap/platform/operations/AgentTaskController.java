package com.eap.platform.operations;

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
@RequestMapping("/api/tasks")
public class AgentTaskController {
    private final AgentTaskService taskService;

    public AgentTaskController(AgentTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public ApiResponse<List<AgentTaskDtos.TaskResponse>> list() {
        return ApiResponse.ok(taskService.list());
    }

    @PostMapping
    public ApiResponse<AgentTaskDtos.TaskResponse> create(@Valid @RequestBody AgentTaskDtos.TaskRequest request) {
        return ApiResponse.ok(taskService.create(request));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<AgentTaskDtos.TaskResponse> updateStatus(@PathVariable Long id, @Valid @RequestBody AgentTaskDtos.StatusRequest request) {
        return ApiResponse.ok(taskService.updateStatus(id, request));
    }
}

