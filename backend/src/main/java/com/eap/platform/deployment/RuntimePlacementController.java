package com.eap.platform.deployment;

import com.eap.platform.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Covered by the central VIEW_CONSOLE GET rule; this endpoint never operates the runtime. */
@RestController
public class RuntimePlacementController {
    private final RuntimePlacementService service;

    public RuntimePlacementController(RuntimePlacementService service) { this.service = service; }

    @GetMapping("/api/agents/{agentId}/substrate/placement")
    public ApiResponse<RuntimePlacementService.Snapshot> current(@PathVariable Long agentId, @RequestParam String instanceKey) {
        return ApiResponse.ok(service.current(agentId, instanceKey));
    }
}
