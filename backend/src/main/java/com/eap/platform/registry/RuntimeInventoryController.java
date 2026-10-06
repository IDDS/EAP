package com.eap.platform.registry;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/registry/runtime-inventory")
public class RuntimeInventoryController {
    private final RuntimeInventoryService service;
    public RuntimeInventoryController(RuntimeInventoryService service) { this.service=service; }
    @GetMapping public ApiResponse<RuntimeInventoryService.Snapshot> list() {return ApiResponse.ok(service.snapshot());}
    @PostMapping("/adopt") public ApiResponse<RuntimeInventoryService.Item> adopt(@Valid @RequestBody RuntimeInventoryService.Adopt request, Authentication auth) {
        return ApiResponse.ok(service.adopt(request,auth==null?"console":auth.getName()));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> conflict() {
        return ResponseEntity.status(409).body(new ApiResponse<>(false,null,"实例已被并发纳管，请刷新列表确认归属",com.eap.platform.common.TraceContext.traceId()));
    }
}
