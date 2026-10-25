package com.eap.platform.registry;

import com.eap.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class ExecutionDeliveryController {
    private final RuntimeConnectionService connections;
    private final ExecutionDeliveryService delivery;
    public ExecutionDeliveryController(RuntimeConnectionService connections,ExecutionDeliveryService delivery){this.connections=connections;this.delivery=delivery;}
    @PostMapping("/api/runtime-access/v1/execution-delivery")
    public ApiResponse<ExecutionDeliveryService.Receipt> report(@RequestBody JsonNode body,HttpServletRequest request){
        return ApiResponse.ok(delivery.report(RuntimeRequestAuthentication.authorize(connections,request),body));
    }
    @GetMapping("/api/observability/agent-executions/deliveries")
    public ApiResponse<ExecutionDeliveryService.Page> page(@RequestParam(required=false) Long agentId,
            @RequestParam(required=false) String streamKey,@RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="50") int limit){
        return ApiResponse.ok(delivery.page(agentId,streamKey,afterId,limit));
    }
}
