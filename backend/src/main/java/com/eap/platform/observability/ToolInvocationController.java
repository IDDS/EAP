package com.eap.platform.observability;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/observability/tool-invocations")
public class ToolInvocationController {
    private final ToolInvocationService service;

    public ToolInvocationController(ToolInvocationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ToolInvocationDtos.ToolInvocationResponse>> list(
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(required = false) Long agentId,
            @RequestParam(required = false) String endpointKey,
            @RequestParam(required = false) String runtimeInstanceKey,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) ToolInvocationKind toolKind,
            @RequestParam(required = false) ToolInvocationStatus status,
            @RequestParam(required = false) String toolName,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to
    ) {
        return ApiResponse.ok(service.list(limit, agentId, endpointKey, runtimeInstanceKey, traceId, toolKind, status, toolName, from, to));
    }
}
