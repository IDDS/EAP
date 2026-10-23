package com.eap.platform.registry;

import com.eap.platform.common.*;
import com.eap.platform.gateway.*;
import com.eap.platform.observability.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/runtime-access/v1")
public class RuntimeAccessController {
    private final RuntimeConnectionService connections;
    private final OpenAiCompatibleGatewayService gateway;
    private final OtlpTraceIngestionService traces;
    private final com.eap.platform.discovery.AgentDiscoveryService discovery;
    private final DiagnosticSamplingService diagnostics;
    public RuntimeAccessController(RuntimeConnectionService connections,OpenAiCompatibleGatewayService gateway,OtlpTraceIngestionService traces,com.eap.platform.discovery.AgentDiscoveryService discovery,DiagnosticSamplingService diagnostics){this.connections=connections;this.gateway=gateway;this.traces=traces;this.discovery=discovery;this.diagnostics=diagnostics;}
    private RuntimeConnectionService.Access authorize(HttpServletRequest request){
        return RuntimeRequestAuthentication.authorize(connections,request);
    }
    @PostMapping("/chat/completions")
    public ResponseEntity<JsonNode> chat(@RequestBody JsonNode body,HttpServletRequest request){
        var access=authorize(request);
        connections.validateTaskAttribution(access,request.getHeader("X-EAP-Task-Key"),request.getHeader("X-EAP-Invocation-Key"));
        if(body.path("stream").asBoolean())throw new BusinessException("实例接入当前使用非流式模型回执");
        String raw=request.getHeader("X-EAP-Trace-Id");if(raw==null)raw=UUID.randomUUID().toString().replace("-","");
        String trace=connections.resolveTrace(access,raw);
        // Telemetry validates endpoint existence. Register before any upstream model side effect.
        discovery.registerEndpoint(new com.eap.platform.discovery.AgentDiscoveryDtos.EndpointRegistrationRequest(
                access.endpointKey(),access.instanceKey(),null,"RUNTIME","eap-runtime-connection/1"));
        var result=RuntimeModelAccess.call(access,()->gateway.chatCompletions(body,access.agentId(),access.endpointKey(),null,trace,
                new GatewayDtos.ClassificationHints(null,null,null,null,List.of()),null));
        if(result.getStatusCode().is2xxSuccessful())connections.recordGateway(access,trace);
        return result;
    }
    @PostMapping("/traces")
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<OtlpTraceIngestionDtos.IngestionResponse> ingest(@RequestBody JsonNode body,HttpServletRequest request){
        var access=authorize(request);var resolved=new HashMap<String,String>();var scoped=RuntimeScopedTelemetry.normalize(body,access,raw->resolved.computeIfAbsent(raw.toLowerCase(Locale.ROOT),id->connections.resolveTrace(access,id)));
        var filtered=diagnostics.filter(scoped.payload(),access);
        var result=traces.ingestScoped(filtered.payload(),access.agentId(),access.endpointKey());
        if(!filtered.warnings().isEmpty()){
            var warnings=new ArrayList<>(result.warnings());warnings.addAll(filtered.warnings());
            result=new OtlpTraceIngestionDtos.IngestionResponse(result.spansScanned(),result.genAiSpansDetected(),result.invocationsRecorded(),result.contentObservationsRecorded(),result.endpointsRegistered(),result.traceSpansRecorded(),result.toolSpansRecorded(),result.mcpSpansRecorded(),result.ragSpansRecorded(),result.a2aSpansRecorded(),result.knowledgeInteractionsRecorded(),List.copyOf(warnings));
        }
        connections.recordTrace(access,scoped.traces());return ApiResponse.ok(result);
    }
    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Map<String,Object>> gatewayFailure(GatewayException error){
        return ResponseEntity.status(error.getStatus()).header("X-EAP-Admission-Outcome",error.admissionOutcome()).body(Map.of("error",Map.of("code",error.getCode(),"message",error.getMessage())));
    }
}
