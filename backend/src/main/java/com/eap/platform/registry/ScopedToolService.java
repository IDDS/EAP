package com.eap.platform.registry;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.audit.*;
import com.eap.platform.budget.BudgetLedger;
import com.eap.platform.common.*;
import com.eap.platform.config.UnauthorizedException;
import com.eap.platform.gateway.GatewayException;
import com.eap.platform.observability.ToolInvocationKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** The controlled tool store is the authority for effects; runtime receipts cannot write this store. */
@Service
public class ScopedToolService {
    public static final String TOOL="controlled_counter_increment", CONTRACT="poc-counter-v1";
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RuntimeConnectionRepository connections;
    private final AgentRuntimeInstanceRepository runtimes;
    private final AgentRepository agents;
    private final RuntimeToolPolicyRepository policyRows;
    private final RuntimeToolPolicyService policies;
    private final RuntimeToolGuardService guard;
    private final BudgetLedger budget;
    private final AuditLogRepository audit;
    private final TransactionTemplate tx;
    private final com.eap.platform.workflow.WorkflowToolEffectFence effectFence;
    public ScopedToolService(JdbcTemplate db,ObjectMapper json,RuntimeConnectionRepository connections,
        AgentRuntimeInstanceRepository runtimes,AgentRepository agents,RuntimeToolPolicyRepository policyRows,
        RuntimeToolPolicyService policies,RuntimeToolGuardService guard,BudgetLedger budget,
        AuditLogRepository audit,PlatformTransactionManager transactions,com.eap.platform.workflow.WorkflowToolEffectFence effectFence) {
        this.effectFence=effectFence;this.db=db;this.json=json;this.connections=connections;this.runtimes=runtimes;this.agents=agents;
        this.policyRows=policyRows;this.policies=policies;this.guard=guard;this.budget=budget;this.audit=audit;
        tx=new TransactionTemplate(transactions);
        tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    private void lock(){db.queryForObject("select id from scoped_tool_lock where id=1 for update",Long.class);}
    private record Row(ScopedToolDtos.Grant v,String uid,String generation,String fingerprint,List<ScopedToolDtos.Binding> bindings) {}
    private Row row(ResultSet r)throws SQLException {
        return new Row(new ScopedToolDtos.Grant(r.getString("grant_id"),r.getString("root_id"),r.getString("parent_id"),r.getString("holder_key"),r.getLong("agent_id"),r.getLong("policy_revision"),r.getString("contract_version"),read(r.getString("resources_json"),String[].class),read(r.getString("recipients_json"),String[].class),r.getLong("max_delta"),r.getLong("claim_limit"),r.getLong("used_count"),r.getInt("depth"),r.getString("state"),instant(r,"expires_at"),instant(r,"created_at")),r.getString("actor_uid"),r.getString("generation"),r.getString("fingerprint"),read(r.getString("bindings_json"),ScopedToolDtos.Binding[].class));
    }
    private Row find(String id){uuid(id);return db.query("select * from scoped_tool_grants where grant_id=?",(r,n)->row(r),id).stream().findFirst().orElseThrow(()->new BusinessException("范围授权不存在"));}
    private List<Row> chain(Row leaf){
        var result=new ArrayList<Row>();var seen=new HashSet<String>();Row r=leaf;
        while(true){if(result.size()>=5||!seen.add(r.v.grantId()))throw new IllegalStateException("委托链无效");result.add(r);if(r.v.parentGrantId()==null)break;r=find(r.v.parentGrantId());}
        if(!result.getLast().v.grantId().equals(leaf.v.rootId()))throw new IllegalStateException("根授权不一致");return result;
    }
    private AgentRuntimeInstance runtime(String key){
        var r=runtimes.lockForScopedTools(key).orElseThrow(()->new BusinessException("运行实例未纳管"));
        if(!r.supportsMachineIdentity())throw new BusinessException("仅支持具有专属身份的实例");return r;
    }
    // Order: scope lock -> enrolled workflow lock -> sorted connection/agent/policy locks -> shared ledger lock.
    private Map<String,RuntimeConnection> lockConnections(Collection<String> keys){
        var out=new TreeMap<String,RuntimeConnection>();for(String key:new TreeSet<>(keys)){out.put(key,connections.lockForToolAccess(key).orElseThrow(()->new UnauthorizedException("实例接入不可用")));runtime(key);}return out;
    }
    private Instant authorityExpiry(RuntimeConnection c){return c.automaticDelivery?c.identityExpiresAt:c.expiresAt;}
    private void active(RuntimeConnection c){if(c.generation==null||c.verifiedAt==null||c.expiresAt==null||!c.expiresAt.isAfter(Instant.now())||authorityExpiry(c)==null||!authorityExpiry(c).isAfter(Instant.now()))throw new UnauthorizedException("实例接入已失效");}
    private void owner(RuntimeConnectionService.Access a,Row g,Map<String,RuntimeConnection> cs){
        var c=cs.get(a.instanceKey());active(c);
        if(!g.v.holderKey().equals(a.instanceKey())||g.v.agentId()!=a.agentId()||!g.generation.equals(a.generation())||!c.generation.equals(a.generation()))throw new UnauthorizedException("范围授权与实例身份不一致");
    }
    private Map<Long,RuntimeToolPolicy> lockPolicies(Collection<Long> ids){
        var result=new TreeMap<Long,RuntimeToolPolicy>();
        for(Long id:new TreeSet<>(ids)){agents.lockForToolPolicy(id).orElseThrow(()->new BusinessException("智能体不存在"));result.put(id,policyRows.lockByAgentId(id).orElse(null));}return result;
    }
    private String invalid(List<Row> chain,Map<String,RuntimeConnection> cs,Map<Long,RuntimeToolPolicy> ps,boolean spend){
        Instant now=Instant.now();
        for(var row:chain){var g=row.v;var c=cs.get(g.holderKey());var r=runtime(g.holderKey());
            if(!CONTRACT.equals(g.contractVersion()))return "CONTRACT_CHANGED";
            if(!"ACTIVE".equals(g.state()))return "GRANT_REVOKED";
            if(!g.expiresAt().isAfter(now))return "GRANT_EXPIRED";
            if(c.generation==null||!Objects.equals(c.generation,row.generation)||c.verifiedAt==null||c.expiresAt==null||!c.expiresAt.isAfter(now)||authorityExpiry(c)==null||!authorityExpiry(c).isAfter(now)||!row.uid.equals(r.getBoundActorUid())||g.agentId()!=r.agentId)return "INSTANCE_CHANGED";
            var agent=agents.findById(g.agentId()).orElseThrow();
            if(agent.getLifecycleStatus()!=com.eap.platform.agent.AgentLifecycleStatus.ACTIVE)return "AGENT_INACTIVE";
            var p=ps.get(g.agentId());String denied=policies.denial(p,TOOL,"TOOL");if(denied!=null)return denied;
            if(p.revision!=g.policyRevision())return "TOOL_POLICY_CHANGED";
        }
        if(spend)for(var row:chain){var g=row.v;var p=ps.get(g.agentId());if(g.usedCount()>=g.claimLimit()||p.claimedCount>=p.claimLimit)return "TOOL_BUDGET_EXHAUSTED";}
        return null;
    }
    private List<String> strings(List<String> list){if(list==null||list.isEmpty()||list.size()>8||list.stream().anyMatch(Objects::isNull)||new HashSet<>(list).size()!=list.size())throw new BusinessException("范围须为1–8个不同标识");return list.stream().sorted().toList();}
    private void bounds(long delta,long limit,Instant expiry){if(delta<1||delta>100||limit<1||limit>32||expiry==null||!expiry.isAfter(Instant.now())||expiry.isAfter(Instant.now().plusSeconds(3600)))throw new BusinessException("授权范围、额度或期限无效");}
    private void resourceIds(List<String> ids){if(ids.stream().anyMatch(id->!id.matches("poc_[A-Za-z0-9_-]{1,60}")))throw new BusinessException("仅支持受控测试计数对象");}
    @Transactional
    public ScopedToolDtos.Grant create(ScopedToolDtos.Root request,String subject){
        lock();uuid(request.grantId());var resources=strings(request.resourceSet());resourceIds(resources);var recipients=strings(request.recipientSet());
        String fp=hash(write(List.of(subject,request.holderKey(),resources,recipients,request.maxDelta(),request.claimLimit(),request.expiresAt())));
        var old=db.query("select * from scoped_tool_grants where grant_id=?",(r,n)->row(r),request.grantId());
        if(!old.isEmpty()){if(!old.getFirst().fingerprint.equals(fp))throw new ConflictException("授权标识已对应不同请求");return old.getFirst().v;}
        bounds(request.maxDelta(),request.claimLimit(),request.expiresAt());if(!recipients.contains(request.holderKey()))throw new BusinessException("接收方范围须包含当前持有实例");
        var cs=lockConnections(recipients);cs.values().forEach(this::active);
        var holder=runtime(request.holderKey());var p=lockPolicies(List.of(holder.agentId)).get(holder.agentId);String denied=policies.denial(p,TOOL,"TOOL");if(denied!=null)throw new BusinessException(denied);
        if(request.expiresAt().isAfter(authorityExpiry(cs.get(request.holderKey()))))throw new BusinessException("授权期限不能超过持有实例接入授权");
        var bindings=recipients.stream().map(key->{var r=runtime(key);return new ScopedToolDtos.Binding(key,r.agentId,r.getBoundActorUid());}).toList();
        insert(request.grantId(),request.grantId(),null,holder,cs.get(request.holderKey()),p,resources,recipients,bindings,request.maxDelta(),request.claimLimit(),0,fp,request.expiresAt(),subject);
        for(String resource:resources){if(db.queryForObject("select count(*) from scoped_tool_counters where resource_id=?",Long.class,resource)>0)throw new ConflictException("测试对象已属于另一根授权");db.update("insert into scoped_tool_counters(resource_id,root_id) values(?,?)",resource,request.grantId());}
        budget.createToolRootBudget(request.grantId(),request.claimLimit(),subject);event("SCOPED_TOOL_ROOT_CREATED",request.grantId(),subject);return find(request.grantId()).v;
    }
    @Transactional
    public ScopedToolDtos.Grant delegate(RuntimeConnectionService.Access access,ScopedToolDtos.Delegate request){
        lock();uuid(request.grantId());var parent=find(request.parentGrantId());var ancestors=chain(parent);var resources=strings(request.resourceSet());resourceIds(resources);var recipients=strings(request.recipientSet());
        var keys=new TreeSet<String>(recipients);keys.add(access.instanceKey());ancestors.forEach(g->keys.add(g.v.holderKey()));var cs=lockConnections(keys);owner(access,parent,cs);
        String fp=hash(write(List.of(access.instanceKey(),access.generation(),request.parentGrantId(),request.holderKey(),resources,recipients,request.maxDelta(),request.claimLimit(),request.expiresAt())));
        var old=db.query("select * from scoped_tool_grants where grant_id=?",(r,n)->row(r),request.grantId());if(!old.isEmpty()){if(!old.getFirst().fingerprint.equals(fp))throw new ConflictException("委托标识已对应不同请求");return old.getFirst().v;}
        bounds(request.maxDelta(),request.claimLimit(),request.expiresAt());
        if(parent.v.depth()>=4||!parent.v.resourceSet().containsAll(resources)||!parent.v.recipientSet().containsAll(recipients)||!recipients.contains(request.holderKey())||request.maxDelta()>parent.v.maxDelta()||request.claimLimit()>parent.v.claimLimit()||request.expiresAt().isAfter(parent.v.expiresAt()))throw new BusinessException("委托必须收窄父授权，不能扩大对象、接收方、参数、额度或期限");
        var holder=runtime(request.holderKey());var ids=new TreeSet<Long>();ancestors.forEach(g->ids.add(g.v.agentId()));ids.add(holder.agentId);var ps=lockPolicies(ids);
        String denied=invalid(ancestors,cs,ps,false);if(denied!=null)throw new BusinessException(denied);denied=policies.denial(ps.get(holder.agentId),TOOL,"TOOL");if(denied!=null)throw new BusinessException(denied);
        cs.values().forEach(this::active);if(request.expiresAt().isAfter(authorityExpiry(cs.get(request.holderKey()))))throw new BusinessException("委托期限超过接收方接入授权");
        var bindings=new ArrayList<ScopedToolDtos.Binding>();for(String key:recipients){var r=runtime(key);var binding=parent.bindings.stream().filter(b->b.instanceKey().equals(key)).findFirst().orElseThrow();if(binding.agentId()!=r.agentId||!binding.actorUid().equals(r.getBoundActorUid()))throw new BusinessException("接收实例身份已变化");bindings.add(binding);}
        insert(request.grantId(),parent.v.rootId(),parent.v.grantId(),holder,cs.get(request.holderKey()),ps.get(holder.agentId),resources,recipients,bindings,request.maxDelta(),request.claimLimit(),parent.v.depth()+1,fp,request.expiresAt(),access.instanceKey());
        event("SCOPED_TOOL_DELEGATED",request.grantId(),access.instanceKey());return find(request.grantId()).v;
    }
    private void insert(String id,String root,String parent,AgentRuntimeInstance holder,RuntimeConnection c,RuntimeToolPolicy p,List<String> resources,List<String> recipients,List<ScopedToolDtos.Binding> bindings,long maxDelta,long limit,int depth,String fp,Instant expires,String actor){
        db.update("insert into scoped_tool_grants(grant_id,root_id,parent_id,holder_key,agent_id,actor_uid,generation,policy_revision,contract_version,resources_json,recipients_json,bindings_json,max_delta,claim_limit,depth,fingerprint,state,expires_at,created_at,created_by) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,CURRENT_TIMESTAMP,?)",id,root,parent,holder.instanceKey,holder.agentId,holder.getBoundActorUid(),c.generation,p.revision,CONTRACT,write(resources),write(recipients),write(bindings),maxDelta,limit,depth,fp,Timestamp.from(expires),actor);
    }
    @Transactional
    public ScopedToolDtos.Grant revoke(String id,String subject){lock();var r=find(id);if(!"REVOKED".equals(r.v.state())){db.update("update scoped_tool_grants set state='REVOKED' where grant_id=?",id);event("SCOPED_TOOL_REVOKED",id,subject);}return find(id).v;}
    private record Prepared(String root,String fp,boolean fresh) {}
    public ScopedToolDtos.Operation execute(RuntimeConnectionService.Access access,ScopedToolDtos.Execute request){
        uuid(request.operationId());uuid(request.grantId());resourceIds(List.of(request.resourceId()));if(request.delta()<1||request.delta()>100)throw new BusinessException("参数超出工具合同");
        Prepared p=tx.execute(status->{lock();var g=find(request.grantId());effectFence.assess(g.v.rootId(),access,request);var cs=lockConnections(List.of(access.instanceKey()));owner(access,g,cs);
            String fp=hash(write(List.of(access.instanceKey(),access.generation(),request.grantId(),request.resourceId(),request.delta())));
            var old=operations(g.v.rootId(),request.operationId(),false);if(!old.isEmpty()){
                String recorded=db.queryForObject("select fingerprint from scoped_tool_operations where root_id=? and operation_id=?",String.class,g.v.rootId(),request.operationId());if(!fp.equals(recorded))throw new ConflictException("根任务操作标识已对应不同委托或参数");return new Prepared(g.v.rootId(),fp,false);
            }
            String trace=RuntimeScopedTelemetry.trace(access.instanceKey(),hash(request.operationId()).substring(0,32));
            db.update("insert into scoped_tool_operations(root_id,operation_id,grant_id,holder_key,generation,agent_id,resource_id,delta_value,trace_id,fingerprint,state,reason_code,effect_source,created_at) values(?,?,?,?,?,?,?,?,?,?,'PREPARED','COMMIT_UNCONFIRMED','UNKNOWN',CURRENT_TIMESTAMP)",g.v.rootId(),request.operationId(),g.v.grantId(),access.instanceKey(),access.generation(),access.agentId(),request.resourceId(),request.delta(),trace,fp);return new Prepared(g.v.rootId(),fp,true);
        });
        if(!p.fresh)return tx.execute(status->{lock();return operations(p.root,request.operationId(),true).getFirst();});
        try{return tx.execute(status->commit(access,request,p.root));}
        catch(com.eap.platform.workflow.WorkflowToolEffectFence.ExpiredResponsibility failure) {
            return tx.execute(status->{lock();reject(p.root,request.operationId(),failure.getMessage());return operations(p.root,request.operationId(),false).getFirst();});
        }
        catch(GatewayException failure){
            if(!"REJECTED".equals(failure.admissionOutcome()))throw failure;
            return tx.execute(status->{lock();var current=operations(p.root,request.operationId(),false).getFirst();if("UNKNOWN".equals(current.state()))reject(p.root,request.operationId(),"SHARED_BUDGET_EXHAUSTED");return operations(p.root,request.operationId(),false).getFirst();});
        }
    }
    private ScopedToolDtos.Operation commit(RuntimeConnectionService.Access access,ScopedToolDtos.Execute request,String root){
        lock();var current=operations(root,request.operationId(),false).getFirst();if(!"UNKNOWN".equals(current.state()))return current;
        String fenced=effectFence.assess(root,access,request);
        if(fenced!=null){reject(root,request.operationId(),fenced);return operations(root,request.operationId(),false).getFirst();}
        var g=find(request.grantId());var ancestors=chain(g);var cs=lockConnections(ancestors.stream().map(r->r.v.holderKey()).toList());owner(access,g,cs);
        var ps=lockPolicies(ancestors.stream().map(r->r.v.agentId()).toList());String denied=invalid(ancestors,cs,ps,true);
        if(denied==null&&(!g.v.resourceSet().contains(request.resourceId())||request.delta()>g.v.maxDelta()))denied="PARAMETER_SCOPE_DENIED";
        if(denied!=null){reject(root,request.operationId(),denied);return operations(root,request.operationId(),false).getFirst();}
        var check=guard.checkValidatedCounter(access,request,hash(request.operationId()).substring(0,32));
        if(!"APPROVED".equals(check.state())){reject(root,request.operationId(),check.reasonCode());return operations(root,request.operationId(),false).getFirst();}
        for(Long agent:ps.keySet()){var call=new RuntimeToolCall();call.agentId=agent;call.toolName=TOOL;call.toolKind="TOOL";call.policyRevision=ps.get(agent).revision;if(policies.consume(call)!=null)throw new IllegalStateException("授权锁内发生变化");}
        budget.claimScopedTool(root,new ArrayList<>(ps.keySet()),access.agentId(),request.operationId(),hash(write(request)),current.traceId());
        effectFence.beforeEffect(root,access,request);
        int changed=db.update("update scoped_tool_counters set counter_value=counter_value+?,effect_count=effect_count+1 where resource_id=? and root_id=?",request.delta(),request.resourceId(),root);if(changed!=1)throw new IllegalStateException("工具对象不属于根授权");
        long value=db.queryForObject("select counter_value from scoped_tool_counters where resource_id=?",Long.class,request.resourceId());String effect=UUID.randomUUID().toString();
        for(var r:ancestors)db.update("update scoped_tool_grants set used_count=used_count+1 where grant_id=?",r.v.grantId());
        db.update("update scoped_tool_operations set state='COMMITTED',reason_code='TOOL_STORE_COMMITTED',effect_id=?,counter_value=?,effect_source='TOOL_STORE_VERIFIED',completed_at=CURRENT_TIMESTAMP where root_id=? and operation_id=?",effect,value,root,request.operationId());
        guard.recordVerified(access,request.operationId(),root,effect,hash(write(Map.of("counterValue",value,"effectId",effect))));
        return operations(root,request.operationId(),false).getFirst();
    }
    private void reject(String root,String operation,String reason){db.update("update scoped_tool_operations set state='REJECTED',reason_code=?,effect_source='TOOL_STORE_NO_COMMIT',completed_at=CURRENT_TIMESTAMP where root_id=? and operation_id=? and state='PREPARED'",reason,root,operation);}
    private List<ScopedToolDtos.Operation> operations(String root,String id,boolean replay){return db.query("select * from scoped_tool_operations where root_id=? and operation_id=?",(r,n)->operation(r,replay),root,id);}
    private ScopedToolDtos.Operation operation(ResultSet r,boolean replay)throws SQLException {
        return new ScopedToolDtos.Operation(r.getString("root_id"),r.getString("operation_id"),r.getString("grant_id"),r.getString("holder_key"),r.getString("resource_id"),r.getLong("delta_value"),"PREPARED".equals(r.getString("state"))?"UNKNOWN":r.getString("state"),r.getString("reason_code"),r.getString("effect_id"),r.getObject("counter_value",Long.class),r.getString("effect_source"),r.getString("trace_id"),instant(r,"created_at"),instant(r,"completed_at"),replay);
    }
    @Transactional
    public ScopedToolDtos.Operation query(RuntimeConnectionService.Access access,String root,String operation){lock();uuid(root);uuid(operation);var result=operations(root,operation,true);if(result.isEmpty())throw new BusinessException("操作意图不存在");var o=result.getFirst();if(!access.instanceKey().equals(o.holderKey()))throw new UnauthorizedException("操作不属于此实例");return o;}
    /** Read the store's original effect; never retry a commit or reinterpret current authorization as past success. */
    @Transactional
    public boolean verifyEffect(String holder,ScopedToolDtos.Execute request,com.fasterxml.jackson.databind.JsonNode output){
        lock();var grant=find(request.grantId());var rows=operations(grant.v.rootId(),request.operationId(),true);
        if(rows.isEmpty()||output==null||!output.isObject())return false;var o=rows.getFirst();
        return "COMMITTED".equals(o.state())&&"TOOL_STORE_VERIFIED".equals(o.effectSource())
            &&Objects.equals(holder,o.holderKey())&&o.grantId().equals(request.grantId())
            &&o.resourceId().equals(request.resourceId())&&o.delta()==request.delta()
            &&o.rootId().equals(output.path("rootId").asText())&&o.operationId().equals(output.path("operationId").asText())
            &&o.effectId().equals(output.path("effectId").asText())&&o.effectSource().equals(output.path("evidenceSource").asText())
            &&output.path("counterValue").isIntegralNumber()&&output.path("counterValue").canConvertToLong()
            &&o.counterValue()!=null&&o.counterValue()==output.path("counterValue").longValue();
    }
    @Transactional(readOnly=true)
    public ScopedToolDtos.RecoveryBinding bindRecovery(String holder,ScopedToolDtos.Execute request) {
        uuid(request.operationId());var g=find(request.grantId());
        if(!holder.equals(g.v.holderKey())||!CONTRACT.equals(g.v.contractVersion()))
            throw new BusinessException("工具恢复绑定与原授权不一致");
        return new ScopedToolDtos.RecoveryBinding(g.v.rootId(),g.v.grantId(),holder,g.v.agentId(),g.uid,
            g.generation,g.v.policyRevision(),g.v.contractVersion(),
            hash(write(List.of(holder,g.generation,request.grantId(),request.resourceId(),request.delta()))));
    }
    @Transactional(readOnly=true)
    public void requireUnsubmittedRecoveryIntent(ScopedToolDtos.RecoveryBinding binding,ScopedToolDtos.Execute request) {
        if(!operations(binding.rootId(),request.operationId(),true).isEmpty())
            throw new ConflictException("原工具意图已存在，不能登记为新工作流派发责任");
    }
    /** Historical read only. Revocation does not erase facts; absence never proves no commit. */
    @Transactional(readOnly=true)
    public ScopedToolDtos.RecoveryEvidence recoverOriginal(ScopedToolDtos.RecoveryBinding binding,ScopedToolDtos.Execute request) {
        if(!Objects.equals(binding,bindRecovery(binding.holderKey(),request)))
            return new ScopedToolDtos.RecoveryEvidence("UNKNOWN","原身份、参数或合同绑定不匹配",null);
        var rows=operations(binding.rootId(),request.operationId(),true);
        if(rows.isEmpty())return new ScopedToolDtos.RecoveryEvidence("UNKNOWN","原操作记录缺失，不能推定未提交",null);
        var o=rows.getFirst();
        String fp=db.queryForObject("select fingerprint from scoped_tool_operations where root_id=? and operation_id=?",String.class,binding.rootId(),request.operationId());
        if(!binding.operationFingerprint().equals(fp)||!binding.holderKey().equals(o.holderKey())
                ||!binding.grantId().equals(o.grantId())||!request.resourceId().equals(o.resourceId())||request.delta()!=o.delta())
            return new ScopedToolDtos.RecoveryEvidence("UNKNOWN","原操作与冻结意图不匹配",null);
        if("COMMITTED".equals(o.state())&&"TOOL_STORE_VERIFIED".equals(o.effectSource())&&o.effectId()!=null&&o.counterValue()!=null)
            return new ScopedToolDtos.RecoveryEvidence("CONFIRMED","原工具存储已确认效果；未再次执行",o);
        if("REJECTED".equals(o.state())&&"TOOL_STORE_NO_COMMIT".equals(o.effectSource()))
            return new ScopedToolDtos.RecoveryEvidence("NO_COMMIT","原工具事务已明确拒绝提交；未退款或重试",o);
        return new ScopedToolDtos.RecoveryEvidence("UNKNOWN","原提交尚未确认，保留核对责任和原预算",o);
    }
    @Transactional(readOnly=true)
    public ScopedToolDtos.Catalog contracts(String instance) {
        var policy=policies.get(instance);
        var tools=new TreeMap<String,RuntimeToolPolicyService.Tool>();
        var controlled=new RuntimeToolPolicyService.Tool(TOOL,ToolInvocationKind.TOOL);
        tools.put("TOOL:"+TOOL,controlled);
        for(var tool:policy.allowedTools())tools.put(tool.kind().name()+":"+tool.name(),tool);
        var rows=tools.values().stream().map(tool->{
            boolean known=TOOL.equals(tool.name())&&tool.kind()==ToolInvocationKind.TOOL;
            boolean allowed=policy.enabled()&&policy.allowedTools().contains(tool);
            return known?new ScopedToolDtos.Contract(tool.name(),tool.kind().name(),CONTRACT,"TOOL_STORE",allowed,
                "CONSOLE_ROOT_AND_NARROWED_DELEGATION","ROOT_OBJECT_AND_RECIPIENT_SET","ROOT_AND_OPERATION",
                "PERSISTED_NO_AUTOMATIC_EXPIRY","CONFLICT_409","ORIGINAL_DURABLE_INTENT","UNSUPPORTED",
                "AUTH_BUDGET_EFFECT_ONE_TRANSACTION","UNSUPPORTED","QUERY_ONLY"):
                new ScopedToolDtos.Contract(tool.name(),tool.kind().name(),null,"RUNTIME_REPORTED",allowed,
                    "POLICY_CLAIM_ONLY","UNVERIFIED","UNVERIFIED","UNVERIFIED","UNVERIFIED","UNVERIFIED",
                    "UNVERIFIED","UNVERIFIED","UNVERIFIED","UNVERIFIED");
        }).toList();
        return new ScopedToolDtos.Catalog(policy.revision(),policy.enabled(),Instant.now(),rows);
    }
    /** Historical inspection is a pure store read, also after revocation; it never executes or settles. */
    @Transactional(readOnly=true)
    public ScopedToolDtos.Inspection inspectOperation(String instance,String root,String operation) {
        if(runtimes.findByInstanceKey(instance).isEmpty())throw new BusinessException("运行实例未纳管");
        uuid(root);uuid(operation);
        var rows=operations(root,operation,true);
        if(rows.isEmpty())throw new BusinessException("操作意图不存在，不能推定未提交");
        var o=rows.getFirst();var rootGrant=find(root);
        if(!instance.equals(o.holderKey())&&!instance.equals(rootGrant.v.holderKey()))
            throw new UnauthorizedException("操作不属于此实例的授权链");
        var grant=find(o.grantId());
        return new ScopedToolDtos.Inspection(grant.v.contractVersion(),"tool-root:"+root,Instant.now(),o);
    }
    @Transactional(readOnly=true)
    public ScopedToolDtos.Dashboard dashboard(String instance){
        if(runtimes.findByInstanceKey(instance).isEmpty())throw new BusinessException("运行实例未纳管");
        var grants=db.query("select * from scoped_tool_grants where holder_key=? or root_id in(select grant_id from scoped_tool_grants where holder_key=? and parent_id is null) order by created_at desc limit 30",(r,n)->row(r).v,instance,instance);
        var ops=db.query("select * from scoped_tool_operations where holder_key=? or root_id in(select grant_id from scoped_tool_grants where holder_key=? and parent_id is null) order by created_at desc limit 30",(r,n)->operation(r,false),instance,instance);
        var counters=db.query("select * from scoped_tool_counters where root_id in(select root_id from scoped_tool_grants where holder_key=?) order by resource_id limit 32",(r,n)->new ScopedToolDtos.Counter(r.getString("resource_id"),r.getString("root_id"),r.getLong("counter_value"),r.getLong("effect_count")),instance);
        return new ScopedToolDtos.Dashboard(grants,ops,counters);
    }
    private void event(String action,String resource,String actor){var e=new AuditLog();e.setActor(actor);e.setAction(action);e.setResourceType("TOOL_GRANT");e.setResourceId(resource);e.setResult("SUCCESS");e.setTraceId(TraceContext.traceId());e.setCreatedAt(Instant.now());audit.save(e);}
    private static Instant instant(ResultSet r,String key)throws SQLException{Timestamp t=r.getTimestamp(key);return t==null?null:t.toInstant();}
    private <T>List<T> read(String value,Class<T[]> type){try{return Arrays.asList(json.readValue(value,type));}catch(Exception e){throw new IllegalStateException("授权数据不可用");}}
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("无法保存元数据");}}
    private static String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(Exception e){throw new BusinessException("标识须为规范UUID");}}
}
