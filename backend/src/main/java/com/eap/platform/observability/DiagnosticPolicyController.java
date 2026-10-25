package com.eap.platform.observability;

import com.eap.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/observability/diagnostics/policy")
public class DiagnosticPolicyController {
    private final DiagnosticPolicyService policies;
    public DiagnosticPolicyController(DiagnosticPolicyService policies) { this.policies = policies; }
    @GetMapping public ApiResponse<DiagnosticPolicyService.Policy> get() { return ApiResponse.ok(policies.current()); }
    @PostMapping public ApiResponse<DiagnosticPolicyService.Policy> publish(@RequestBody JsonNode request, Authentication auth) {
        return ApiResponse.ok(policies.publish(request, auth == null ? "console" : auth.getName()));
    }
}
