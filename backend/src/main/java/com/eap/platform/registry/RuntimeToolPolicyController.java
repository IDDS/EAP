package com.eap.platform.registry;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/registry/runtime-tools/policy")
public class RuntimeToolPolicyController {
    private final RuntimeToolPolicyService service;
    public RuntimeToolPolicyController(RuntimeToolPolicyService service){this.service=service;}
    @GetMapping public ApiResponse<RuntimeToolPolicyService.View> get(@RequestParam String instanceKey){return ApiResponse.ok(service.get(instanceKey));}
    @PutMapping public ApiResponse<RuntimeToolPolicyService.View> update(@Valid @RequestBody RuntimeToolPolicyService.Update body,Authentication auth){
        return ApiResponse.ok(service.update(body,auth==null?"console":auth.getName()));
    }
}
