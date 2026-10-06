package com.eap.platform.registry;

import com.eap.platform.agent.*;
import com.eap.platform.audit.*;
import com.eap.platform.common.*;
import com.eap.platform.substrate.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Control-plane push only. Runners never receive the platform signing key or API service account. */
@Service
public class RuntimeCredentialDeliveryService {
    @org.springframework.beans.factory.annotation.Autowired
    private com.eap.platform.ax.AxTaskRepository taskIdentities;
    private final RuntimeConnectionRepository connections;
    private final AgentRuntimeInstanceRepository runtimes;
    private final RuntimeConnectionProperties config;
    private final SubstrateProperties substrate;
    private final SubstrateClient client;
    private final AgentService agents;
    private final AuditLogRepository audit;
    private final ObjectMapper json;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("runtime-delivery-deadline").factory());
    record Pending(String key,Long agentId,String uid,String template,String epoch,String operation,String revision,Instant expiresAt,long serial,boolean redelivery) {}
    public RuntimeCredentialDeliveryService(RuntimeConnectionRepository connections,AgentRuntimeInstanceRepository runtimes,
        RuntimeConnectionProperties config,SubstrateProperties substrate,SubstrateClient client,AgentService agents,
        AuditLogRepository audit,ObjectMapper json,JdbcTemplate db,PlatformTransactionManager manager){
        this.connections=connections;this.runtimes=runtimes;this.config=config;this.substrate=substrate;this.client=client;
        this.agents=agents;this.audit=audit;this.json=json;this.db=db;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private AgentRuntimeInstance runtime(String key,boolean lock){
        // Match tool commit lock order: connection before runtime. The runtime lock covers a first insert.
        if(lock)connections.lockForToolAccess(key);
        var r=(lock?runtimes.lockForScopedTools(key):runtimes.findByInstanceKey(key)).orElseThrow(()->new BusinessException("运行实例未纳管"));
        if(!r.supportsMachineIdentity())throw new BusinessException("自动接入需要已复核的专属实例身份");
        if(agents.get(r.agentId).lifecycleStatus()==AgentLifecycleStatus.RETIRED||r.status==RuntimeStatus.OFFLINE)throw new BusinessException("已下线实例不能接入");
        return r;
    }
    private SubstrateClient.ActorInventory current(AgentRuntimeInstance r){
        var ref=SubstrateActorRef.parse(r.endpoint);
        if(!substrate.isEnabled()||!substrate.inventoryScopes().contains(ref.atespace()))throw new BusinessException("实例运行空间未获授权");
        var a=client.inspect(ref);
        if(!ref.atespace().equals(a.atespace())||!ref.name().equals(a.name())||!r.getBoundActorUid().equals(a.uid())||!Objects.equals(r.getBoundActorTemplate(),a.template()))throw new ConflictException("实例身份已变化，配送被阻断");
        return a;
    }
    private void requestKey(String key){try{if(!UUID.fromString(key).toString().equals(key))throw new IllegalArgumentException();}catch(Exception e){throw new BusinessException("接入请求标识须为UUID");}}
    public void connect(String key,String request,String subject){connect(key,request,subject,0);}
    public void connectCreated(String key,String request,String subject){connect(key,request,subject,1);}
    public void connectTaskCreated(String key,String request,String subject){connect(key,request,subject,2);}
    private Instant taskApproval(String key,String request){
        var row=taskIdentities.identityByInstanceKey(key).orElseThrow(()->new ConflictException("任务原接入授权无法确认"));
        var until=row.identityUntil();
        if(row.identityRevoked()||request!=null&&!request.equals(row.deliveryRequest())||!config.getDeliveryPublicKey().equals(row.identityPublicKey())||until==null||!until.isAfter(Instant.now()))throw new ConflictException("任务原接入授权已撤销、到期或变化");
        return until;
    }
    private void connect(String key,String request,String subject,int approval){
        boolean creation=approval!=0;
        if(!config.deliveryReady())throw new BusinessException("管理员尚未启用安全配送");
        requestKey(request);
        Pending pending=tx.execute(s->{
            var r=runtime(key,true);current(r);
            if("SUBSTRATE_TASK".equals(r.protocol)&&approval!=2)throw new BusinessException("任务实例只通过原任务安全接入，不能另行批准");
            if(approval==2&&!"SUBSTRATE_TASK".equals(r.protocol))throw new BusinessException("任务接入批准只能用于原任务实例");
            Instant taskUntil=approval==2?taskApproval(key,request):null;
            if(approval==1) {
                var cancelled=db.queryForList("select revoked from platform_runtime_creations where instance_key=? and delivery_request=?",Boolean.class,key,request);
                if(cancelled.size()!=1||cancelled.getFirst())throw new ConflictException("原创建接入请求已撤销，不能恢复原创建授权");
            }
            var c=connections.lockForToolAccess(key).orElseGet(RuntimeConnection::new);
            var seen=db.queryForList("select identity_epoch from runtime_delivery_requests where instance_key=? and request_key=?",String.class,key,request);
            if(!seen.isEmpty()){
                if(!c.automaticDelivery||c.generation==null||!c.generation.equals(seen.getFirst()))throw new ConflictException("原接入请求已撤销或被替代，请勿重放");
                if(c.deliveryOperation==null)return null;
                if(!request.equals(c.deliveryOperation))throw new ConflictException("已有另一项配送待核对，请先刷新状态");
            }else{
                if(c.deliveryOperation!=null&&!request.equals(c.deliveryOperation))throw new ConflictException("原配送尚未核对，请重试原请求");
                if(c.automaticDelivery&&(c.identityExpiresAt==null||!c.identityExpiresAt.isAfter(Instant.now())))throw new ConflictException("实例接入授权期已过，请先撤销后重新批准接入");
                if(c.generation==null)c.generation=UUID.randomUUID().toString();
                if(!c.automaticDelivery)c.identityExpiresAt=taskUntil==null?Instant.now().plusSeconds(86400):taskUntil;
                db.update("insert into runtime_delivery_requests(instance_key,request_key,identity_epoch) values(?,?,?)",key,request,c.generation);
                c.instanceKey=key;c.automaticDelivery=true;if(c.deliveryOperation==null)prepare(c,request);
                connections.saveAndFlush(c);event(r,"ENABLE_AUTOMATIC_RUNTIME_CONNECTION",subject);
            }
            return pending(r,c);
        });
        if(pending!=null)deliver(pending,true);
        else if(creation && connections.findByInstanceKey(key).filter(c->c.expiresAt==null||!c.expiresAt.isAfter(Instant.now())||c.identityExpiresAt==null||!c.identityExpiresAt.isAfter(Instant.now())).isPresent())ensure(key,true);
    }
    /** Called immediately before dispatch. An expired sleeping instance can obtain a fresh lease. */
    public void ensure(String key){ensure(key,false);}
    private void ensure(String key,boolean restoreSleep){
        Pending pending=tx.execute(s->{
            var flags=db.queryForList("select automatic_delivery from runtime_connections where instance_key=?",Boolean.class,key);
            if(flags.isEmpty()||!flags.getFirst())return null;
            if(!config.deliveryReady())throw new BusinessException("自动接入配送配置不可用");
            var r=runtime(key,true);current(r);
            if("SUBSTRATE_TASK".equals(r.protocol))taskApproval(key,null);
            var c=connections.lockForToolAccess(key).orElseThrow();
            if(!c.automaticDelivery||c.generation==null||c.identityExpiresAt==null||!c.identityExpiresAt.isAfter(Instant.now()))throw new BusinessException("自动接入已撤销");
            if(c.deliveryOperation==null&&c.verifiedAt!=null&&c.expiresAt!=null&&c.expiresAt.isAfter(Instant.now().plusSeconds(Math.min(config.getLeaseSeconds()/2,180))))return new Pending(r.instanceKey,r.agentId,r.getBoundActorUid(),r.getBoundActorTemplate(),c.generation,c.credentialRevision,c.credentialRevision,c.expiresAt,c.credentialSerial,true);
            if(c.deliveryOperation==null){prepare(c,UUID.randomUUID().toString());connections.saveAndFlush(c);}
            return pending(r,c);
        });
        if(pending!=null)deliver(pending,restoreSleep);
    }
    private void prepare(RuntimeConnection c,String operation){
        c.deliveryOperation=operation;c.deliveryRevision=UUID.randomUUID().toString();c.deliverySerial=Math.addExact(c.credentialSerial,1);
        c.deliveryExpiresAt=Instant.now().plusSeconds(config.getLeaseSeconds());
        if(c.identityExpiresAt!=null&&c.deliveryExpiresAt.isAfter(c.identityExpiresAt))c.deliveryExpiresAt=c.identityExpiresAt;c.lastMessage="安全配送待确认；原请求标识保留";
    }
    private Pending pending(AgentRuntimeInstance r,RuntimeConnection c){return new Pending(r.instanceKey,r.agentId,r.getBoundActorUid(),r.getBoundActorTemplate(),c.generation,c.deliveryOperation,c.deliveryRevision,c.deliveryExpiresAt,c.deliverySerial,false);}
    private void fence(Pending p,AgentRuntimeInstance r,RuntimeConnection c){
        if(!c.automaticDelivery||c.identityExpiresAt==null||!c.identityExpiresAt.isAfter(Instant.now())||!Objects.equals(c.generation,p.epoch())|| (p.redelivery()?c.deliveryOperation!=null||!Objects.equals(c.credentialRevision,p.revision())||c.credentialSerial!=p.serial():!Objects.equals(c.deliveryOperation,p.operation())||!Objects.equals(c.deliveryRevision,p.revision())||!Objects.equals(c.deliverySerial,p.serial()))||!Objects.equals(r.agentId,p.agentId())||!Objects.equals(r.getBoundActorUid(),p.uid())||!Objects.equals(r.getBoundActorTemplate(),p.template()))throw new ConflictException("配送已撤销或实例身份改变");
        current(r);
    }
    private void deliver(Pending p,boolean restoreSleep){
        var r=runtime(p.key(),false);var actor=current(r);boolean restore=restoreSleep&&"SUSPENDED".equals(actor.state());
        try{
            client.resume(SubstrateActorRef.parse(r.endpoint));current(r);
            // Commit the pending operation before network delivery. Lost replies resume this exact operation.
            boolean needed=Boolean.TRUE.equals(tx.execute(s->{
                var locked=runtime(p.key(),true);var c=connections.lockForToolAccess(p.key()).orElseThrow();
                if(!p.redelivery()&&c.deliveryOperation==null&&c.automaticDelivery&&Objects.equals(c.generation,p.epoch())&&Objects.equals(c.credentialRevision,p.revision()))return false;
                fence(p,locked,c);return true;
            }));
            if(!needed)return;
            var challenge=post(p.key(),"/eap/identity/challenge",signed(json.writeValueAsBytes(Map.of("operation",p.revision(),"serial",p.serial(),"expiresAt",Instant.now().plusSeconds(45).getEpochSecond()))));
            String nonce=challenge.path("nonce").asText();
            if(!p.uid().equals(challenge.path("actorUid").asText())||!p.key().equals(challenge.path("instanceKey").asText())||!p.revision().equals(challenge.path("operation").asText())||!nonce.matches("[A-Za-z0-9_-]{43}")||!"EAP_IDENTITY_V1".equals(challenge.path("protocol").asText()))throw new BusinessException("程序不支持安全配送协议");
            String token=RuntimeConnectionService.credentialToken(config,r,p.epoch(),p.revision());
            byte[] keyBytes=Base64.getUrlDecoder().decode(challenge.path("publicKey").asText());
            var publicKey=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
            if(!(publicKey instanceof java.security.interfaces.RSAPublicKey rsa)||rsa.getModulus().bitLength()!=2048)throw new BusinessException("配送公钥无效");
            var cipher=Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.ENCRYPT_MODE,publicKey,new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
            var body=new TreeMap<String,Object>();
            body.put("protocol","EAP_IDENTITY_V1");body.put("operation",p.revision());body.put("serial",p.serial());body.put("nonce",nonce);
            body.put("instanceKey",p.key());body.put("agentId",p.agentId().toString());body.put("actorUid",p.uid());
            body.put("identityEpoch",p.epoch());body.put("revision",p.revision());body.put("expiresAt",p.expiresAt().getEpochSecond());
            body.put("ciphertext",Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal(token.getBytes(StandardCharsets.UTF_8))));
            byte[] payload=json.writeValueAsBytes(body);
            var reply=post(p.key(),"/eap/identity/install",signed(payload));
            String proof=RuntimeConnectionService.hmac(token,"eap-delivery-v1|"+p.revision()+"|"+nonce+"|"+p.key()+"|"+p.revision());
            if(!p.revision().equals(reply.path("operation").asText())||!MessageDigest.isEqual(proof.getBytes(StandardCharsets.UTF_8),reply.path("proof").asText().getBytes(StandardCharsets.UTF_8)))throw new BusinessException("配送回执未确认");
            tx.executeWithoutResult(s->{
                var locked=runtime(p.key(),true);var c=connections.lockForToolAccess(p.key()).orElseThrow();
                if(!p.redelivery()&&c.deliveryOperation==null&&c.automaticDelivery&&Objects.equals(c.generation,p.epoch())&&Objects.equals(c.credentialRevision,p.revision()))return;
                fence(p,locked,c);
                if(p.redelivery())return;
                if(!p.expiresAt().isAfter(Instant.now()))throw new ConflictException("原配送已超过期限；保持原标识等待复核");
                c.credentialSerial=p.serial();c.credentialRevision=p.revision();c.expiresAt=p.expiresAt();c.verifiedAt=Instant.now();c.lastDeliveryAt=Instant.now();
                c.deliveryOperation=null;c.deliveryRevision=null;c.deliveryExpiresAt=null;c.deliverySerial=null;c.lastMessage=null;connections.saveAndFlush(c);
                event(locked,"DELIVER_RUNTIME_CREDENTIAL","runtime-controller");
            });
        }catch(BusinessException|ConflictException e){throw e;}
        catch(Exception e){throw new BusinessException("安全配送尚未确认，请刷新状态并使用原请求重试；未执行任务");}
        finally{
            if(restore){current(r);if(!"SUSPENDED".equals(client.suspend(SubstrateActorRef.parse(r.endpoint)).state()))throw new BusinessException("配送后未确认恢复休眠，请核对状态");}
        }
    }
    private Map<String,String> signed(byte[] payload)throws Exception{
        var signer=Signature.getInstance("Ed25519");signer.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(config.getDeliveryPrivateKey()))));signer.update(payload);
        return Map.of("payload",Base64.getUrlEncoder().withoutPadding().encodeToString(payload),"signature",Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
    }
    private com.fasterxml.jackson.databind.JsonNode post(String key,String path,Map<String,?> body)throws Exception{
        URI uri=URI.create(substrate.getRouterUrl().replaceAll("/+$","")+path);
        if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||!Set.of("http","https").contains(uri.getScheme()))throw new BusinessException("运行路由配置无效");
        var connection=(HttpURLConnection)uri.toURL().openConnection();var timer=deadlines.schedule(connection::disconnect,12,TimeUnit.SECONDS);
        try{
            connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setConnectTimeout(3000);connection.setReadTimeout(9000);
            connection.setRequestProperty(SubstrateActorRef.ROUTING_HEADER,key);connection.setRequestProperty("Content-Type","application/json");connection.setDoOutput(true);
            byte[] data=json.writeValueAsBytes(body);connection.setFixedLengthStreamingMode(data.length);try(var out=connection.getOutputStream()){out.write(data);}
            if(connection.getResponseCode()!=200)throw new BusinessException("安全配送接口未确认成功，请保留原请求");
            try(var in=connection.getInputStream()){byte[] raw=in.readNBytes(4097);if(raw.length>4096)throw new BusinessException("配送响应超限");return json.readTree(raw);}
        }finally{timer.cancel(false);connection.disconnect();}
    }
    private void event(AgentRuntimeInstance r,String action,String subject){
        var e=new AuditLog();e.setActor(subject);e.setAction(action);e.setResourceType("AGENT");e.setResourceId(r.agentId.toString());e.setAfterJson("{\"instanceKey\":\""+r.instanceKey+"\"}");e.setResult("SUCCESS");e.setCreatedAt(Instant.now());e.setTraceId(TraceContext.traceId());audit.save(e);
    }
    @PreDestroy public void close(){deadlines.shutdownNow();}
}
