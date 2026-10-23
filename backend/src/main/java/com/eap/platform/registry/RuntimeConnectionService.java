package com.eap.platform.registry;

import com.eap.platform.agent.*;
import com.eap.platform.audit.*;
import com.eap.platform.common.*;
import com.eap.platform.config.UnauthorizedException;
import com.eap.platform.substrate.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class RuntimeConnectionService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.eap.platform.ax.AxTaskRepository taskIdentities;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate creationDb;
    private final RuntimeConnectionRepository connections;
    private final AgentRuntimeInstanceRepository runtimes;
    private final RuntimeConnectionProperties config;
    private final SubstrateProperties substrate;
    private final SubstrateClient client;
    private final AgentService agents;
    private final AuditLogRepository audit;
    private final ObjectMapper json;
    private final com.eap.platform.observability.TraceSpanObservationRepository spans;
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("runtime-connection-deadline").factory());
    public RuntimeConnectionService(RuntimeConnectionRepository connections,AgentRuntimeInstanceRepository runtimes,
            RuntimeConnectionProperties config,SubstrateProperties substrate,SubstrateClient client,AgentService agents,AuditLogRepository audit,ObjectMapper json,com.eap.platform.observability.TraceSpanObservationRepository spans){
        this.connections=connections;this.runtimes=runtimes;this.config=config;this.substrate=substrate;this.client=client;this.agents=agents;this.audit=audit;this.json=json;this.spans=spans;
    }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    RuntimeCredentialDeliveryService delivery;
    public record Status(String instanceKey,Long agentId,boolean available,String identity,String connection,
                         Instant expiresAt,Instant verifiedAt,Instant gatewayAt,Instant traceAt,String gatewayUrl,String message,boolean automaticDelivery,boolean deliveryAvailable,String deliveryPublicKey,Instant lastDeliveryAt,Instant identityExpiresAt,String deliveryOperation,boolean automaticOnly,boolean taskManaged){}
    public record Credential(String instanceKey,Long agentId,String token,Instant expiresAt,String gatewayUrl){
        @Override public String toString(){return "RuntimeCredential[redacted]";}
    }
    public record Access(String instanceKey,Long agentId,String generation,String endpointKey){}

    private AgentRuntimeInstance bound(String key){
        var r=runtimes.findByInstanceKey(key).orElseThrow(()->new BusinessException("运行实例未纳管"));
        agents.get(r.agentId);
        if(!r.supportsMachineIdentity())throw new BusinessException("该接入流程需要已复核的专属实例身份");
        return r;
    }
    private SubstrateClient.ActorInventory current(AgentRuntimeInstance r){
        var ref=SubstrateActorRef.parse(r.endpoint);
        if(!substrate.isEnabled()||!substrate.inventoryScopes().contains(ref.atespace()))throw new BusinessException("运行空间不在接入范围内");
        var a=client.inspect(ref);
        if(!ref.atespace().equals(a.atespace())||!ref.name().equals(a.name())||!r.getBoundActorUid().equals(a.uid())||!Objects.equals(r.getBoundActorTemplate(),a.template()))
            throw new ConflictException("实例身份已变化，需要重新复核；未执行接入操作");
        return a;
    }
    private boolean valid(RuntimeConnection c){return config.ready()&&c!=null&&c.generation!=null&&c.expiresAt!=null&&c.expiresAt.isAfter(Instant.now())&&(!c.automaticDelivery||c.identityExpiresAt!=null&&c.identityExpiresAt.isAfter(Instant.now()));}
    private String gateway(){return substrate.getGatewayUrl().replaceAll("/+$","");}
    @Transactional(readOnly=true)
    public Status status(String key){
        var r=bound(key);var c=connections.findByInstanceKey(key).orElse(null);
        String identity="MATCHED";
        try{current(r);}catch(ConflictException e){identity="CHANGED";}catch(RuntimeException e){identity="UNAVAILABLE";}
        String state=c==null?"NOT_CONFIGURED":c.generation==null?"REVOKED":c.deliveryOperation!=null?"DELIVERY_PENDING":!valid(c)?"EXPIRED":c.verifiedAt==null?"PENDING":"READY";
        if(!identity.equals("MATCHED"))state="BLOCKED";
        return new Status(key,r.agentId,config.ready(),identity,state,c==null?null:c.expiresAt,c==null?null:c.verifiedAt,
                valid(c)?c.gatewayAt:null,valid(c)?c.traceAt:null,gateway(),c==null?null:c.lastMessage,c!=null&&c.automaticDelivery,config.deliveryReady(),config.getDeliveryPublicKey(),c==null?null:c.lastDeliveryAt,c==null?null:c.identityExpiresAt,c==null?null:c.deliveryOperation,r.getImportedActorUid()==null,"SUBSTRATE_TASK".equals(r.protocol));
    }
    @Transactional
    public Credential issue(String key,String requestKey,String requester){
        if(!config.ready())throw new BusinessException("实例接入尚未配置独立签名密钥");
        try{if(!UUID.fromString(requestKey).toString().equals(requestKey))throw new IllegalArgumentException();}catch(Exception e){throw new BusinessException("凭证请求标识无效");}
        var r=lockedRuntime(key);current(r);
        if(r.getImportedActorUid()==null)throw new BusinessException("平台创建实例只支持安全自动接入");
        if(r.status==RuntimeStatus.OFFLINE||agents.get(r.agentId).lifecycleStatus()==AgentLifecycleStatus.RETIRED)throw new BusinessException("已下线智能体不能接入");
        var c=connections.lockForToolAccess(key).orElseGet(RuntimeConnection::new);
        if(c.automaticDelivery)throw new ConflictException("自动接入已启用，请先撤销后再切回人工配置");
        if(!requestKey.equals(c.requestKey)){
            c.instanceKey=key;c.requestKey=requestKey;c.generation=UUID.randomUUID().toString();c.expiresAt=Instant.now().plusSeconds(86400);
            c.verifiedAt=null;c.gatewayAt=null;c.gatewayTrace=null;c.traceAt=null;c.lastMessage=null;
            c.credentialRevision=null;c.deliveryOperation=null;c.deliveryRevision=null;c.deliveryExpiresAt=null;c.deliverySerial=null;
            connections.saveAndFlush(c);event(r,"ISSUE_RUNTIME_CREDENTIAL",requester);
        }else if(!valid(c))throw new ConflictException("原凭证已过期，请显式换发新凭证");
        return new Credential(key,r.agentId,token(r,c),c.expiresAt,gateway());
    }
    @Transactional
    public void revoke(String key,String requester){
        var r=lockedRuntime(key);var c=connections.lockForToolAccess(key).orElseGet(RuntimeConnection::new);c.instanceKey=key;
        if("SUBSTRATE_TASK".equals(r.protocol))creationDb.update("update ax_tasks set identity_revoked=true where instance_key=? and identity_mode='PER_INSTANCE_V1'",key);
        else if(r.getImportedActorUid()==null)creationDb.update("update platform_runtime_creations set revoked=true where instance_key=?",key);
        if(c!=null){c.automaticDelivery=false;c.deliveryOperation=null;c.deliveryRevision=null;c.deliveryExpiresAt=null;c.deliverySerial=null;c.generation=null;c.verifiedAt=null;c.gatewayAt=null;c.gatewayTrace=null;c.traceAt=null;c.lastMessage=null;connections.saveAndFlush(c);}
        event(r,"REVOKE_RUNTIME_CREDENTIAL",requester);
    }
    /** Probe contains a random challenge, never a platform or instance credential. */
    @Transactional
    public Status verify(String key,String requester){
        var r=lockedRuntime(key);var actor=current(r);var c=connections.lockForToolAccess(key).orElse(null);
        if("SUBSTRATE_TASK".equals(r.protocol))throw new BusinessException("任务实例按原任务专属协议接入，不接受通用任务握手");
        if(!valid(c))throw new BusinessException("请先签发有效的实例接入凭证");
        String nonce=UUID.randomUUID().toString();boolean restore="SUSPENDED".equals(actor.state());
        String failure=null;
        try{
            client.resume(SubstrateActorRef.parse(r.endpoint));current(r);
            var reply=probe(r,nonce);
            String proof=hmac(token(r,c),nonce+"|"+r.agentId+"|"+r.instanceKey+"|EAP_HTTP_TASK_V1");
            if(!nonce.equals(reply.path("nonce").asText())||!r.agentId.toString().equals(reply.path("agentId").asText())
                    ||!key.equals(reply.path("instanceKey").asText())||!"EAP_HTTP_TASK_V1".equals(reply.path("protocol").asText())
                    ||!equal(proof,reply.path("proof").asText()))throw new BusinessException("实例身份、凭证或任务协议握手不匹配");
            current(r);
        }catch(Exception e){failure="握手未通过，请核对程序的实例身份、接入凭证与任务协议；未开放执行";}
        finally{
            if(restore)try{current(r);if(!"SUSPENDED".equals(client.suspend(SubstrateActorRef.parse(r.endpoint)).state()))failure="接入检查后未确认休眠，请检查实例状态";}
            catch(RuntimeException e){failure="接入检查后无法确认原实例已休眠，请检查实例状态";}
        }
        c.verifiedAt=failure==null?Instant.now():null;c.lastMessage=failure;connections.saveAndFlush(c);
        event(r,failure==null?"VERIFY_RUNTIME_CONNECTION":"VERIFY_RUNTIME_CONNECTION_FAILED",requester);
        // Return an honest PENDING state on failure so clearing previous readiness is committed.
        return status(key);
    }
    private com.fasterxml.jackson.databind.JsonNode probe(AgentRuntimeInstance r,String nonce)throws Exception{
        URI uri=URI.create(substrate.getRouterUrl().replaceAll("/+$","")+"/eap/integration");
        if(uri.getHost()==null||uri.getUserInfo()!=null||!Set.of("http","https").contains(uri.getScheme()))throw new BusinessException("运行路由未配置");
        var connection=(HttpURLConnection)uri.toURL().openConnection();
        var timer=deadlines.schedule(connection::disconnect,12,TimeUnit.SECONDS);
        try{
            connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setConnectTimeout(3000);connection.setReadTimeout(9000);
            connection.setRequestProperty(SubstrateActorRef.ROUTING_HEADER,r.instanceKey);connection.setRequestProperty("Content-Type","application/json");connection.setDoOutput(true);
            byte[] body=json.writeValueAsBytes(Map.of("nonce",nonce));connection.setFixedLengthStreamingMode(body.length);
            try(var out=connection.getOutputStream()){out.write(body);}
            if(connection.getResponseCode()!=200)throw new BusinessException("接入握手不可用");
            byte[] raw;try(var in=connection.getInputStream()){raw=in.readNBytes(4097);}
            if(raw.length>4096)throw new BusinessException("握手响应超限");return json.readTree(raw);
        }finally{timer.cancel(false);connection.disconnect();}
    }
    @Transactional(readOnly=true)
    public String evidence(String key){
        var c=connections.findByInstanceKey(key).orElse(null);
        if(!valid(c)||c.verifiedAt==null)return "UNVERIFIED";
        return c.gatewayAt!=null&&c.traceAt!=null?"SCOPED_PATH":"TASK_READY";
    }
    @Transactional(readOnly=true)
    public boolean executable(AgentRuntimeInstance runtime){
        var c=connections.findByInstanceKey(runtime.instanceKey).orElse(null);
        return runtime.supportsMachineIdentity() && runtime.getStatus()!=RuntimeStatus.OFFLINE && (valid(c)&&c.verifiedAt!=null || config.deliveryReady()&&c!=null&&c.automaticDelivery&&c.generation!=null&&c.identityExpiresAt!=null&&c.identityExpiresAt.isAfter(Instant.now()));
    }
    @Transactional(readOnly=true)
    public void checkIdentity(AgentRuntimeInstance runtime){current(bound(runtime.instanceKey));}
    /** Fresh installed epoch, isolated from an enclosing contract's MySQL snapshot. */
    @Transactional(propagation=Propagation.REQUIRES_NEW,readOnly=true)
    public String inspectionGeneration(String instance) {
        var r=bound(instance);var c=connections.findByInstanceKey(r.instanceKey).orElse(null);
        if(!valid(c)||c.verifiedAt==null||c.deliveryOperation!=null)throw new BusinessException("实例尚未验证任务协议或凭证已失效");
        return c.generation;
    }
    // Renewal commits independently. Suspend the caller's persistence context and
    // database snapshot so this read uses the installed lease, even after admission.
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public String dispatchToken(AgentRuntimeInstance runtime){
        if(delivery!=null)delivery.ensure(runtime.instanceKey);
        var r=bound(runtime.instanceKey);var c=connections.findByInstanceKey(r.instanceKey).orElse(null);
        if(!valid(c)||c.verifiedAt==null)throw new BusinessException("实例尚未验证任务协议或凭证已失效");
        current(r);return token(r,c);
    }
    /** Inspect readiness using the installed credential, without delivery or renewal. */
    @Transactional(readOnly=true)
    public String inspectionToken(AgentRuntimeInstance runtime){
        var r=bound(runtime.instanceKey);var c=connections.findByInstanceKey(r.instanceKey).orElse(null);
        if(!valid(c)||c.verifiedAt==null||c.deliveryOperation!=null)throw new BusinessException("实例尚未验证任务协议或凭证已失效");
        current(r);return token(r,c);
    }
    @Transactional(readOnly=true)
    public Access authenticate(String key,String supplied){
        try{
            var r=bound(key);var c=connections.findByInstanceKey(key).orElse(null);
            if(r.status==RuntimeStatus.OFFLINE||!valid(c)||c.verifiedAt==null||!equal(token(r,c),supplied)||agents.get(r.agentId).lifecycleStatus()!=AgentLifecycleStatus.ACTIVE)throw new IllegalArgumentException();
            if("SUBSTRATE_TASK".equals(r.protocol)){
                var task=taskIdentities.identityByInstanceKey(key).orElseThrow(IllegalArgumentException::new);
                if(task.identityRevoked()||task.identityUntil()==null||!task.identityUntil().isAfter(Instant.now())||!Objects.equals(r.getBoundActorUid(),task.actorUid())||!Objects.equals(r.getBoundActorTemplate(),task.actorTemplate()))throw new IllegalArgumentException();
            }
            current(r);return new Access(key,r.agentId,c.generation,"runtime-"+r.id);
        }catch(RuntimeException e){throw new UnauthorizedException("实例凭证无效、过期或当前身份不可用");}
    }
    public void validateTaskProtocol(Access access,String path){
        if("SUBSTRATE_TASK".equals(bound(access.instanceKey()).protocol) && !Set.of("/api/runtime-access/v1/chat/completions","/api/runtime-access/v1/traces").contains(path))throw new UnauthorizedException("任务实例只支持原任务模型与观测通道，不能使用其他执行入口");
    }
    @Transactional(readOnly=true)
    public void validateLegacyTaskAttribution(String taskKey){
        if(taskKey!=null && !creationDb.queryForList("select task_key from ax_tasks where task_key=? and identity_mode='PER_INSTANCE_V1'",String.class,taskKey).isEmpty())throw taskFailure("专属任务只接受原运行实例的机器身份");
    }
    /** Network-task attribution is bound to this exact actor, not merely the same Agent. */
    @Transactional(readOnly=true)
    public void validateTaskAttribution(Access access,String taskKey,String invocationKey){
        var r=bound(access.instanceKey());
        var rows=taskKey==null?List.<Map<String,Object>>of():creationDb.queryForList("select task_key,agent_id,instance_key,actor_uid,actor_template,status,identity_revoked from ax_tasks where task_key=? and identity_mode='PER_INSTANCE_V1'",taskKey);
        if("SUBSTRATE_TASK".equals(r.protocol)&&rows.isEmpty())throw taskFailure("任务实例只能关联其原任务");
        if(rows.isEmpty())return;
        var task=rows.getFirst();
        if(!access.instanceKey().equals(task.get("instance_key"))||!Objects.equals(r.getBoundActorUid(),task.get("actor_uid"))||!Objects.equals(r.getBoundActorTemplate(),task.get("actor_template"))||!Objects.equals(access.agentId(),((Number)task.get("agent_id")).longValue())||!"RUNNING".equals(task.get("status"))||Boolean.TRUE.equals(task.get("identity_revoked")))throw taskFailure("原任务归属不匹配、已结束或已撤销");
        if(invocationKey!=null&&!invocationKey.equals("task:"+taskKey))throw taskFailure("任务模型调用使用固定的原任务意图，不能更换调用标识");
    }
    private com.eap.platform.gateway.GatewayException taskFailure(String message){return new com.eap.platform.gateway.GatewayException(org.springframework.http.HttpStatus.CONFLICT,"task_identity_mismatch",message);}
    /** Only EAP-issued experiment traces may retain their global identifier.
     * All other caller identifiers stay in the instance namespace. */
    @Transactional(readOnly=true)
    public String resolveTrace(Access access,String raw){
        String scoped=RuntimeScopedTelemetry.trace(access.instanceKey(),raw);
        var r=bound(access.instanceKey());var c=connections.findByInstanceKey(r.instanceKey).orElse(null);
        if(r.status==RuntimeStatus.OFFLINE||!Objects.equals(r.agentId,access.agentId())||!Objects.equals("runtime-"+r.id,access.endpointKey())||!valid(c)||!Objects.equals(c.generation,access.generation()))throw new UnauthorizedException("实例追踪身份已失效");
        String canonical=raw.toLowerCase(Locale.ROOT);
        var team=creationDb.queryForList("select s.role_ordinal,c.protocol_json from team_evolution_slots s join team_evolution_evaluations e on e.id=s.evaluation_id join team_evolution_contracts c on c.id=e.contract_id where s.trace_id=?",canonical);
        if(!team.isEmpty()) {
            com.fasterxml.jackson.databind.JsonNode role;
            try {role=new com.fasterxml.jackson.databind.ObjectMapper().readTree((String)team.getFirst().get("protocol_json")).path("roles").path(((Number)team.getFirst().get("role_ordinal")).intValue());}
            catch(Exception ex){throw new UnauthorizedException("团队原追踪不可读取");}
            if(team.size()!=1||role.path("agentId").asLong()!=access.agentId()||role.path("runtimeId").asLong()!=r.id
                    ||!role.path("instanceKey").asText().equals(r.instanceKey)||!role.path("actorUid").asText().equals(r.getBoundActorUid())
                    ||!role.path("actorTemplate").asText().equals(r.getBoundActorTemplate())||!role.path("endpoint").asText().equals(r.endpoint))throw new UnauthorizedException("团队追踪不属于原实例身份");
            return canonical; // Stopped original slots remain recognizable and cannot fall back to chat.
        }
        var issued=creationDb.queryForList("select e.agent_id,e.instance_key,e.actor_uid,e.template,e.endpoint from agent_experiment_trials t join agent_experiments e on e.id=t.experiment_id where t.trace_id=?",canonical);
        if(issued.isEmpty())return scoped;
        var frozen=issued.getFirst();
        if(issued.size()!=1||!Objects.equals(((Number)frozen.get("agent_id")).longValue(),access.agentId())||!Objects.equals(frozen.get("instance_key"),r.instanceKey)||!Objects.equals(frozen.get("actor_uid"),r.getBoundActorUid())||!Objects.equals(frozen.get("template"),r.getBoundActorTemplate())||!Objects.equals(frozen.get("endpoint"),r.endpoint))throw new UnauthorizedException("实验追踪不属于当前实例身份");
        // Keep completed/unknown records recognizable: existing budget admission
        // refuses stopped trials instead of reinterpreting them as ordinary chat.
        return canonical;
    }
    @Transactional
    public void recordGateway(Access access,String trace){
        var c=connections.findByInstanceKey(access.instanceKey()).orElse(null);
        if(valid(c)&&Objects.equals(c.generation,access.generation())){c.gatewayAt=Instant.now();c.gatewayTrace=trace;}
    }
    @Transactional
    public void recordTrace(Access access,Set<String> traces){
        var c=connections.findByInstanceKey(access.instanceKey()).orElse(null);
        if(valid(c)&&Objects.equals(c.generation,access.generation())&&c.gatewayTrace!=null&&traces.contains(c.gatewayTrace)&&spans.existsByTraceIdAndAgentIdAndEndpointKey(c.gatewayTrace,access.agentId(),access.endpointKey()))c.traceAt=Instant.now();
    }
    private AgentRuntimeInstance lockedRuntime(String key){
        connections.lockForToolAccess(key);
        var r=runtimes.lockForScopedTools(key).orElseThrow(()->new BusinessException("运行实例未纳管"));
        bound(key);return r;
    }
    private String token(AgentRuntimeInstance r,RuntimeConnection c){return credentialToken(config,r,c.generation,c.credentialRevision);}
    static String credentialToken(RuntimeConnectionProperties config,AgentRuntimeInstance r,String generation,String revision){
        String identity="eap-runtime-v1|"+r.instanceKey+"|"+r.agentId+"|"+r.getBoundActorUid()+"|"+r.getBoundActorTemplate()+"|"+generation;
        return "eap_rt_"+hmac(config.getSigningKey(),identity+(revision==null?"":"|lease:"+revision));
    }
    static String hmac(String key,String message){
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("无法生成实例凭证");}
    }
    private static boolean equal(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private void event(AgentRuntimeInstance r,String action,String subject){
        var e=new AuditLog();e.setActor(subject==null?"console":subject);e.setAction(action);e.setResourceType("AGENT");e.setResourceId(r.agentId.toString());
        e.setAfterJson("{\"instanceKey\":\""+r.instanceKey+"\"}");e.setResult(action.endsWith("FAILED")?"FAILED":"SUCCESS");e.setTraceId(TraceContext.traceId());e.setCreatedAt(Instant.now());audit.save(e);
    }
    @PreDestroy public void close(){deadlines.shutdownNow();}
}
