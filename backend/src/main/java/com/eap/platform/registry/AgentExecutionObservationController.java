package com.eap.platform.registry;

import com.eap.platform.common.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
public class AgentExecutionObservationController {
    private final RuntimeConnectionService connections;
    private final AgentExecutionObservationService observations;
    public AgentExecutionObservationController(RuntimeConnectionService connections, AgentExecutionObservationService observations) {
        this.connections=connections;this.observations=observations;
    }
    @PostMapping("/api/runtime-access/v1/execution-events")
    public ApiResponse<AgentExecutionObservationService.Receipt> ingest(@RequestBody JsonNode body,HttpServletRequest request) {
        return ApiResponse.ok(observations.ingest(RuntimeRequestAuthentication.authorize(connections,request),body));
    }
    @GetMapping("/api/observability/agent-executions")
    public ApiResponse<AgentExecutionObservationService.EventPage> events(@RequestParam(required=false) Long agentId,
            @RequestParam(required=false) String streamKey,@RequestParam(defaultValue="0") long afterId,@RequestParam(defaultValue="100") int limit) {
        return ApiResponse.ok(observations.events(agentId,streamKey,afterId,limit));
    }
    @GetMapping("/api/observability/agent-executions/{streamKey}/graph")
    public ApiResponse<AgentExecutionObservationService.Graph> graph(@PathVariable String streamKey) {return ApiResponse.ok(observations.graph(streamKey));}
}
