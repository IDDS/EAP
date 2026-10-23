package com.eap.platform.registry;

import com.eap.platform.agent.*;
import com.eap.platform.audit.*;
import com.eap.platform.common.*;
import com.eap.platform.substrate.*;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** Live inventory is read-only. Enrollment binds an exact observed actor identity; it never changes its policy or template. */
@Service
public class RuntimeInventoryService {
    private final SubstrateClient client;
    private final SubstrateProperties properties;
    private final AgentRuntimeInstanceRepository runtimes;
    private final AgentService agents;
    private final AuditLogRepository audit;
    private final RuntimeConnectionService connections;
    public RuntimeInventoryService(SubstrateClient client, SubstrateProperties properties, AgentRuntimeInstanceRepository runtimes,
                                   AgentService agents, AuditLogRepository audit, RuntimeConnectionService connections) {
        this.client=client; this.properties=properties; this.runtimes=runtimes; this.agents=agents; this.audit=audit; this.connections=connections;
    }
    public record Item(String scope,String name,String uid,String template,String state,Long agentId,String agentName,
                       String enrollment,String governance) {}
    public record Scope(String name,String status,boolean truncated,List<Item> items) {}
    public record Snapshot(boolean enabled,Instant queriedAt,List<Scope> scopes) {}
    public record Adopt(@NotBlank @Size(max=63) String scope,@NotBlank @Size(max=63) String name,
                        @NotBlank @Size(max=64) String uid,@NotBlank @Size(max=128) String template,@NotNull Long agentId) {}

    @Transactional(readOnly=true)
    public Snapshot snapshot() {
        if (!properties.isEnabled()) return new Snapshot(false,Instant.now(),List.of());
        var visible=new HashMap<Long,String>();
        agents.list().forEach(a -> visible.put(a.id(),a.name()));
        var registered=runtimes.findAll();
        var index=new HashMap<String,AgentRuntimeInstance>();
        registered.forEach(runtime -> index.put(runtime.instanceKey,runtime));
        var deadline=Instant.now().plusSeconds(15);
        var result=new ArrayList<Scope>();
        for (String scope:properties.inventoryScopes()) {
            var items=new ArrayList<Item>();
            var seen=new HashSet<String>();
            boolean truncated=false;
            String token="";
            var tokens=new HashSet<String>();
            try {
                for(int page=0;page<5;page++) {
                    if(Instant.now().isAfter(deadline)) throw new IllegalStateException("查询时间上限");
                    var next=client.listActors(scope,token);
                    for(var actor:next.actors()) {
                        if (!scope.equals(actor.atespace())) throw new IllegalStateException("范围不匹配");
                        if (!seen.add(actor.name())) continue;
                        var bound=index.get(scope+"/"+actor.name());
                        if (bound!=null && !visible.containsKey(bound.agentId)) continue;
                        items.add(item(actor,bound,visible));
                    }
                    token=next.nextPageToken();
                    if(token==null || token.isBlank()) break;
                    if(!tokens.add(token)) throw new IllegalStateException("分页重复");
                }
                truncated=token!=null&&!token.isBlank();
                if(!truncated) for(var bound:registered) {
                    if(!bound.hasBoundIdentity() || !bound.instanceKey.startsWith(scope+"/") || !visible.containsKey(bound.agentId)) continue;
                    String name=bound.instanceKey.substring(scope.length()+1);
                    if(!seen.contains(name)) items.add(new Item(scope,name,bound.getBoundActorUid(),bound.getBoundActorTemplate(),
                            "MISSING",bound.agentId,visible.get(bound.agentId),"MISSING","UNVERIFIED"));
                }
                result.add(new Scope(scope,"CONNECTED",truncated,List.copyOf(items)));
            } catch(RuntimeException e) {
                // Never present partial/missing data as a complete inventory or echo provider error messages.
                result.add(new Scope(scope,"UNAVAILABLE",true,List.of()));
            }
        }
        return new Snapshot(true,Instant.now(),result);
    }
    private Item item(SubstrateClient.ActorInventory actor,AgentRuntimeInstance bound,Map<Long,String> names) {
        String enrollment=bound==null?"UNMANAGED":bound.hasBoundIdentity()
            ? Objects.equals(bound.getBoundActorUid(),actor.uid())&&Objects.equals(bound.getBoundActorTemplate(),actor.template())
                ? bound.getImportedActorUid()!=null?"IMPORTED":"PLATFORM_IDENTITY" : "IDENTITY_CHANGED"
            : SubstrateActorRef.PROTOCOL.equalsIgnoreCase(bound.protocol)?"PLATFORM_MANAGED":"REGISTERED";
        return new Item(actor.atespace(),actor.name(),actor.uid(),actor.template(),actor.state(),
            bound==null?null:bound.agentId,bound==null?null:names.get(bound.agentId),enrollment,
            Set.of("IMPORTED","PLATFORM_IDENTITY").contains(enrollment)?Objects.toString(connections.evidence(bound.instanceKey),"UNVERIFIED")
                :"PLATFORM_MANAGED".equals(enrollment)?"PLATFORM_PATH":"UNVERIFIED");
    }

    @Transactional
    public Item adopt(Adopt request,String requester) {
        if(!properties.isEnabled() || !properties.inventoryScopes().contains(request.scope())) throw new BusinessException("运行空间不在允许纳管范围内");
        var ref=SubstrateActorRef.parse("substrate://"+request.scope()+"/"+request.name());
        var agent=agents.get(request.agentId());
        if(agent.lifecycleStatus()==AgentLifecycleStatus.RETIRED) throw new BusinessException("已下线智能体不能纳管实例");
        var current=client.inspect(ref);
        if(!ref.atespace().equals(current.atespace()) || !ref.name().equals(current.name())
                || !request.uid().equals(current.uid()) || !request.template().equals(current.template()))
            throw new ConflictException("实例身份或模板已变化，请刷新发现列表后重新复核");
        var existing=runtimes.findByInstanceKey(ref.routingValue()).orElse(null);
        if(existing!=null) {
            if(!request.agentId().equals(existing.agentId) || !request.uid().equals(existing.importedActorUid)
                    || !request.template().equals(existing.importedActorTemplate))
                throw new ConflictException("实例已有归属，不允许覆盖绑定");
            return item(current,existing,Map.of(agent.id(),agent.name()));
        }
        var runtime=new AgentRuntimeInstance();
        runtime.agentId=agent.id(); runtime.instanceKey=ref.routingValue(); runtime.endpoint="substrate://"+ref.routingValue();
        runtime.protocol=SubstrateActorRef.OBSERVED_PROTOCOL; runtime.importedActorUid=current.uid(); runtime.importedActorTemplate=current.template();
        // Enrollment is neither a heartbeat nor proof of business readiness.
        runtime.status=RuntimeStatus.DEGRADED;
        runtimes.saveAndFlush(runtime);
        var event=new AuditLog();
        event.setActor(requester==null?"console":requester);event.setAction("ENROLL_RUNTIME_INSTANCE");
        event.setResourceType("AGENT");event.setResourceId(String.valueOf(agent.id()));
        event.setAfterJson("{\"instanceKey\":\""+ref.routingValue()+"\",\"governance\":\"UNVERIFIED\"}");
        event.setResult("SUCCESS");event.setTraceId(TraceContext.traceId());event.setCreatedAt(Instant.now());audit.save(event);
        return item(current,runtime,Map.of(agent.id(),agent.name()));
    }
}
