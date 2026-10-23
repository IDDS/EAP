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
    @Value("${eap.team-evolution.holdout-enabled:false}") boolean teamHoldoutEnabled;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AgentService agents;
    private final TransactionTemplate committed;
    private final TransactionTemplate joined;
    @Value("${eap.budget.enabled:false}") boolean enabled;
    @Value("${eap.workflow.execution.timeout-seconds:900}") int workflowTimeoutSeconds = 900;
    @Value("${eap.team-evolution.slot-execution-enabled:false}") boolean teamExecutionEnabled;
    @Value("${eap.team-evolution.model-proposals-enabled:false}") boolean teamProposalsEnabled;
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
        if("TASK".equals(r.scopeType)&&(r.scopeKey.matches("optimization:[1-9][0-9]*")||r.scopeKey.startsWith("tool-root:")||r.scopeKey.startsWith("team-evolution:")))throw new ConflictException("任务总预算已冻结，不能修改或清零");
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
    /** Read-only capacity check, not an allocation or a reservation of shared capacity. */
    public void requireTeamCapacity(List<Long> owners,long calls,long outputTokens) {
        committed.executeWithoutResult(status->{lock();teamCapacity(owners,calls,outputTokens);});
    }
    private void teamCapacity(List<Long> owners,long calls,long outputTokens) {
        if(!enabled||owners.size()!=2||new HashSet<>(owners).size()!=2||owners.stream().anyMatch(id->id==null||id<1)
                ||calls<1||calls>128||outputTokens<1||outputTokens>32768)throw new BusinessException("TEAM_BUDGET_DISABLED_OR_INVALID");
        // Current read: the enclosing contract transaction may already have a MySQL snapshot.
        var rows=db.query("select * from budget_accounts where (scope_type='PLATFORM' and scope_key='default' or scope_type='AGENT' and scope_key in (?,?)) and unit in ('MODEL_CALLS','OUTPUT_TOKENS') for update",(rs,n)->account(rs),owners.get(0).toString(),owners.get(1).toString());
        // Conservative per-agent bound: either role cannot exceed the whole plan cap.
        for(var a:rows){
            long required="MODEL_CALLS".equals(a.unit)?calls:outputTokens;
            var total=a.usedAmount.add(a.reservedAmount).add(BigDecimal.valueOf(required)).add(BigDecimal.valueOf(a.marginAmount));
            if(a.violated||a.limitAmount!=null&&total.compareTo(BigDecimal.valueOf(a.limitAmount))>0)throw new BusinessException("TEAM_SHARED_BUDGET_INSUFFICIENT");
        }
    }
    /** Only a saved, explicitly approved team plan may create immutable root accounts. */
    public void freezeTeamBudget(long id,List<Long> owners,long calls,long outputTokens,String actor) {
        joined.executeWithoutResult(status->{
            lock();teamCapacity(owners,calls,outputTokens);
            var plans=db.queryForList("select max_calls,max_output_tokens from team_evolution_budget_plans where contract_id=?",id);
            if(plans.size()!=1||((Number)plans.getFirst().get("max_calls")).longValue()!=calls
                    ||((Number)plans.getFirst().get("max_output_tokens")).longValue()!=outputTokens)throw new BusinessException("TEAM_APPROVED_PLAN_REQUIRED");
            var protocol=tree(db.queryForObject("select protocol_json from team_evolution_contracts where id=?",String.class,id));
            var expected=new TreeSet<Long>();protocol.path("roles").forEach(r->expected.add(r.path("agentId").asLong()));
            if(!expected.equals(new TreeSet<>(owners)))throw new BusinessException("TEAM_BUDGET_OWNER_MISMATCH");
            for(String unit:List.of("MODEL_CALLS","OUTPUT_TOKENS","TOOL_CLAIMS")) {
                var a=ensure(new Scope("TASK","team-evolution:"+id),unit);
                if(a.limitAmount!=null||a.revision!=0||a.usedAmount.signum()!=0||a.reservedAmount.signum()!=0)throw new ConflictException("TEAM_BUDGET_ALREADY_FROZEN");
                long cap=switch(unit){case "MODEL_CALLS"->calls;case "OUTPUT_TOKENS"->outputTokens;default->0;};
                db.update("update budget_accounts set limit_amount=?,revision=1,updated_by=?,updated_at=CURRENT_TIMESTAMP where id=?",cap,actor,a.id);
                db.update("insert into budget_limit_changes(account_id,revision,limit_amount,margin_amount,changed_by,created_at) values(?,1,?,0,?,CURRENT_TIMESTAMP)",a.id,cap,actor);
            }
        });
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
        if(task!=null&&(task.startsWith("optimization:")||task.startsWith("team-evolution:")))throw bad("invalid_budget_task","根预算只能由服务端持久意图关联");
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
        boolean proposal=trace!=null&&!db.queryForList("select trace_id from team_evolution_proposals where trace_id=?",trace).isEmpty();
        if(proposal) {
            if(!enabled||!teamProposalsEnabled)throw bad("team_proposal_rejected","团队提案要求可信预算准入");
            return committed.execute(status->{lock();String scope=admitTeamProposal(request,agentId,endpoint,taskId,trace);
                return reserve("team-proposal:"+trace,com.eap.platform.optimization.TeamEvolutionDigest.of(json,request),agentId,scope,null,trace,"MODEL",request.path("max_tokens").asLong(),null);});
        }
        // Recognize original team traces even after stop/expiry or when budgets are disabled.
        boolean team=trace!=null&&!db.queryForList("select trace_id from team_evolution_slots where trace_id=?",trace).isEmpty();
        if(team) {
            if(!enabled||!teamExecutionEnabled)throw bad("team_execution_rejected","团队执行要求可信预算准入");
            return committed.execute(status->{lock();String scope=admitTeam(request,agentId,endpoint,taskId,trace);
                return reserve("team-trace:"+trace,hash(jsonString(request)),agentId,scope,null,trace,"MODEL",request.path("max_tokens").asLong(),null);});
        }
        if(!enabled) {
            String task=header("X-EAP-Task-Key");
            return committed.execute(status->{requireWorkflowDispatch(task,agentId);return null;});
        }
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
            // Same lock order as workflow tools: workflow first, budget second.
            requireWorkflowDispatch(at.taskKey,agentId);
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
    private void requireWorkflowDispatch(String task,Long agent) {
        if(task==null)return;
        int split=task.lastIndexOf(':');if(split<1)return;
        var rows=db.query("select id,status,started_at,steps_json,recovery_version,owner_id,owner_epoch from workflow_executions where run_key=? for update",(r,n)->{
            // Hibernate writes Instant with UTC semantics. The JDBC session may be +08;
            // interpreting this column in that session would expire a fresh owner early.
            var started=r.getTimestamp(3,Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            return new WorkflowDispatch(r.getLong(1),r.getString(2),started==null?null:started.toInstant(),
                    r.getString(4),r.getInt(5),r.getString(6),r.getLong(7));
        },task.substring(0,split));
        if(rows.isEmpty())return; // Non-workflow tasks retain their own admission contracts.
        var run=rows.getFirst();
        if(!"RUNNING".equals(run.status()) || run.startedAt()==null
                || !Instant.now().isBefore(run.startedAt().plusSeconds(Math.max(1,workflowTimeoutSeconds))))
            throw bad("workflow_not_running","工作流执行责任已结束或到期，未准入模型调用");
        var steps=tree(run.steps());
        if(!steps.isArray()||steps.isEmpty())throw bad("workflow_not_running","工作流没有正在派发的步骤");
        var step=steps.get(steps.size()-1);
        if(!task.equals(step.path("taskKey").asText())||!"DISPATCHING".equals(step.path("status").asText())
                ||agent==null||agent.longValue()!=step.path("agentId").asLong(-1))
            throw bad("workflow_not_running","工作流步骤已改变，未准入模型调用");
        var access=com.eap.platform.registry.RuntimeModelAccess.current();
        if(access!=null&&!access.instanceKey().equals(step.path("runtimeInstanceKey").asText()))
            throw bad("workflow_not_running","模型调用实例与原派发不匹配");
        if(run.recoveryVersion()==1) {
            if(run.owner()==null || db.queryForObject("select count(*) from workflow_phase_observations where execution_id=? and owner_epoch=? and owner_id=? and node_sequence=? and phase_kind='TASK_WAIT' and state='RUNNING'",Long.class,
                    run.id(),run.epoch(),run.owner(),step.path("sequence").asInt(-1))!=1)
                throw bad("workflow_not_running","工作流原派发责任已失效，未准入模型调用");
        }
    }
    private record WorkflowDispatch(long id,String status,Instant startedAt,String steps,int recoveryVersion,String owner,long epoch) {}
    private String admitTeamProposal(JsonNode request,Long agent,String endpoint,Long taskId,String trace) {
        var permit=com.eap.platform.optimization.TeamProposalAccess.current();
        if(permit==null||!trace.equals(permit.traceId())||agent==null||endpoint!=null||taskId!=null
                ||header("X-EAP-Task-Key")!=null||header("X-EAP-Invocation-Key")!=null
                ||com.eap.platform.registry.RuntimeModelAccess.current()!=null)throw bad("team_proposal_rejected","团队提案只接受平台原意图");
        long root=permit.contractId();
        var rows=db.queryForList("select * from team_evolution_proposals where contract_id=? and trace_id=? for update",root,trace);
        if(rows.size()!=1)throw bad("team_proposal_rejected","团队提案原意图未确认");var intent=rows.getFirst();
        var p=db.queryForMap("select * from team_evolution_budget_plans where contract_id=?",root);
        if(!"DISPATCHED".equals(intent.get("state"))||!Objects.equals(agent,((Number)intent.get("agent_id")).longValue())
                ||!"MODEL_SINGLE_ROLE".equals(p.get("proposal_mode"))||!((java.sql.Timestamp)p.get("approval_expires_at")).toInstant().isAfter(Instant.now())
                ||!com.eap.platform.optimization.TeamEvolutionDigest.of(json,request).equals(intent.get("model_request_digest"))
                ||!request.path("max_tokens").isIntegralNumber()||request.path("max_tokens").asInt()!=((Number)p.get("max_proposal_tokens")).intValue())throw bad("team_proposal_rejected","团队提案批准、责任或载荷已变化");
        if(!db.queryForList("select contract_id from team_evolution_closures where contract_id=? for update",root).isEmpty()
                ||!db.queryForList("select contract_id from team_evolution_frontiers where contract_id=? for update",root).isEmpty())throw bad("team_proposal_rejected","团队搜索已关闭");
        if(db.queryForObject("select count(*) from team_evolution_proposals where contract_id=?",Integer.class,root)>((Number)p.get("max_proposal_calls")).intValue())throw bad("team_proposal_rejected","团队提案次数未确认");
        String protocol=db.queryForObject("select protocol_json from team_evolution_contracts where id=?",String.class,root);
        for(var role:tree(protocol).path("roles")) {
            String generation=null;for(var binding:tree((String)p.get("live_bindings_json")))if(binding.path("role").equals(role.path("role")))generation=binding.path("identityGeneration").asText();
            var d=new HashMap<String,Object>();d.put("contract_id",root);d.put("protocol_json",protocol);d.put("max_calls",p.get("max_calls"));d.put("max_output_tokens",p.get("max_output_tokens"));
            d.put("runtime_id",role.path("runtimeId").asLong());d.put("instance_key",role.path("instanceKey").asText());d.put("endpoint",role.path("endpoint").asText());
            d.put("actor_uid",role.path("actorUid").asText());d.put("actor_template",role.path("actorTemplate").asText());
            requireTeamBinding(d,p,role.path("agentId").asLong(),generation);
        }
        return "team-evolution:"+root;
    }
    /** Shared budget lock; mutable authority uses current reads, contract/plan are immutable. */
    private String admitTeam(JsonNode request,Long agent,String endpoint,Long taskId,String trace) {
        var access=com.eap.platform.registry.RuntimeModelAccess.current();
        if(access==null||taskId!=null||header("X-EAP-Task-Key")!=null||header("X-EAP-Invocation-Key")!=null
                ||!Objects.equals(agent,access.agentId())||!Objects.equals(endpoint,access.endpointKey()))throw bad("team_execution_rejected","团队调用只接受原实例机器身份");
        var rows=db.queryForList("select evaluation_id from team_evolution_dispatches where trace_id=?",trace);
        if(rows.size()!=1)throw bad("team_execution_rejected","团队原槽位尚未派发");
        // Same lock order as the receipt writer: execution before dispatch; roots are immutable.
        var x=db.queryForMap("select * from team_evolution_executions where evaluation_id=? for update",rows.getFirst().get("evaluation_id"));
        var d=db.queryForMap("select * from team_evolution_dispatches where trace_id=? for update",trace);
        long contract=db.queryForObject("select contract_id from team_evolution_evaluations where id=?",Long.class,d.get("evaluation_id"));
        if(!db.queryForList("select contract_id from team_evolution_closures where contract_id=? for update",contract).isEmpty())throw bad("team_execution_rejected","团队根已关闭");
        if("HOLDOUT".equals(db.queryForObject("select split_name from team_evolution_evaluations where id=?",String.class,d.get("evaluation_id")))&&!teamHoldoutEnabled)throw bad("team_execution_rejected","团队留出执行关闭");
        var p=db.queryForMap("select * from team_evolution_budget_plans where contract_id=?",contract);
        d.put("execution_state",x.get("state"));d.put("deadline",x.get("deadline"));d.put("contract_id",contract);
        d.put("approval_expires_at",p.get("approval_expires_at"));d.put("max_calls",p.get("max_calls"));d.put("max_output_tokens",p.get("max_output_tokens"));
        d.put("protocol_json",db.queryForObject("select protocol_json from team_evolution_contracts where id=?",String.class,contract));
        if(!"DISPATCHED".equals(d.get("state"))||!"RUNNING".equals(d.get("execution_state"))
                ||!((java.sql.Timestamp)d.get("deadline")).toInstant().isAfter(Instant.now())||!((java.sql.Timestamp)d.get("approval_expires_at")).toInstant().isAfter(Instant.now())
                ||!Objects.equals(agent,((Number)d.get("agent_id")).longValue())||!access.instanceKey().equals(d.get("instance_key"))
                ||!endpoint.equals("runtime-"+d.get("runtime_id"))||!com.eap.platform.optimization.TeamEvolutionDigest.of(json,request).equals(d.get("model_request_digest")))throw bad("team_execution_rejected","团队责任、期限或模型载荷不匹配");
        return requireTeamBinding(d,p,agent,access.generation());
    }
    private String requireTeamBinding(Map<String,Object> d,Map<String,Object> p,Long agent,String generation) {
        if(generation==null||generation.isBlank())throw bad("team_execution_rejected","原计划机器身份epoch未确认");
        var bindings=db.query("select r.agent_id,r.endpoint,r.platform_actor_uid,r.platform_actor_template,r.status,r.protocol,r.imported_actor_uid,c.generation,c.expires_at,c.verified_at,c.delivery_operation,c.automatic_delivery,c.identity_expires_at from agent_runtime_instances r join runtime_connections c on c.instance_key=r.instance_key where r.id=? and r.instance_key=? for update",(rs,index)->{
            var binding=new org.springframework.jdbc.core.ColumnMapRowMapper().mapRow(rs,index);
            // These three fields are JPA Instant values. Match Hibernate's UTC reads;
            // ordinary JDBC reads in a +08 session shift an acknowledged lease by 8h.
            var utc=Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            for(String field:List.of("expires_at","verified_at","identity_expires_at"))
                binding.put(field,rs.getTimestamp(field,utc));
            return binding;
        },d.get("runtime_id"),d.get("instance_key"));
        if(bindings.size()!=1)throw bad("team_execution_rejected","原实例身份不可用");var r=bindings.getFirst();
        if(!Objects.equals(agent,((Number)r.get("agent_id")).longValue())||!Objects.equals(d.get("endpoint"),r.get("endpoint"))
                ||!Objects.equals(d.get("actor_uid"),r.get("platform_actor_uid"))||!Objects.equals(d.get("actor_template"),r.get("platform_actor_template"))
                ||!"SUBSTRATE".equals(r.get("protocol"))||r.get("imported_actor_uid")!=null||"OFFLINE".equals(r.get("status"))||r.get("generation")==null||r.get("delivery_operation")!=null
                ||(confirmed(r.get("automatic_delivery"))&&(r.get("identity_expires_at")==null||!((java.sql.Timestamp)r.get("identity_expires_at")).toInstant().isAfter(Instant.now())))||r.get("expires_at")==null||r.get("verified_at")==null||!Objects.equals(generation,r.get("generation"))||!((java.sql.Timestamp)r.get("expires_at")).toInstant().isAfter(Instant.now()))throw bad("team_execution_rejected","原实例身份已变化或撤销");
        JsonNode role=null;for(var candidate:tree((String)d.get("protocol_json")).path("roles"))if(candidate.path("runtimeId").asLong()==((Number)d.get("runtime_id")).longValue())role=candidate;
        if(role==null)throw bad("team_execution_rejected","原角色未确认");
        JsonNode epoch=null;for(var binding:tree((String)p.get("live_bindings_json")))if(binding.path("role").asText().equals(role.path("role").asText()))epoch=binding.path("identityGeneration");
        if(epoch==null||!epoch.isTextual()||!generation.equals(epoch.asText()))throw bad("team_execution_rejected","原计划机器身份epoch已变化");
        var creations=db.queryForList("select * from platform_runtime_creations where instance_key=? for update",d.get("instance_key"));
        var deployments=db.queryForList("select * from substrate_agent_deployments where deployment_key=? for update",role.path("deploymentKey").asText());
        var active=db.queryForList("select lifecycle_status from agents where id=? for update",agent);
        if(creations.size()!=1||confirmed(creations.getFirst().get("revoked"))||!Objects.equals(d.get("actor_uid"),creations.getFirst().get("actor_uid"))
                ||!role.path("deploymentKey").asText().equals(creations.getFirst().get("deployment_key"))||active.size()!=1||!"ACTIVE".equals(active.getFirst().get("lifecycle_status"))||deployments.size()!=1)throw bad("team_execution_rejected","原批准或Agent已变化");
        var dep=deployments.getFirst();
        if(((Number)dep.get("agent_id")).longValue()!=agent||!role.path("actorTemplate").asText().equals(dep.get("atespace")+"/"+dep.get("template_name"))||!"READY".equals(dep.get("status"))||!"RUNNER".equals(dep.get("source_type"))||!role.path("imageRef").asText().equals(dep.get("image_ref"))
                ||role.path("deploymentVersion").asLong()!=((Number)dep.get("version")).longValue()
                ||!role.path("configDigest").asText().equals(com.eap.platform.optimization.TeamEvolutionDigest.of(json,tree((String)dep.get("config_json")))))throw bad("team_execution_rejected","原发布配置已变化");
        String scope="team-evolution:"+d.get("contract_id");
        var accounts=db.queryForList("select * from budget_accounts where scope_type='TASK' and scope_key=? for update",scope);
        if(accounts.size()!=3)throw bad("team_execution_rejected","团队冻结预算缺失");
        for(var a:accounts){long expected=switch((String)a.get("unit")){case "MODEL_CALLS"->((Number)d.get("max_calls")).longValue();case "OUTPUT_TOKENS"->((Number)d.get("max_output_tokens")).longValue();case "TOOL_CLAIMS"->0;default->-1;};
            if(a.get("limit_amount")==null||((Number)a.get("limit_amount")).longValue()!=expected||((Number)a.get("revision")).longValue()!=1||((Number)a.get("margin_amount")).longValue()!=0||confirmed(a.get("violated")))throw bad("team_execution_rejected","团队冻结预算已变化");}
        return scope;
    }
    /** Bind only the actual post-governance Gateway response, never a Runner's self-reported usage. */
    public void recordTeamResponse(Ticket ticket,JsonNode response) {
        if(ticket==null)return;
        committed.executeWithoutResult(status->{lock();var reservation=owned(ticket);
            var rows=db.queryForList("select * from team_evolution_dispatches where trace_id=? for update",reservation.get("trace_id"));
            if(rows.isEmpty())return;var d=rows.getFirst();
            if(!"DISPATCHED".equals(d.get("state"))||d.get("reservation_id")!=null||!"SETTLED".equals(reservation.get("state")))return;
            var choices=response==null?json.nullNode():response.path("choices");
            if(!choices.isArray()||choices.size()!=1||!choices.path(0).path("message").path("content").isTextual()
                    ||choices.path(0).path("message").path("tool_calls").size()!=0||!Set.of("stop","length").contains(choices.path(0).path("finish_reason").asText()))return;
            db.update("update team_evolution_dispatches set reservation_id=?,gateway_output_digest=?,gateway_finish_reason=? where operation_key=? and state='DISPATCHED' and reservation_id is null",ticket.id,
                    com.eap.platform.optimization.TeamEvolutionDigest.of(json,choices.path(0).path("message").path("content")),choices.path(0).path("finish_reason").asText(),d.get("operation_key"));
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
        db.update("insert into budget_reservations(invocation_key,fingerprint,agent_id,task_key,experiment_id,trace_id,operation,output_bound,state,fence,created_at) values(?,?,?,?,?,?,?,?,'DISPATCHED',?,CURRENT_TIMESTAMP(6))",stable,fingerprint,agentId,task,experiment,trace,operation,bound,fence);
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
        db.update("update budget_reservations set state=?,input_tokens=?,output_tokens=?,reason_code=?,settled_at=CURRENT_TIMESTAMP(6) where id=?",state,input,output,violation?"OUTPUT_BOUND_EXCEEDED":uncertain?"RECEIPT_UNKNOWN":null,t.id);
        db.update("insert into budget_settlements(reservation_id,state,input_tokens,output_tokens,created_at) values(?,?,?,?,CURRENT_TIMESTAMP(6))",t.id,state,input,output);
    }
    private boolean confirmed(Object flag) {return Boolean.TRUE.equals(flag)||flag instanceof Number n&&n.intValue()==1;}
    private GatewayException bad(String code,String message) {return new GatewayException(HttpStatus.BAD_REQUEST,code,message);}
    private String jsonString(Object value) {try{return json.writeValueAsString(value);}catch(Exception ex){throw new BusinessException("预算请求摘要不可计算");}}
    private String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException("预算摘要不可计算");}}
}
