package com.eap.platform.registry;

import com.eap.platform.common.*;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public class ScopedToolController {
    private final ScopedToolService service;
    private final RuntimeConnectionService connections;
    private final ObjectMapper json;
    private final Validator validator;
    public ScopedToolController(ScopedToolService service,RuntimeConnectionService connections,ObjectMapper json,Validator validator){this.service=service;this.connections=connections;this.json=json;this.validator=validator;}
    @GetMapping("/api/registry/runtime-tools/scopes")
    public ApiResponse<ScopedToolDtos.Dashboard> dashboard(@RequestParam String instanceKey){return ApiResponse.ok(service.dashboard(instanceKey));}
    @GetMapping("/api/registry/runtime-tools/contracts")
    public ApiResponse<ScopedToolDtos.Catalog> contracts(@RequestParam String instanceKey){return ApiResponse.ok(service.contracts(instanceKey));}
    @GetMapping("/api/registry/runtime-tools/scopes/operations")
    public ApiResponse<ScopedToolDtos.Inspection> inspect(@RequestParam String instanceKey,@RequestParam String rootId,@RequestParam String operationId){return ApiResponse.ok(service.inspectOperation(instanceKey,rootId,operationId));}
    @PostMapping("/api/registry/runtime-tools/scopes")
    public ApiResponse<ScopedToolDtos.Grant> create(@RequestBody JsonNode body,Authentication auth){return ApiResponse.ok(service.create(read(body,ScopedToolDtos.Root.class,Set.of("grantId","holderKey","resourceSet","recipientSet","maxDelta","claimLimit","expiresAt")),auth==null?"console":auth.getName()));}
    @PostMapping("/api/registry/runtime-tools/scopes/revoke")
    public ApiResponse<ScopedToolDtos.Grant> revoke(@RequestBody JsonNode body,Authentication auth){var r=read(body,ScopedToolDtos.Revoke.class,Set.of("grantId"));return ApiResponse.ok(service.revoke(r.grantId(),auth==null?"console":auth.getName()));}
    @PostMapping("/api/runtime-access/v1/tools/scoped/delegate")
    public ApiResponse<ScopedToolDtos.Grant> delegate(@RequestBody JsonNode body,HttpServletRequest request){var a=RuntimeRequestAuthentication.authorize(connections,request);return ApiResponse.ok(service.delegate(a,read(body,ScopedToolDtos.Delegate.class,Set.of("grantId","parentGrantId","holderKey","resourceSet","recipientSet","maxDelta","claimLimit","expiresAt"))));}
    @PostMapping("/api/runtime-access/v1/tools/scoped/execute")
    public ApiResponse<ScopedToolDtos.Operation> execute(@RequestBody JsonNode body,HttpServletRequest request){var a=RuntimeRequestAuthentication.authorize(connections,request);return ApiResponse.ok(service.execute(a,read(body,ScopedToolDtos.Execute.class,Set.of("grantId","operationId","resourceId","delta"))));}
    @GetMapping("/api/runtime-access/v1/tools/scoped/operations")
    public ApiResponse<ScopedToolDtos.Operation> query(@RequestParam String rootId,@RequestParam String operationId,HttpServletRequest request){return ApiResponse.ok(service.query(RuntimeRequestAuthentication.authorize(connections,request),rootId,operationId));}
    private <T>T read(JsonNode body,Class<T> type,Set<String> fields){
        if(body==null||!body.isObject()||body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>8192)throw new BusinessException("工具请求格式或大小无效");
        for(var names=body.fieldNames();names.hasNext();)if(!fields.contains(names.next()))throw new BusinessException("请求包含不允许覆盖的字段");
        try{var value=json.treeToValue(body,type);if(!validator.validate(value).isEmpty())throw new IllegalArgumentException();return value;}catch(Exception e){throw new BusinessException("工具请求字段无效");}
    }
}
