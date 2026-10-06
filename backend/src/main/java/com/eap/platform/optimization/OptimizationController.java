package com.eap.platform.optimization;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/agent-optimizations")
public class OptimizationController {
    private final OptimizationService service;
    public OptimizationController(OptimizationService service){this.service=service;}
    @GetMapping("/status") public ApiResponse<Map<String,Boolean>> status(){return ApiResponse.ok(Map.of("enabled",service.enabled()));}
    @GetMapping public ApiResponse<List<OptimizationDtos.Summary>> list(){return ApiResponse.ok(service.list());}
    @GetMapping("/{id}") public ApiResponse<OptimizationDtos.Summary> get(@PathVariable long id){return ApiResponse.ok(service.get(id));}
    @GetMapping("/{id}/content") public ApiResponse<Map<String,Object>> content(@PathVariable long id){return ApiResponse.ok(service.content(id));}
    @PostMapping public ApiResponse<OptimizationDtos.Summary> submit(@Valid @RequestBody OptimizationDtos.Submit request,Authentication authentication){return ApiResponse.ok(service.submit(request,authentication==null?"console":authentication.getName()));}
}
