package com.eap.platform.registry;

import com.eap.platform.common.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class RuntimeToolController {
    private final RuntimeConnectionService connections;
    private final RuntimeToolGuardService guard;
    private final ObjectMapper json;
    private final Validator validator;
    public RuntimeToolController(RuntimeConnectionService connections,RuntimeToolGuardService guard,ObjectMapper json,Validator validator){
        this.connections=connections;this.guard=guard;this.json=json;this.validator=validator;
    }
    @PostMapping("/api/runtime-access/v1/tools/check")
    public ApiResponse<RuntimeToolDtos.View> check(@RequestBody JsonNode body,HttpServletRequest request){
        var access=RuntimeRequestAuthentication.authorize(connections,request);
        return ApiResponse.ok(guard.check(access,read(body,RuntimeToolDtos.Check.class,Set.of("callId","toolName","toolKind","traceId","arguments"))));
    }
    @PostMapping("/api/runtime-access/v1/tools/claim")
    public ApiResponse<RuntimeToolDtos.ClaimResult> claim(@RequestBody JsonNode body,HttpServletRequest request){
        var access=RuntimeRequestAuthentication.authorize(connections,request);
        return ApiResponse.ok(guard.claim(access,read(body,RuntimeToolDtos.Claim.class,Set.of("callId"))));
    }
    @PostMapping("/api/runtime-access/v1/tools/receipt")
    public ApiResponse<RuntimeToolDtos.View> receipt(@RequestBody JsonNode body,HttpServletRequest request){
        var access=RuntimeRequestAuthentication.authorize(connections,request);
        return ApiResponse.ok(guard.receipt(access,read(body,RuntimeToolDtos.Receipt.class,Set.of("callId","outcome","durationMs","outputDigest"))));
    }
    @GetMapping("/api/registry/runtime-tools")
    public ApiResponse<List<RuntimeToolDtos.View>> list(@RequestParam String instanceKey){return ApiResponse.ok(guard.list(instanceKey));}
    @ExceptionHandler(com.eap.platform.gateway.GatewayException.class)
    public org.springframework.http.ResponseEntity<Map<String,Object>> budgetFailure(com.eap.platform.gateway.GatewayException error) {
        return org.springframework.http.ResponseEntity.status(error.getStatus()).header("X-EAP-Admission-Outcome",error.admissionOutcome()).body(Map.of("error",Map.of("code",error.getCode(),"message",error.getMessage())));
    }
    private <T>T read(JsonNode body,Class<T> type,Set<String> fields){
        if(body==null||!body.isObject()||body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>70000)
            throw new BusinessException("工具请求格式或大小无效");
        for(var names=body.fieldNames();names.hasNext();)if(!fields.contains(names.next()))throw new BusinessException("工具请求包含不允许覆盖的字段");
        try{var value=json.treeToValue(body,type);if(!validator.validate(value).isEmpty())throw new IllegalArgumentException();return value;}
        catch(Exception e){throw new BusinessException("工具请求字段无效");}
    }
}
