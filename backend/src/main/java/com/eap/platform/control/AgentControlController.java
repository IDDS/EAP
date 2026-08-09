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

    @GetMapping("/commands")
    public ApiResponse<List<AgentControlDtos.CommandResponse>> recent() {
        return ApiResponse.ok(controlService.recent());
    }

    @PostMapping("/commands")
    public ApiResponse<AgentControlDtos.CommandResponse> issue(@Valid @RequestBody AgentControlDtos.CommandRequest request) {
        return ApiResponse.ok(controlService.issue(request));
    }

    @PostMapping("/commands/{commandKey}/retry")
    public ApiResponse<AgentControlDtos.CommandResponse> retry(@PathVariable String commandKey) {
        return ApiResponse.ok(controlService.retry(commandKey));
    }
}
