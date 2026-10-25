package com.eap.platform.registry;

import com.eap.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class ExecutionLinkController {
    private final RuntimeConnectionService connections;
    private final ExecutionLinkService links;
    public ExecutionLinkController(RuntimeConnectionService connections,ExecutionLinkService links){this.connections=connections;this.links=links;}
    @PostMapping("/api/runtime-access/v1/execution-links")
    public ApiResponse<ExecutionLinkService.Receipt> report(@RequestBody JsonNode body,HttpServletRequest request) {
        return ApiResponse.ok(links.report(RuntimeRequestAuthentication.authorize(connections,request),body));
    }
    @GetMapping("/api/observability/agent-executions/{streamKey}/links")
    public ApiResponse<ExecutionLinkService.Page> page(@PathVariable String streamKey,@RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="50") int limit) {
        return ApiResponse.ok(links.page(streamKey,afterId,limit));
    }
}
