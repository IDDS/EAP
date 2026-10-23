package com.eap.platform.agent;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agents")
public class AgentController {
    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping
    public ApiResponse<List<AgentDtos.AgentResponse>> list() {
        return ApiResponse.ok(agentService.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<AgentDtos.AgentResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(agentService.get(id));
    }

    @PostMapping
    public ApiResponse<AgentDtos.AgentResponse> create(@Valid @RequestBody AgentDtos.AgentRequest request) {
        return ApiResponse.ok(agentService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AgentDtos.AgentResponse> update(@PathVariable Long id, @Valid @RequestBody AgentDtos.AgentRequest request) {
        return ApiResponse.ok(agentService.update(id, request));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<AgentDtos.AgentResponse> updateStatus(@PathVariable Long id, @Valid @RequestBody AgentDtos.StatusRequest request) {
        return ApiResponse.ok(agentService.updateStatus(id, request.lifecycleStatus()));
    }
}

