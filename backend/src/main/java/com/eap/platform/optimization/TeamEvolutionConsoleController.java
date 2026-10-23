package com.eap.platform.optimization;

import com.eap.platform.common.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/team-evolutions")
public class TeamEvolutionConsoleController {
    private final TeamEvolutionConsoleService service;
    private final TeamEvolutionOperationService operations;
    public TeamEvolutionConsoleController(TeamEvolutionConsoleService service, TeamEvolutionOperationService operations) { this.service=service; this.operations=operations; }
    @GetMapping("/{id}/actions")
    public ApiResponse<TeamEvolutionOperationService.Context> context(@PathVariable long id, Authentication authentication) {
        return ApiResponse.ok(operations.context(id,authentication == null ? "console" : authentication.getName()));
    }
    @GetMapping("/receipts")
    public ApiResponse<TeamEvolutionOperationService.Receipt> receipt(@RequestParam TeamEvolutionOperationService.Action action,
            @RequestParam String requestKey, @RequestParam(required=false) Long rootId,
            @RequestParam(required=false) String evaluationId, Authentication authentication) {
        return ApiResponse.ok(operations.receipt(action,requestKey,rootId,evaluationId,authentication == null ? "console" : authentication.getName()));
    }
    @GetMapping("/console")
    public ApiResponse<TeamEvolutionConsoleService.Page> list(@RequestParam(required=false) Long beforeId) {
        return ApiResponse.ok(service.list(beforeId));
    }
    @GetMapping("/{id}/console")
    public ApiResponse<TeamEvolutionConsoleService.Detail> get(@PathVariable long id) {
        return ApiResponse.ok(service.get(id));
    }
}
