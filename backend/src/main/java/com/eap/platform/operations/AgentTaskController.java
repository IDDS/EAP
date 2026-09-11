package com.eap.platform.operations;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
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

    @GetMapping("/page")
    public ApiResponse<com.eap.platform.common.HistoryPage<AgentTaskDtos.TaskResponse>> page(
            @Valid @org.springframework.web.bind.annotation.ModelAttribute com.eap.platform.common.HistoryQuery query) {
        return ApiResponse.ok(taskService.page(query));
    }

    @GetMapping
    public ApiResponse<List<AgentTaskDtos.TaskResponse>> list() {
        return ApiResponse.ok(taskService.list());
    }

    @PostMapping
    public ApiResponse<AgentTaskDtos.TaskResponse> create(
            @Valid @RequestBody AgentTaskDtos.TaskRequest request,
            Authentication authentication
    ) {
        String authenticatedRequester = authenticatedRequester(authentication);
        return ApiResponse.ok(taskService.create(
                request.withRequester(authenticatedRequester == null ? request.requester() : authenticatedRequester)
        ));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<AgentTaskDtos.TaskResponse> updateStatus(@PathVariable Long id, @Valid @RequestBody AgentTaskDtos.StatusRequest request) {
        return ApiResponse.ok(taskService.updateStatus(id, request));
    }

    private String authenticatedRequester(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getName() == null || authentication.getName().isBlank()
                || "anonymousUser".equals(authentication.getName())) {
            return null;
        }
        String name = authentication.getName().trim();
        return name.length() > 128 ? name.substring(0, 128) : name;
    }
}
