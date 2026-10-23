package com.eap.platform.control;

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
@RequestMapping("/api/control")
public class AgentControlController {
    private final AgentControlService controlService;

    public AgentControlController(AgentControlService controlService) {
        this.controlService = controlService;
    }

    @GetMapping("/agents/{agentId}/targets")
    public ApiResponse<AgentControlDtos.Targets> targets(@PathVariable Long agentId) {
        return ApiResponse.ok(controlService.targets(agentId));
    }

    @GetMapping("/commands")
    public ApiResponse<List<AgentControlDtos.CommandResponse>> recent() {
        return ApiResponse.ok(controlService.recent());
    }

    @PostMapping("/commands")
    public ApiResponse<AgentControlDtos.CommandResponse> issue(@Valid @RequestBody AgentControlDtos.CommandRequest request, org.springframework.security.core.Authentication authentication) {
        return ApiResponse.ok(controlService.issue(new AgentControlDtos.CommandRequest(request.agentId(), request.commandType(), request.payloadJson(),
                authentication == null ? "console" : authentication.getName(), request.runtimeInstanceKey())));
    }

    @PostMapping("/commands/{commandKey}/retry")
    public ApiResponse<AgentControlDtos.CommandResponse> retry(@PathVariable String commandKey, org.springframework.security.core.Authentication authentication) {
        return ApiResponse.ok(controlService.retry(commandKey, authentication == null ? "console" : authentication.getName()));
    }
}
