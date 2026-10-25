package com.eap.platform.optimization;

import com.eap.platform.agent.AgentService;
import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A decision to abandon an original intent is separate from its still UNKNOWN outcome. */
@Service
public class OptimizationUnknownReviewService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AgentService agents;
    public OptimizationUnknownReviewService(JdbcTemplate db,ObjectMapper json,AgentService agents) {
        this.db=db;this.json=json;this.agents=agents;
    }
    public record Decide(@NotBlank @Pattern(regexp="[a-f0-9]{64}") String evidenceDigest,
                         @NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{8,64}") String requestKey,
                         @AssertTrue boolean acceptUnknownAndNoReplay) {}
    public record Decision(String decision,String reasonCode,String evidenceDigest,String principalDigest,String createdAt) {}
    public record Inspection(long runId,String status,String reasonCode,String instanceKey,String actorUid,
                             List<Long> experimentIds,int unknownTrials,int notRunTrials,int modelCalls,
                             int settledCalls,int usageKnownCalls,Long inputTokens,Long outputTokens,
                             List<String> blockers,String evidenceDigest,Decision decision,boolean decisionCurrent) {}
    private record Snapshot(Inspection view,Map<String,Object> evidence) {}
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("审计证据无法编码");}}
    private String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("审计摘要无法计算");}}
    private long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private boolean yes(Object value) {return Boolean.TRUE.equals(value)||value instanceof Number n&&n.intValue()!=0;}
    private Decision decision(long id) {
        var rows=db.queryForList("select decision,reason_code,evidence_digest,principal_digest,created_at from agent_optimization_unknown_reviews where run_id=?",id);
        if(rows.isEmpty())return null;var row=rows.getFirst();
        return new Decision((String)row.get("decision"),(String)row.get("reason_code"),(String)row.get("evidence_digest"),(String)row.get("principal_digest"),row.get("created_at").toString());
    }
    private Snapshot snapshot(long id,boolean locking) {
        var roots=db.queryForList("select id,agent_id,instance_key,status,reason_code,fingerprint,dataset_digest,claim_hash,updated_at,profile_json,dataset_json,manual_prompt from agent_optimizations where id=?",id);
        if(roots.isEmpty())throw new BusinessException("优化任务不存在");var root=roots.getFirst();
        List<String> blockers=new ArrayList<>();String instance=(String)root.get("instance_key");
        if(!"UNKNOWN".equals(root.get("status")))blockers.add("ROOT_NOT_UNKNOWN");
        if(root.get("claim_hash")!=null)blockers.add("SEARCH_CLAIM_PRESENT");
        // Hash private profile content in memory; it never enters the audit payload or metadata response.
        String uid=null,template=null;
        try {var p=json.readTree((String)root.remove("profile_json"));uid=p.path("_actorUid").asText(null);template=p.path("_template").asText(null);root.put("profileDigest",hash(encode(p)));}
        catch(Exception e){blockers.add("FROZEN_IDENTITY_MISSING");}
        root.remove("claim_hash");
        root.put("datasetContentDigest",hash((String)root.remove("dataset_json")));
        root.put("manualPromptDigest",hash((String)root.remove("manual_prompt")));
        var protocols=db.queryForList("select run_id,version from agent_optimization_protocols where run_id=?",id);
        if(protocols.isEmpty())blockers.add("REGISTERED_PROTOCOL_REQUIRED");
        var experiments=db.queryForList("select id,agent_id,instance_key,actor_uid,template,status,profile_digest,request_fingerprint,updated_at from agent_experiments where optimization_run_id=? order by id",id);
        List<Long> ids=experiments.stream().map(r->number(r,"id")).toList();
        if(experiments.stream().anyMatch(r->Set.of("QUEUED","RUNNING").contains(r.get("status"))))blockers.add("CHILD_EXPERIMENT_ACTIVE");
        for(var e:experiments)if(number(e,"agent_id")!=number(root,"agent_id")||!instance.equals(e.get("instance_key"))||!Objects.equals(uid,e.get("actor_uid"))||!Objects.equals(template,e.get("template"))) {blockers.add("CHILD_IDENTITY_MISMATCH");break;}
        var trials=db.queryForList("select t.id,t.experiment_id,t.status,t.task_key,t.trace_id,t.reason_code,t.finish_reason,t.input_tokens,t.output_tokens,t.usage_source,t.score,t.started_at,t.finished_at,t.output_text from agent_experiment_trials t join agent_experiments e on e.id=t.experiment_id where e.optimization_run_id=? order by t.id",id);
        trials.forEach(t->{Object output=t.remove("output_text");t.put("outputDigest",output==null?null:hash((String)output));});
        if(trials.stream().anyMatch(r->Set.of("QUEUED","RUNNING").contains(r.get("status"))))blockers.add("TRIAL_ACTIVE");
        var reflections=db.queryForList("select id,ordinal,operation_key,trace_id,status,reason_code from agent_optimization_reflections where run_id=? order by id",id);
        if(reflections.stream().anyMatch(r->"DISPATCHED".equals(r.get("status"))))blockers.add("REFLECTION_ACTIVE");
        String suffix=locking?" for update":"";
        var runtimes=db.queryForList("select agent_id,instance_key,platform_actor_uid,platform_actor_template from agent_runtime_instances where instance_key=?"+suffix,instance);
        if(uid==null||template==null||runtimes.size()!=1||number(runtimes.getFirst(),"agent_id")!=number(root,"agent_id")||!uid.equals(runtimes.getFirst().get("platform_actor_uid"))||!template.equals(runtimes.getFirst().get("platform_actor_template")))blockers.add("ORIGINAL_BOUND_IDENTITY_REQUIRED");
        // Select booleans, never credential material. Revocation prevents new machine dispatch.
        var connections=db.queryForList("select instance_key,revision,generation is null as revoked,delivery_operation is null as no_delivery from runtime_connections where instance_key=?"+suffix,instance);
        if(connections.size()!=1||!yes(connections.getFirst().get("revoked"))||!yes(connections.getFirst().get("no_delivery")))blockers.add("ORIGINAL_IDENTITY_NOT_REVOKED");
        if(locking)db.queryForObject("select id from budget_admission_lock where id=1 for update",Long.class);
        String selection="r.task_key=? or r.experiment_id in (select id from agent_experiments where optimization_run_id=?) or r.trace_id in (select t.trace_id from agent_experiment_trials t join agent_experiments e on e.id=t.experiment_id where e.optimization_run_id=?) or r.trace_id in (select trace_id from agent_optimization_reflections where run_id=?) or r.id in (select i.reservation_id from budget_reservation_items i join budget_accounts a on a.id=i.account_id where a.scope_type='TASK' and a.scope_key=?)";
        Object[] parameters={"optimization:"+id,id,id,id,"optimization:"+id};
        var reservations=db.queryForList("select r.id,r.agent_id,r.task_key,r.experiment_id,r.trace_id,r.fingerprint,r.output_bound,r.operation,r.state,r.input_tokens,r.output_tokens,r.reason_code,r.created_at,r.settled_at from budget_reservations r where "+selection+" order by r.id",parameters);
        var settlements=db.queryForList("select s.reservation_id,s.state,s.input_tokens,s.output_tokens from budget_settlements s where s.reservation_id in (select r.id from budget_reservations r where "+selection+") order by s.reservation_id",parameters);
        var items=db.queryForList("select i.reservation_id,i.account_id,i.reserved_amount,a.scope_type,a.scope_key,a.unit,a.violated from budget_reservation_items i join budget_accounts a on a.id=i.account_id where i.reservation_id in (select r.id from budget_reservations r where "+selection+") order by i.reservation_id,i.account_id",parameters);
        Map<Long,Map<String,Object>> paid=new HashMap<>();settlements.forEach(r->paid.put(number(r,"reservation_id"),r));
        int settled=0,known=0;long input=0,output=0;
        for(var r:reservations) {
            var s=paid.get(number(r,"id"));
            if(!"MODEL".equals(r.get("operation"))||number(r,"agent_id")!=number(root,"agent_id"))blockers.add("BUDGET_SCOPE_MISMATCH");
            if("SETTLED".equals(r.get("state"))&&s!=null&&"SETTLED".equals(s.get("state"))&&Objects.equals(r.get("input_tokens"),s.get("input_tokens"))&&Objects.equals(r.get("output_tokens"),s.get("output_tokens")))settled++;
            if(r.get("input_tokens") instanceof Number a&&r.get("output_tokens") instanceof Number b&&a.longValue()>=0&&b.longValue()>=0) {
                try {input=Math.addExact(input,a.longValue());output=Math.addExact(output,b.longValue());known++;}catch(ArithmeticException e){blockers.add("USAGE_OVERFLOW");}
            }
        }
        if(reservations.isEmpty()||settled!=reservations.size()||known!=reservations.size())blockers.add("BUDGET_EVIDENCE_INCOMPLETE");
        for(var r:reservations)if(items.stream().filter(i->number(i,"reservation_id")==number(r,"id")&&"TASK".equals(i.get("scope_type"))&&("optimization:"+id).equals(i.get("scope_key"))).count()!=2)blockers.add("ROOT_BUDGET_LINK_MISSING");
        if(items.stream().anyMatch(i->yes(i.get("violated"))))blockers.add("BUDGET_ACCOUNT_VIOLATED");
        Set<String> traces=new HashSet<>();reservations.forEach(r->traces.add((String)r.get("trace_id")));
        if(trials.stream().anyMatch(r->"UNKNOWN".equals(r.get("status"))&&!traces.contains(r.get("trace_id")))||reflections.stream().anyMatch(r->"UNKNOWN".equals(r.get("status"))&&!traces.contains(r.get("trace_id"))))blockers.add("UNKNOWN_OPERATION_COST_MISSING");
        var accounts=db.queryForList("select id,unit,limit_amount,margin_amount,revision,used_amount,reserved_amount,violated from budget_accounts where scope_type='TASK' and scope_key=? order by id","optimization:"+id);
        if(accounts.size()!=2||accounts.stream().anyMatch(r->yes(r.get("violated"))||((Number)r.get("reserved_amount")).longValue()!=0))blockers.add("ROOT_BUDGET_NOT_CLEAR");
        for(var a:accounts)if(!Set.of("MODEL_CALLS","OUTPUT_TOKENS").contains(a.get("unit"))||((Number)a.get("used_amount")).longValue()!=("MODEL_CALLS".equals(a.get("unit"))?settled:output))blockers.add("ROOT_COST_MISMATCH");
        Map<String,Object> evidence=new LinkedHashMap<>();evidence.put("root",root);evidence.put("protocol",protocols);evidence.put("experiments",experiments);evidence.put("trials",trials);evidence.put("reflections",reflections);evidence.put("runtime",runtimes);evidence.put("connection",connections);evidence.put("reservations",reservations);evidence.put("settlements",settlements);evidence.put("items",items);evidence.put("accounts",accounts);
        String digest=hash(encode(evidence));Decision prior=decision(id);List<String> reasons=blockers.stream().distinct().toList();
        var view=new Inspection(id,(String)root.get("status"),(String)root.get("reason_code"),instance,uid,ids,(int)trials.stream().filter(r->"UNKNOWN".equals(r.get("status"))).count(),(int)trials.stream().filter(r->"CANCELLED".equals(r.get("status"))).count(),reservations.size(),settled,known,known==reservations.size()&&!reservations.isEmpty()?input:null,known==reservations.size()&&!reservations.isEmpty()?output:null,reasons,digest,prior,prior!=null&&reasons.isEmpty()&&digest.equals(prior.evidenceDigest()));
        return new Snapshot(view,evidence);
    }
    @Transactional(readOnly=true) public Inspection inspect(long id) {var s=snapshot(id,false);var agent=db.queryForObject("select agent_id from agent_optimizations where id=?",Long.class,id);agents.get(agent);return s.view();}
    @Transactional public Inspection decide(long id,Decide request,String principal) {
        if(principal==null||principal.isBlank())throw new BusinessException("认证主体缺失");
        db.queryForObject("select id from agent_optimization_admission where id=1 for update",Long.class);
        db.queryForObject("select id from agent_experiment_admission where id=1 for update",Long.class);
        String actor=hash(principal),fingerprint=hash(encode(List.of(id,request.evidenceDigest(),request.acceptUnknownAndNoReplay(),actor)));
        var old=db.queryForList("select run_id,request_fingerprint from agent_optimization_unknown_reviews where request_key=?",request.requestKey());
        if(!old.isEmpty()) {if(number(old.getFirst(),"run_id")!=id||!fingerprint.equals(old.getFirst().get("request_fingerprint")))throw new ConflictException("治理提交键属于其他决定或主体");return inspect(id);}
        var s=snapshot(id,true);agents.get(db.queryForObject("select agent_id from agent_optimizations where id=?",Long.class,id));
        if(s.view().decision()!=null)throw new ConflictException("原意图已有不可改写的治理决定");
        if(!request.acceptUnknownAndNoReplay()||!s.view().blockers().isEmpty()||!s.view().evidenceDigest().equals(request.evidenceDigest()))throw new ConflictException("原证据已变化或不满足停止条件，请重新核对");
        db.update("insert into agent_optimization_unknown_reviews(run_id,request_key,request_fingerprint,evidence_digest,evidence_json,principal_digest,decision,reason_code,created_at) values(?,?,?,?,?,?,'STOP_NO_REPLAY','ORIGINAL_RECEIPT_UNAVAILABLE',CURRENT_TIMESTAMP)",id,request.requestKey(),fingerprint,request.evidenceDigest(),encode(s.evidence()),actor);
        return inspect(id);
    }
    public boolean hasBlockingRoots() {
        for(long id:db.queryForList("select id from agent_optimizations where status in ('QUEUED','SEARCHING','HOLDOUT','UNKNOWN') order by id",Long.class)) {
            if(!"UNKNOWN".equals(db.queryForObject("select status from agent_optimizations where id=?",String.class,id))||!approved(id))return true;
        }return false;
    }
    public boolean hasBlockingExperiments() {
        for(long id:db.queryForList("select id from agent_optimizations where status='UNKNOWN' order by id",Long.class))if(!approved(id))return true;
        for(var e:db.queryForList("select id,status,optimization_run_id from agent_experiments where status in ('QUEUED','RUNNING','UNKNOWN') order by id")) {
            if(!"UNKNOWN".equals(e.get("status"))||!(e.get("optimization_run_id") instanceof Number root)||!approved(root.longValue()))return true;
        }return false;
    }
    private boolean approved(long id) {var d=decision(id);return d!=null&&"STOP_NO_REPLAY".equals(d.decision())&&snapshot(id,false).view().decisionCurrent();}
    public void requireIndependentInstance(String instance) {
        if(db.queryForObject("select count(*) from agent_optimization_unknown_reviews d join agent_optimizations r on r.id=d.run_id where r.instance_key=?",Long.class,instance)>0)throw new ConflictException("已停止原意图的实例不能用于新实验，请使用独立任务与实例");
    }
}
