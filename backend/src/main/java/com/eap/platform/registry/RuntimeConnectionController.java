package com.eap.platform.registry;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/registry/runtime-connection")
public class RuntimeConnectionController {
    private final RuntimeConnectionService service;
    private final RuntimeCredentialDeliveryService delivery;
    public RuntimeConnectionController(RuntimeConnectionService service,RuntimeCredentialDeliveryService delivery){this.service=service;this.delivery=delivery;}
    public record Issue(@NotBlank @Size(max=128) String instanceKey,@NotBlank @Size(max=36) String requestKey){}
    public record Target(@NotBlank @Size(max=128) String instanceKey){}
    private String subject(Authentication auth){return auth==null?"console":auth.getName();}
    @GetMapping public ApiResponse<RuntimeConnectionService.Status> get(@RequestParam String instanceKey){return ApiResponse.ok(service.status(instanceKey));}
    @PostMapping("/credential") public ResponseEntity<ApiResponse<RuntimeConnectionService.Credential>> issue(@Valid @RequestBody Issue body,Authentication auth){
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Pragma","no-cache").body(ApiResponse.ok(service.issue(body.instanceKey(),body.requestKey(),subject(auth))));
    }
    @PostMapping("/automatic") public ApiResponse<RuntimeConnectionService.Status> automatic(@Valid @RequestBody Issue body,Authentication auth){delivery.connect(body.instanceKey(),body.requestKey(),subject(auth));return ApiResponse.ok(service.status(body.instanceKey()));}
    @PostMapping("/verify") public ApiResponse<RuntimeConnectionService.Status> verify(@Valid @RequestBody Target body,Authentication auth){return ApiResponse.ok(service.verify(body.instanceKey(),subject(auth)));}
    @PostMapping("/revoke") public ApiResponse<Void> revoke(@Valid @RequestBody Target body,Authentication auth){service.revoke(body.instanceKey(),subject(auth));return ApiResponse.ok(null);}
}
