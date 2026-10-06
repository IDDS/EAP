package com.eap.platform.deployment;

import com.eap.platform.agent.*;
import com.eap.platform.common.*;
import com.eap.platform.registry.*;
import com.eap.platform.substrate.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Durable creation intent. Native creation and identity delivery run outside database transactions. */
@Service
public class PlatformRuntimeCreationService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final SubstrateClient client;
    private final AgentRegistryService registry;
    private final AgentRepository agents;
    private final RuntimeCredentialDeliveryService delivery;
    private final RuntimeConnectionService connections;
    record Intent(String deployment, String key, String uid, boolean attempted, String deliveryRequest, boolean revoked) {}

    public PlatformRuntimeCreationService(JdbcTemplate db, PlatformTransactionManager manager,
            SubstrateClient client, AgentRegistryService registry, AgentRepository agents,
            RuntimeCredentialDeliveryService delivery, RuntimeConnectionService connections) {
        this.db=db;this.client=client;this.registry=registry;this.agents=agents;this.delivery=delivery;this.connections=connections;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private Intent find(Long agent, String request) {
        return db.query("select * from platform_runtime_creations where agent_id=? and request_key=?",
            (r,n)->new Intent(r.getString("deployment_key"),r.getString("instance_key"),r.getString("actor_uid"),r.getBoolean("attempted"),r.getString("delivery_request"),r.getBoolean("revoked")),agent,request).stream().findFirst().orElse(null);
    }
    public String existingDeployment(Long agent, String request) {
        validate(request);var found=find(agent,request);return found==null?null:found.deployment();
    }
    private void validate(String request) {
        try { if(!UUID.fromString(request).toString().equals(request))throw new IllegalArgumentException(); }
        catch(Exception e){throw new BusinessException("创建实例需要固定的UUID请求标识");}
    }
    private void active(Long agent) {
        if(agents.findById(agent).filter(a->a.getLifecycleStatus()!=AgentLifecycleStatus.RETIRED).isEmpty())
            throw new BusinessException("已下线智能体不能创建或接入实例");
    }
    private void identity(SubstrateClient.ActorInventory actor, SubstrateActorRef ref, String template, String uid) {
        if(!ref.atespace().equals(actor.atespace())||!ref.name().equals(actor.name())
                ||!template.equals(actor.template())||actor.uid()==null
                ||!actor.uid().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
                ||uid!=null&&!uid.equals(actor.uid()))
            throw new ConflictException("实例身份或模板已变化，保持原创建请求等待复核");
    }
    public String create(SubstrateDeployment d, String request, List<String> egress) {
        validate(request);active(d.agentId);
        var existing=find(d.agentId,request);
        if(existing==null) {
            String name="a"+d.agentId+"-v"+d.version+"-"+request.replace("-","").substring(0,12);
            try {
                tx.executeWithoutResult(s->db.update("insert into platform_runtime_creations(agent_id,request_key,deployment_key,instance_key,delivery_request) values(?,?,?,?,?)",
                    d.agentId,request,d.deploymentKey,d.atespace+"/"+name,UUID.randomUUID().toString()));
            } catch(org.springframework.dao.DuplicateKeyException race) {
                if(find(d.agentId,request)==null)throw race;
            }
        }
        var intent=find(d.agentId,request);
        if(!d.deploymentKey.equals(intent.deployment()))throw new ConflictException("原创建请求属于另一部署版本");
        if(intent.revoked())throw new ConflictException("原创建请求已撤销，不能创建或重新接入");
        var ref=SubstrateActorRef.parse("substrate://"+intent.key());
        String template=d.atespace+"/"+d.templateName;
        if(intent.uid()==null) {
            // A lost native creation reply cannot establish ownership. Never adopt by name or send Create again.
            int claimed=tx.execute(s->db.update("update platform_runtime_creations set attempted=true where agent_id=? and request_key=? and attempted=false",d.agentId,request));
            if(claimed!=1)throw new ConflictException("原生创建结果尚未确认；保留原请求，请管理员核对，未创建另一实例");
            var actor=client.createActorWithIdentity(ref,d.templateName);
            identity(actor,ref,template,null);
            tx.executeWithoutResult(s->db.update("update platform_runtime_creations set actor_uid=? where agent_id=? and request_key=? and actor_uid is null",actor.uid(),d.agentId,request));
            intent=find(d.agentId,request);
        }
        identity(client.inspect(ref),ref,template,intent.uid());
        registry.bindPlatformRuntime(d.agentId,intent.key(),intent.uid(),template);
        // If policy fails, keep the same actor and UID. A retry cannot delete/recreate its identity.
        client.setEgressHostnames(ref,egress);
        identity(client.inspect(ref),ref,template,intent.uid());
        return intent.key();
    }
    public void connect(SubstrateDeployment d, String request, String subject) {
        active(d.agentId);var intent=find(d.agentId,request);
        if(intent==null||intent.uid()==null||!d.deploymentKey.equals(intent.deployment()))throw new ConflictException("创建身份尚未确认");
        if(intent.revoked())throw new ConflictException("原创建请求已撤销，不能创建或重新接入");
        var ref=SubstrateActorRef.parse("substrate://"+intent.key());
        identity(client.inspect(ref),ref,d.atespace+"/"+d.templateName,intent.uid());
        delivery.connectCreated(intent.key(),intent.deliveryRequest(),subject);
        if(!"READY".equals(connections.status(intent.key()).connection()))throw new BusinessException("专属身份尚未确认，实例不可派发；保持原接入请求");
    }
}
