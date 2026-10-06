package com.eap.platform.registry;

import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Namespace untrusted IDs before ingestion so an instance cannot overwrite another instance's spans. */
public final class RuntimeScopedTelemetry {
    private RuntimeScopedTelemetry(){}
    public record Scoped(JsonNode payload,Set<String> traces){}
    public static String trace(String key,String input){
        if(input==null||!input.matches("[0-9a-fA-F]{32}")||input.matches("0+"))throw new BusinessException("Trace 标识必须为非零32位十六进制");
        return hash(key+"|trace|"+input.toLowerCase(Locale.ROOT)).substring(0,32);
    }
    private static String span(String key,String input){
        if(!input.matches("[0-9a-fA-F]{16}")||input.matches("0+"))throw new BusinessException("Span 标识必须为非零16位十六进制");
        return hash(key+"|span|"+input.toLowerCase(Locale.ROOT)).substring(0,16);
    }
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("标识转换失败");}}
    public static Scoped normalize(JsonNode input,RuntimeConnectionService.Access access){
        return normalize(input,access,raw->trace(access.instanceKey(),raw));
    }
    /** Resolver is supplied only by the authenticated server controller, never by the HTTP caller. */
    public static Scoped normalize(JsonNode input,RuntimeConnectionService.Access access,java.util.function.Function<String,String> resolver){
        if(input==null||input.toString().getBytes(StandardCharsets.UTF_8).length>262144)throw new BusinessException("观测上报超限");
        var copy=input.deepCopy();var resources=copy.path("resourceSpans");
        if(!resources.isArray()||resources.isEmpty())throw new BusinessException("缺少 resourceSpans");
        int count=0;var traces=new HashSet<String>();
        for(var resource:resources){
            validateIdentity(resource.path("resource").path("attributes"),access.agentId());
            var scopes=resource.path("scopeSpans");
            if(!scopes.isArray())throw new BusinessException("实例接入仅接受 scopeSpans 格式");
            for(var scope:scopes){
                var spans=scope.path("spans");if(!spans.isArray())throw new BusinessException("缺少 spans");
                for(var value:spans){
                    if(++count>128||!value.isObject())throw new BusinessException("单次最多接受128个Span");
                    var item=(ObjectNode)value;validateIdentity(item.path("attributes"),access.agentId());
                    String trace=resolver.apply(item.path("traceId").asText());traces.add(trace);item.put("traceId",trace);
                    item.put("spanId",span(access.instanceKey(),item.path("spanId").asText()));
                    String parent=item.path("parentSpanId").asText();if(!parent.isBlank()&&!parent.matches("0+"))item.put("parentSpanId",span(access.instanceKey(),parent));
                    // Cross-instance links are not trusted identity evidence in this bounded protocol.
                    item.remove("links");
                }
            }
        }
        if(count==0)throw new BusinessException("没有可接收的Span");
        return new Scoped(copy,Set.copyOf(traces));
    }
    private static final Set<String> IDS=Set.of("eap.agent.id","agent.id","gen_ai.agent.id","ai.eap.agent.id");
    private static void validateIdentity(JsonNode attrs,Long agent){
        if(attrs.isArray())for(var attr:attrs){if(IDS.contains(attr.path("key").asText()))check(attr.path("value"),agent);}
        else if(attrs.isObject())for(String key:IDS)if(attrs.has(key))check(attrs.get(key),agent);
    }
    private static void check(JsonNode value,Long agent){
        if(value.isObject())value=value.has("stringValue")?value.path("stringValue"):value.path("intValue");
        if(!agent.toString().equals(value.asText()))throw new BusinessException("观测身份与实例凭证不一致");
    }
}
