package com.eap.platform.optimization;

import com.eap.platform.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** No bearer identity grants access here; every route checks the scoped run claim. */
@RestController
@RequestMapping("/internal/agent-optimizations/{id}")
public class OptimizationMachineController {
    private final OptimizationService service;
    public OptimizationMachineController(OptimizationService service){this.service=service;}
    @GetMapping("/context") public ApiResponse<Map<String,Object>> context(@PathVariable long id,@RequestHeader(value="X-EAP-Optimization-Claim",required=false) String claim){return ApiResponse.ok(service.context(id,claim));}
    @PostMapping("/evaluations") public ApiResponse<Map<String,Object>> evaluate(@PathVariable long id,@RequestHeader(value="X-EAP-Optimization-Claim",required=false) String claim,@Valid @RequestBody OptimizationDtos.Evaluate request){return ApiResponse.ok(service.evaluate(id,claim,request));}
    @GetMapping("/evaluations/{key}") public ApiResponse<Map<String,Object>> evaluation(@PathVariable long id,@RequestHeader(value="X-EAP-Optimization-Claim",required=false) String claim,@PathVariable String key){return ApiResponse.ok(service.evaluation(id,claim,key));}
    @PostMapping("/reflections") public ApiResponse<Map<String,Object>> reflect(@PathVariable long id,@RequestHeader(value="X-EAP-Optimization-Claim",required=false) String claim,@Valid @RequestBody OptimizationDtos.Reflect request){return ApiResponse.ok(service.reflect(id,claim,request));}
    @GetMapping("/reflections/{ordinal}") public ApiResponse<Map<String,Object>> reflection(@PathVariable long id,@RequestHeader(value="X-EAP-Optimization-Claim",required=false) String claim,@PathVariable int ordinal){return ApiResponse.ok(service.reflection(id,claim,ordinal));}
    @PostMapping("/search-result") public ApiResponse<Map<String,Object>> searchResult(@PathVariable long id,@RequestHeader(value="X-EAP-Optimization-Claim",required=false) String claim,@Valid @RequestBody OptimizationDtos.SearchReceipt request){return ApiResponse.ok(service.searchResult(id,claim,request));}
}
