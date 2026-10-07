package com.eap.platform.budget;

import com.eap.platform.agent.AgentService;
import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.eap.platform.gateway.GatewayException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** All admission, limit changes and settlements share one DB lock, including multiple application replicas. */
@Service
public class BudgetLedger {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AgentService agents;
    private final TransactionTemplate committed;
    private final TransactionTemplate joined;
    @Value("${eap.budget.enabled:false}") boolean enabled;
    public BudgetLedger(JdbcTemplate db,ObjectMapper json,AgentService agents,PlatformTransactionManager manager) {
        this.db=db;this.json=json;this.agents=agents;
        committed=new TransactionTemplate(manager);committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        joined=new TransactionTemplate(manager);
    }
    public record Account(Long id,String scopeType,String scopeKey,String unit,Long limitAmount,long marginAmount,
                          BigDecimal usedAmount,BigDecimal reservedAmount,long revision,boolean violated,String updatedBy,String updatedAt) {}
    public record Entry(Long id,Long agentId,String taskKey,Long experimentId,String traceId,String operation,long outputBound,
                        String state,Long inputTokens,Long outputTokens,String reasonCode,String createdAt,String settledAt) {}
    public record Dashboard(boolean enabled,List<Account> accounts,List<Entry> reservations) {}
    public record Limit(@NotBlank @Pattern(regexp="PLATFORM|AGENT|TASK|EXPERIMENT") String scopeType,
                        @NotBlank @Size(max=128) String scopeKey,
                        @NotBlank @Pattern(regexp="MODEL_CALLS|OUTPUT_TOKENS|TOOL_CLAIMS") String unit,
                        @Min(0) @Max(1000000000000L) Long limitAmount,
                        @Min(0) @Max(1000000000000L) long marginAmount,@Min(0) long expectedRevision) {}
    public record Ticket(long id,String fence) {}
    private record Scope(String type,String key) {}
    private record Attribution(String taskKey,Long experimentId,String optimizationRoot,String reflectionTrace) {}

    private void lock() { db.queryForObject("select id from budget_admission_lock where id=1 for update",Long.class); }
    public boolean enabled() {return enabled;}
    public Dashboard dashboard() {
        return new Dashboard(enabled,db.query("select * from budget_accounts order by case when scope_type='PLATFORM' then 0 else 1 end,id desc limit 200",(rs,n)->account(rs)),
                db.query("select * from budget_reservations order by id desc limit 100",(rs,n)->new Entry(rs.getLong("id"),rs.getObject("agent_id",Long.class),rs.getString("task_key"),rs.getObject("experiment_id",Long.class),rs.getString("trace_id"),rs.getString("operation"),rs.getLong("output_bound"),rs.getString("state"),rs.getObject("input_tokens",Long.class),rs.getObject("output_tokens",Long.class),rs.getString("reason_code"),rs.getTimestamp("created_at").toInstant().toString(),rs.getTimestamp("settled_at")==null?null:rs.getTimestamp("settled_at").toInstant().toString())));
    }
    private Account account(java.sql.ResultSet rs)throws java.sql.SQLException {
        return new Account(rs.getLong("id"),rs.getString("scope_type"),rs.getString("scope_key"),rs.getString("unit"),rs.getObject("limit_amount",Long.class),rs.getLong("margin_amount"),rs.getBigDecimal("used_amount"),rs.getBigDecimal("reserved_amount"),rs.getLong("revision"),rs.getBoolean("violated"),rs.getString("updated_by"),rs.getTimestamp("updated_at").toInstant().toString());
    }
    private Account ensure(Scope scope,String unit) {
        var rows=db.query("select * from budget_accounts where scope_type=? and scope_key=? and unit=?",(rs,n)->account(rs),scope.type,scope.key,unit);
        if(!rows.isEmpty())return rows.getFirst();
        db.update("insert into budget_accounts(scope_type,scope_key,unit,updated_at) values(?,?,?,CURRENT_TIMESTAMP)",scope.type,scope.key,unit);
        return db.query("select * from budget_accounts where scope_type=? and scope_key=? and unit=?",(rs,n)->account(rs),scope.type,scope.key,unit).getFirst();
    }
    public Account setLimit(Limit r,String actor) {
        if("TASK".equals(r.scopeType)&&(r.scopeKey.matches("optimization:[1-9][0-9]*")||r.scopeKey.startsWith("tool-root:")))throw new ConflictException("任务总预算已冻结，不能修改或清零");
        validateScope(r.scopeType,r.scopeKey);
        return committed.execute(status->{
            lock();var a=ensure(new Scope(r.scopeType,r.scopeKey),r.unit);
            if(a.revision!=r.expectedRevision)throw new ConflictException("预算配置已变化，请刷新后修改");
            db.update("update budget_accounts set limit_amount=?,margin_amount=?,revision=revision+1,updated_by=?,updated_at=CURRENT_TIMESTAMP where id=?",r.limitAmount,r.marginAmount,actor,a.id);
            db.update("insert into budget_limit_changes(account_id,revision,limit_amount,margin_amount,changed_by,created_at) values(?,?,?,?,?,CURRENT_TIMESTAMP)",a.id,a.revision+1,r.limitAmount,r.marginAmount,actor);
            return ensure(new Scope(r.scopeType,r.scopeKey),r.unit);
        });
    }
    private void validateScope(String type,String key) {
        if("PLATFORM".equals(type)) {if(!"default".equals(key))throw new BusinessException("部署共享预算的标识须为default");return;}
        try {
            if("AGENT".equals(type)) {agents.get(canonicalId(key));return;}
            if("EXPERIMENT".equals(type)) {
                Long owner=db.queryForObject("select agent_id from agent_experiments where id=?",Long.class,canonicalId(key));agents.get(owner);return;
            }
            if("TASK".equals(type)) {Long owner=taskOwner(key);if(owner==null)throw new BusinessException("任务不存在或没有已保存的派发意图");agents.get(owner);return;}
        } catch(org.springframework.dao.EmptyResultDataAccessException|NumberFormatException ex) {throw new BusinessException("预算范围不存在");}
        throw new BusinessException("不支持此预算范围");
    }
    private Long canonicalId(String key) {
        long id=Long.parseLong(key);if(id<1||!Long.toString(id).equals(key))throw new BusinessException("预算范围ID须为标准正整数");return id;
    }
    /** Capacity check only, not a reservation: every later call still performs live shared admission. */
    public void requireOptimizationCapacity(long agentId,long calls,long outputTokens) {
        if(!enabled||agentId<1||calls<1||outputTokens<1)throw new BusinessException("优化共享预算预检无效");
        joined.executeWithoutResult(status->{
            lock();
            var rows=db.query("select * from budget_accounts where (scope_type='PLATFORM' and scope_key='default' or scope_type='AGENT' and scope_key=?) and unit in ('MODEL_CALLS','OUTPUT_TOKENS')",(rs,n)->account(rs),Long.toString(agentId));
            for(var a:rows){
                long required="MODEL_CALLS".equals(a.unit)?calls:outputTokens;
                var total=a.usedAmount.add(a.reservedAmount).add(BigDecimal.valueOf(required)).add(BigDecimal.valueOf(a.marginAmount));
                if(a.violated||a.limitAmount!=null&&total.compareTo(BigDecimal.valueOf(a.limitAmount))>0)
                    throw new BusinessException("共享预算剩余额度不足以覆盖完整优化计划");
            }
        });
    }
    public void createOptimizationBudget(long id,long maxCalls,long outputTokens,String actor) {
        if(!enabled||id<1||maxCalls<1||maxCalls>64||outputTokens<1||outputTokens>65536)throw new BusinessException("优化预算未启用或范围无效");
        joined.executeWithoutResult(status->{lock();for(var unit:List.of("MODEL_CALLS","OUTPUT_TOKENS")) {
            var a=ensure(new Scope("TASK","optimization:"+id),unit);
            if(a.limitAmount!=null)throw new ConflictException("运行预算已设置");
            long cap="MODEL_CALLS".equals(unit)?maxCalls:outputTokens;
            db.update("update budget_accounts set limit_amount=?,revision=1,updated_by=?,updated_at=CURRENT_TIMESTAMP where id=?",cap,actor,a.id);
            db.update("insert into budget_limit_changes(account_id,revision,limit_amount,margin_amount,changed_by,created_at) values(?,1,?,0,?,CURRENT_TIMESTAMP)",a.id,cap,actor);
        }});
    }
    private Long taskOwner(String key) {
        if(key.matches("optimization:[1-9][0-9]*")) {
            var owners=db.queryForList("select agent_id from agent_optimizations where id=?",Long.class,Long.parseLong(key.substring(13)));
            return owners.isEmpty()?null:owners.getFirst();
        }

        if(key.startsWith("agent-task:")) {
            try {return db.queryForObject("select agent_id from agent_tasks where id=?",Long.class,canonicalId(key.substring(11)));}
            catch(org.springframework.dao.EmptyResultDataAccessException|NumberFormatException ex){return null;}
        }
        var ax=db.queryForList("select agent_id from ax_tasks where task_key=?",Long.class,key);if(!ax.isEmpty())return ax.getFirst();
        int split=key.lastIndexOf(':');if(split<1)return null;
        var runs=db.queryForList("select steps_json from workflow_executions where run_key=?",String.class,key.substring(0,split));
        for(String run:runs)for(JsonNode step:tree(run))if(key.equals(step.path("taskKey").asText())&&step.path("agentId").canConvertToLong())return step.path("agentId").asLong();
        return null;
    }
    private JsonNode tree(String value) {try{return json.readTree(value);}catch(Exception ex){throw new BusinessException("预算关联记录不可读取");}}
    private String header(String name) {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a?a.getRequest().getHeader(name):null;
    }
    private Attribution attribution(Long agentId,Long taskId,String trace) {
        String task=header("X-EAP-Task-Key");
        if(task!=null&&task.startsWith("optimization:"))throw bad("invalid_budget_task","优化根预算只能由服务端持久意图关联");
        if(task!=null) {if(task.length()>128||agentId==null||!Objects.equals(agentId,taskOwner(task)))throw bad("invalid_budget_task","调用任务不属于该智能体或派发意图不存在");}
        if(taskId!=null) {String key="agent-task:"+taskId;if(agentId==null||!Objects.equals(agentId,taskOwner(key)))throw bad("invalid_budget_task","调用任务不属于该智能体");if(task!=null&&!task.equals(key))throw bad("invalid_budget_task","两个任务标识不一致");task=key;}
        Long experiment=null;String root=null,reflection=null;
        if(trace!=null&&agentId!=null) {
            var ids=db.queryForList("select t.experiment_id,e.optimization_run_id,t.status as trial_status,e.status as experiment_status from agent_experiment_trials t join agent_experiments e on e.id=t.experiment_id where t.trace_id=? and e.agent_id=?",trace,agentId);
            if(!ids.isEmpty()) {
                var trial=ids.getFirst();
                if(!"RUNNING".equals(trial.get("trial_status"))||!"RUNNING".equals(trial.get("experiment_status")))throw new GatewayException(HttpStatus.CONFLICT,"experiment_not_running","实验调用没有正在执行的派发意图");
                experiment=((Number)trial.get("experiment_id")).longValue();
                if(trial.get("optimization_run_id") instanceof Number n)root="optimization:"+n.longValue();
            }
        }
        if(trace!=null&&agentId!=null&&experiment==null) {
            var intents=db.queryForList("select o.run_id,o.status as operation_status,r.status as run_status from agent_optimization_reflections o join agent_optimizations r on r.id=o.run_id where o.trace_id=? and r.agent_id=?",trace,agentId);
            if(!intents.isEmpty()) {
                var intent=intents.getFirst();
                if(!"DISPATCHED".equals(intent.get("operation_status"))||!"SEARCHING".equals(intent.get("run_status")))throw bad("optimization_not_running","优化反思没有正在执行的持久意图");
                root="optimization:"+((Number)intent.get("run_id")).longValue();reflection=trace;
            }
        }
        if(root!=null) {
            String state=db.queryForObject("select status from agent_optimizations where id=?",String.class,Long.parseLong(root.substring(13)));
            if(!Set.of("SEARCHING","HOLDOUT").contains(state))throw bad("optimization_not_running","优化运行已停止，不再准入");
        }
        return new Attribution(task,experiment,root,reflection);
    }
    public Ticket admitModel(JsonNode request,Long agentId,String endpoint,Long taskId,String trace,String fallbackKey) {
        if(!enabled)return null;
        if(request.has("max_tokens")&&request.has("max_completion_tokens"))throw bad("ambiguous_output_bound","请只声明一种输出上限");
        JsonNode bound=request.has("max_tokens")?request.get("max_tokens"):request.path("max_completion_tokens");
        if(!bound.isIntegralNumber()||!bound.canConvertToLong()||bound.asLong()<1||bound.asLong()>8192||
                (request.has("n")&&(!request.path("n").isIntegralNumber()||!request.path("n").canConvertToLong()||request.path("n").asLong()!=1)))throw bad("output_bound_required","预算准入要求单个输出和1–8192的明确输出上限");
        var at=attribution(agentId,taskId,trace);
        String supplied=header("X-EAP-Invocation-Key");
        if(supplied!=null&&!supplied.matches("[A-Za-z0-9_.:-]{8,128}"))throw bad("invalid_invocation_key","调用标识格式无效");
        String identity=agentId==null?"endpoint:"+endpoint:"agent:"+agentId;
        String key=at.reflectionTrace!=null?"optimization-reflection:"+at.reflectionTrace:at.experimentId!=null?"experiment-trace:"+trace:supplied!=null?supplied:at.taskKey!=null?"task:"+at.taskKey:fallbackKey;
        String fingerprint=hash(jsonString(List.of(request,identity,at.taskKey==null?"":at.taskKey,at.experimentId==null?0:at.experimentId)));
        return committed.execute(status->{
            lock();
            if(at.optimizationRoot!=null) {
                var root=db.queryForMap("select status,public_samples_confirmed,reflection_use_confirmed,case when claim_deadline is not null and claim_deadline<=CURRENT_TIMESTAMP then true else false end as claim_expired from agent_optimizations where id=? for update",Long.parseLong(at.optimizationRoot.substring(13)));
                String state=(String)root.get("status");
                if("SEARCHING".equals(state)&&confirmed(root.get("claim_expired")))throw bad("optimization_not_running","优化执行身份已过期，不再准入");
                if(!confirmed(root.get("public_samples_confirmed"))||at.reflectionTrace!=null&&!confirmed(root.get("reflection_use_confirmed")))throw bad("optimization_purpose_unapproved","样本或反思用途未获准");
                if(at.reflectionTrace!=null?!"SEARCHING".equals(state):!Set.of("SEARCHING","HOLDOUT").contains(state))throw bad("optimization_not_running","优化运行已停止，不再准入");
            }
            return reserve(identity+":"+key,fingerprint,agentId,at.taskKey,at.experimentId,trace,"MODEL",bound.asLong(),at.optimizationRoot);
        });
    }
    private Ticket reserve(String key,String fingerprint,Long agentId,String task,Long experiment,String trace,String operation,long bound,String optimizationRoot) {
        return reserve(key,fingerprint,agentId,task,experiment,trace,operation,bound,optimizationRoot,List.of());
    }
    private Ticket reserve(String key,String fingerprint,Long agentId,String task,Long experiment,String trace,String operation,long bound,String optimizationRoot,List<Long> extraAgents) {
        String stable=hash(key);
        var old=db.queryForList("select id,fingerprint,state from budget_reservations where invocation_key=?",stable);
        if(!old.isEmpty()) {
            if(!fingerprint.equals(old.getFirst().get("fingerprint")))throw new GatewayException(HttpStatus.CONFLICT,"budget_invocation_conflict","原调用标识对应不同载荷");
            throw new GatewayException(HttpStatus.CONFLICT,"budget_invocation_recorded","调用意图已保存；请核对原回执，不重新执行");
        }
        List<Scope> scopes=new ArrayList<>();scopes.add(new Scope("PLATFORM","default"));
        var agentScopes=new java.util.TreeSet<Long>(extraAgents);if(agentId!=null)agentScopes.add(agentId);
        for(Long owner:agentScopes)scopes.add(new Scope("AGENT",owner.toString()));
        if(task!=null)scopes.add(new Scope("TASK",task));if(optimizationRoot!=null&&!optimizationRoot.equals(task))scopes.add(new Scope("TASK",optimizationRoot));if(experiment!=null)scopes.add(new Scope("EXPERIMENT",experiment.toString()));
        Map<Long,Long> items=new LinkedHashMap<>();
        for(var scope:scopes)for(var unit:"MODEL".equals(operation)?List.of("MODEL_CALLS","OUTPUT_TOKENS"):List.of("TOOL_CLAIMS")) {
            var a=ensure(scope,unit);long amount="OUTPUT_TOKENS".equals(unit)?bound:1;
            var commitment=a.usedAmount.add(a.reservedAmount).add(BigDecimal.valueOf(a.marginAmount)).add(BigDecimal.valueOf(amount));
            if(a.violated||a.limitAmount!=null&&commitment.compareTo(BigDecimal.valueOf(a.limitAmount))>0)
                throw new GatewayException(HttpStatus.TOO_MANY_REQUESTS,"budget_exhausted","预算不足或存在上界违例，请核对已用和在途预留");
            items.put(a.id,amount);
        }
        String fence=UUID.randomUUID().toString();
        db.update("insert into budget_reservations(invocation_key,fingerprint,agent_id,task_key,experiment_id,trace_id,operation,output_bound,state,fence,created_at) values(?,?,?,?,?,?,?,?,'DISPATCHED',?,CURRENT_TIMESTAMP)",stable,fingerprint,agentId,task,experiment,trace,operation,bound,fence);
        long id=db.queryForObject("select id from budget_reservations where invocation_key=?",Long.class,stable);
        for(var item:items.entrySet()) {
            db.update("insert into budget_reservation_items(reservation_id,account_id,reserved_amount) values(?,?,?)",id,item.getKey(),item.getValue());
            db.update("update budget_accounts set reserved_amount=reserved_amount+?,updated_at=CURRENT_TIMESTAMP where id=?",item.getValue(),item.getKey());
        }
        return new Ticket(id,fence);
    }
    /** Joins the guarded-tool transaction so a rejected claim rolls back both policy and ledger. */
    public void claimTool(Long agentId,String instance,String callId,String fingerprint,String trace) {
        if(!enabled)return;
        joined.executeWithoutResult(status->{lock();var t=reserve("tool:"+instance+":"+callId,fingerprint,agentId,null,null,trace,"TOOL",0,null);settle(t,null,null,false);});
    }
    public void createToolRootBudget(String root,long cap,String actor) {
        if(!enabled)throw new BusinessException("受控工具要求启用共享预算");
        joined.executeWithoutResult(status->{lock();var a=ensure(new Scope("TASK","tool-root:"+root),"TOOL_CLAIMS");
            if(a.limitAmount!=null)throw new ConflictException("根授权预算已冻结");
            db.update("update budget_accounts set limit_amount=?,revision=1,updated_by=?,updated_at=CURRENT_TIMESTAMP where id=?",cap,actor,a.id);
            db.update("insert into budget_limit_changes(account_id,revision,limit_amount,margin_amount,changed_by,created_at) values(?,1,?,0,?,CURRENT_TIMESTAMP)",a.id,cap,actor);
        });
    }
    /** One commitment charges the deployment, all distinct authority agents and the immutable root task. */
    public void claimScopedTool(String root,List<Long> authorityAgents,Long executor,String operation,String fingerprint,String trace) {
        if(!enabled)throw new BusinessException("受控工具要求启用共享预算");
        joined.executeWithoutResult(status->{lock();var t=reserve("scoped-tool:"+root+":"+operation,fingerprint,executor,"tool-root:"+root,null,trace,"TOOL",0,null,authorityAgents);settle(t,null,null,false);});
    }
    public void complete(Ticket ticket,JsonNode response) {
        if(ticket==null)return;
        JsonNode usage=response==null?null:response.get("usage");
        Long input=count(usage,"prompt_tokens","input_tokens"),output=count(usage,"completion_tokens","output_tokens");
        if(input==null||output==null) {unconfirmed(ticket,"USAGE_UNKNOWN");return;}
        committed.executeWithoutResult(status->{lock();settle(ticket,input,output,false);});
    }
    public void completeStream(Ticket ticket,String stream) {
        if(ticket==null)return;
        JsonNode last=null;boolean done=false,invalid=false;
        for(String line:stream.split("\n"))if(line.startsWith("data:")) {
            String value=line.substring(5).trim();if("[DONE]".equals(value)){done=true;continue;}
            try{var chunk=json.readTree(value);if(chunk.has("usage"))last=chunk;}catch(Exception ex){invalid=true;}
        }
        if(!done||invalid){unconfirmed(ticket,"STREAM_INCOMPLETE");return;}complete(ticket,last);
    }
    private Long count(JsonNode usage,String first,String second) {
        if(usage==null)return null;JsonNode value=usage.has(first)?usage.get(first):usage.get(second);
        return value!=null&&value.isIntegralNumber()&&value.canConvertToLong()&&value.asLong()>=0?value.asLong():null;
    }
    public void unconfirmed(Ticket ticket,String reason) {
        if(ticket==null)return;
        committed.executeWithoutResult(status->{lock();var row=owned(ticket);
            if(!"DISPATCHED".equals(row.get("state")))return;
            settle(ticket,null,null,true);
            db.update("update budget_reservations set reason_code=? where id=?",reason,ticket.id);
        });
    }
    private Map<String,Object> owned(Ticket t) {
        var rows=db.queryForList("select * from budget_reservations where id=?",t.id);
        if(rows.isEmpty()||!t.fence.equals(rows.getFirst().get("fence")))throw new ConflictException("预算执行持有者无效");return rows.getFirst();
    }
    private void settle(Ticket t,Long input,Long output,boolean uncertain) {
        var row=owned(t);String existing=(String)row.get("state");
        if(!"DISPATCHED".equals(existing)) {
            if(("SETTLED".equals(existing)||"VIOLATION".equals(existing))&&Objects.equals(input,row.get("input_tokens"))&&Objects.equals(output,row.get("output_tokens"))&&!uncertain)return;
            throw new ConflictException("预算回执已保存或未知，不允许迟到覆盖");
        }
        boolean model="MODEL".equals(row.get("operation"));boolean violation=output!=null&&output>((Number)row.get("output_bound")).longValue();
        for(var item:db.queryForList("select i.account_id,i.reserved_amount,a.unit from budget_reservation_items i join budget_accounts a on a.id=i.account_id where i.reservation_id=? order by i.account_id",t.id)) {
            boolean tokens="OUTPUT_TOKENS".equals(item.get("unit"));if(tokens&&uncertain)continue;
            long amount=((Number)item.get("reserved_amount")).longValue();long actual=tokens?Objects.requireNonNull(output):1;
            db.update("update budget_accounts set reserved_amount=reserved_amount-?,used_amount=used_amount+?,violated=case when ? then true else violated end,updated_at=CURRENT_TIMESTAMP where id=?",amount,actual,violation&&tokens,item.get("account_id"));
        }
        String state=uncertain?"UNKNOWN":violation?"VIOLATION":"SETTLED";
        db.update("update budget_reservations set state=?,input_tokens=?,output_tokens=?,reason_code=?,settled_at=CURRENT_TIMESTAMP where id=?",state,input,output,violation?"OUTPUT_BOUND_EXCEEDED":uncertain?"RECEIPT_UNKNOWN":null,t.id);
        db.update("insert into budget_settlements(reservation_id,state,input_tokens,output_tokens,created_at) values(?,?,?,?,CURRENT_TIMESTAMP)",t.id,state,input,output);
    }
    private boolean confirmed(Object flag) {return Boolean.TRUE.equals(flag)||flag instanceof Number n&&n.intValue()==1;}
    private GatewayException bad(String code,String message) {return new GatewayException(HttpStatus.BAD_REQUEST,code,message);}
    private String jsonString(Object value) {try{return json.writeValueAsString(value);}catch(Exception ex){throw new BusinessException("预算请求摘要不可计算");}}
    private String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException("预算摘要不可计算");}}
}
