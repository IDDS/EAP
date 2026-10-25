package com.eap.platform.execution;

import com.eap.platform.common.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/executions")
public class ExecutionCatalogController {
    private final ExecutionCatalogService service;
    public ExecutionCatalogController(ExecutionCatalogService service) { this.service = service; }
    @GetMapping
    public ApiResponse<ExecutionCatalogService.Page> list(
            @RequestParam(defaultValue="ALL") String kind, @RequestParam(defaultValue="ALL") String status,
            @RequestParam(defaultValue="") String query, @RequestParam(defaultValue="false") boolean mine,
            @RequestParam(required=false) Long workflowId, @RequestParam(required=false) Instant from,
            @RequestParam(required=false) Instant until, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size, Authentication authentication) {
        return ApiResponse.ok(service.list(kind, status, query, mine, workflowId, from, until, page, size, authentication));
    }
}
