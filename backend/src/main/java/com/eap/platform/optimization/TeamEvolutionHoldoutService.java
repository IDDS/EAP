package com.eap.platform.optimization;

import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** One frozen independent comparison and one final root accounting record. Never applies a prompt. */
@Service
public class TeamEvolutionHoldoutService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TeamEvolutionService contracts;
    private final TeamEvolutionScoringService scoring;
    @Value("${eap.team-evolution.holdout-enabled:false}") boolean enabled;
    public TeamEvolutionHoldoutService(JdbcTemplate db,ObjectMapper json,TeamEvolutionService contracts,TeamEvolutionScoringService scoring) {
        this.db=db;this.json=json;this.contracts=contracts;this.scoring=scoring;
    }
    public record Holdout(String frontierDigest,String holdoutDigest,TeamEvolutionService.Evaluation baseline,TeamEvolutionService.Evaluation selected) {}
    public record Batch(String evaluationId,String candidateId,String split,String arm,String state,boolean scored) {}
    public record Closure(String protocolDigest,String datasetDigest,String frontierDigest,String holdoutDigest,
                          String baselineId,String selectedCandidateId,String recommendation,String comparisonPolicy,
                          boolean adoptionAvailable,boolean stableBenefitVerified,boolean completeEvidence,
                          TeamEvolutionScoringService.Score baseline,TeamEvolutionScoringService.Score selected,
                          TeamEvolutionScoringService.Costs costs,int proposalModelCalls,int proposalAttempts,int registeredCandidates,List<Batch> batches) {}
    private void available(){if(!enabled||!contracts.contractsEnabled())throw new BusinessException("TEAM_HOLDOUT_DISABLED");}
    private <T> T decode(String value,Class<T> type){try{return json.readValue(value,type);}catch(Exception ex){throw new BusinessException("TEAM_RECORD_UNREADABLE");}}
    private JsonNode samples(Map<String,Object> root) {
        var dataset=contracts.tree((String)root.get("dataset_json"));
        if(!contracts.hash(dataset).equals(root.get("dataset_digest")))throw new BusinessException("TEAM_HOLDOUT_DATASET_MISMATCH");
        var samples=json.createArrayNode();for(var sample:dataset)if("HOLDOUT".equals(sample.path("split").asText()))samples.add(sample);
        if(samples.isEmpty()||samples.size()>10)throw new BusinessException("TEAM_HOLDOUT_DATASET_MISMATCH");return samples;
    }
    private Map<String,Object> frontier(long root) {
        var rows=db.queryForList("select result_json from team_evolution_frontiers where contract_id=?",root);
        if(rows.size()!=1)throw new BusinessException("TEAM_SEALED_FRONTIER_REQUIRED");return rows.getFirst();
    }
    @Transactional(readOnly=true)
    public Holdout get(long root) {
        contracts.contract(root,false);
        var rows=db.queryForList("select * from team_evolution_holdouts where contract_id=?",root);
        if(rows.size()!=1)throw new BusinessException("TEAM_HOLDOUT_NOT_FOUND");var h=rows.getFirst();
        return new Holdout((String)h.get("frontier_digest"),(String)h.get("holdout_digest"),
                contracts.evaluation(root,(String)h.get("baseline_evaluation_id")),contracts.evaluation(root,(String)h.get("selected_evaluation_id")));
    }
    @Transactional
    public Holdout freeze(long root,JsonNode request,String actor) {
        available();contracts.fields(request,"requestKey");String key=contracts.key(request,"requestKey"),fp=contracts.hash(request);
        var c=contracts.contract(root,true);contracts.owner(c,actor);
        var prior=db.queryForList("select request_key,fingerprint from team_evolution_holdouts where contract_id=?",root);
        if(!prior.isEmpty()) {
            if(!key.equals(prior.getFirst().get("request_key"))||!fp.equals(prior.getFirst().get("fingerprint")))throw new ConflictException("TEAM_ORIGINAL_HOLDOUT_REQUIRED");
            return get(root);
        }
        contracts.rootOpen(root);
        String frozen=(String)frontier(root).get("result_json");var f=decode(frozen,TeamEvolutionScoringService.Frontier.class);
        if(!f.sealed()||f.selectedCandidateId()==null||!f.frontier().contains(f.selectedCandidateId()))throw new BusinessException("TEAM_HOLDOUT_SELECTION_REQUIRED");
        var plans=db.queryForList("select approval_expires_at from team_evolution_budget_plans where contract_id=? for update",root);
        if(plans.size()!=1||!((Timestamp)plans.getFirst().get("approval_expires_at")).toInstant().isAfter(Instant.now()))throw new BusinessException("TEAM_APPROVAL_EXPIRED");
        var samples=samples(c);
        String baseline=arm(root,c,f.baselineId(),"BASELINE",samples),selected=arm(root,c,f.selectedCandidateId(),"SELECTED",samples);
        db.update("insert into team_evolution_holdouts(contract_id,request_key,fingerprint,principal_digest,frontier_digest,holdout_digest,baseline_evaluation_id,selected_evaluation_id,created_at) values(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",root,key,fp,contracts.principal(actor),contracts.hash(contracts.tree(frozen)),contracts.hash(samples),baseline,selected);
        return get(root);
    }
    private String arm(long root,Map<String,Object> c,String candidate,String arm,JsonNode samples) {
        String id=UUID.randomUUID().toString();
        db.update("insert into team_evolution_evaluations(id,contract_id,candidate_id,operation_key,fingerprint,state,created_at,split_name,arm) values(?,?,?,?,?,'NOT_DISPATCHED',CURRENT_TIMESTAMP,'HOLDOUT',?)",id,root,candidate,"holdout:"+id,contracts.hash(List.of(root,candidate,arm)),arm);
        for(var sample:samples) {
            int ordinal=0;for(var role:contracts.tree((String)c.get("protocol_json")).path("roles")) {
                db.update("insert into team_evolution_slots(evaluation_id,sample_id,role_name,role_ordinal,operation_key,trace_id,state) values(?,?,?,?,?,?,'NOT_DISPATCHED')",id,sample.path("id").asText(),role.path("role").asText(),ordinal++,"team-eval:"+UUID.randomUUID(),UUID.randomUUID().toString().replace("-",""));
            }
        }
        return id;
    }
    @Transactional(readOnly=true)
    public Closure getClosure(long root) {
        contracts.contract(root,false);var rows=db.queryForList("select result_json from team_evolution_closures where contract_id=?",String.class,root);
        if(rows.size()!=1)throw new BusinessException("TEAM_CLOSURE_NOT_FOUND");return decode(rows.getFirst(),Closure.class);
    }
    @Transactional
    public Closure close(long root,JsonNode request,String actor) {
        available();contracts.fields(request,"requestKey");String key=contracts.key(request,"requestKey"),fp=contracts.hash(request);
        var c=contracts.contract(root,true);contracts.owner(c,actor);
        var prior=db.queryForList("select * from team_evolution_closures where contract_id=?",root);
        if(!prior.isEmpty()) {
            if(!key.equals(prior.getFirst().get("request_key"))||!fp.equals(prior.getFirst().get("fingerprint")))throw new ConflictException("TEAM_ORIGINAL_CLOSURE_REQUIRED");
            return decode((String)prior.getFirst().get("result_json"),Closure.class);
        }
        if(db.queryForObject("select count(*) from team_evolution_proposals where contract_id=? and state in ('PREPARING','DISPATCHED')",Integer.class,root)!=0)throw new ConflictException("TEAM_PROPOSAL_STILL_RUNNING");
        var frontiers=db.queryForList("select result_json from team_evolution_frontiers where contract_id=?",root);
        String frozen=frontiers.isEmpty()?null:(String)frontiers.getFirst().get("result_json");
        var f=frozen==null?null:decode(frozen,TeamEvolutionScoringService.Frontier.class);
        // A stopped first attempt can close without inventing a validation score or selection.
        // Same order as Gateway: global budget -> executions -> reservation/account current reads.
        db.queryForObject("select id from budget_admission_lock where id=1 for update",Long.class);
        var executions=db.queryForList("select x.evaluation_id,x.state from team_evolution_executions x join team_evolution_evaluations e on e.id=x.evaluation_id where e.contract_id=? order by x.evaluation_id for update",root);
        if(executions.stream().anyMatch(x->"RUNNING".equals(x.get("state"))))throw new ConflictException("TEAM_EXECUTION_STILL_RUNNING");
        var scores=db.queryForList("select s.evaluation_id,s.result_json from team_evolution_scores s join team_evolution_evaluations e on e.id=s.evaluation_id where e.contract_id=?",root);
        var byId=new TreeMap<String,TeamEvolutionScoringService.Score>();for(var s:scores)byId.put((String)s.get("evaluation_id"),decode((String)s.get("result_json"),TeamEvolutionScoringService.Score.class));
        if(executions.stream().anyMatch(x->"RECEIPTS_COMPLETE".equals(x.get("state"))&&!byId.containsKey(x.get("evaluation_id"))))throw new ConflictException("TEAM_COMPLETE_SCORE_REQUIRED");
        var costs=scoring.costs(root,true);if(costs.pendingCalls()!=0)throw new ConflictException("TEAM_COST_SETTLEMENT_PENDING");
        verifyAccounts(root,costs);
        var rows=db.queryForList("select * from team_evolution_holdouts where contract_id=?",root);
        TeamEvolutionScoringService.Score base=null,selected=null;String digest=null;
        if(!rows.isEmpty()) {
            var h=rows.getFirst();digest=(String)h.get("holdout_digest");
            if(frozen==null||!contracts.hash(contracts.tree(frozen)).equals(h.get("frontier_digest"))||!contracts.hash(samples(c)).equals(digest))throw new BusinessException("TEAM_ORIGINAL_HOLDOUT_REQUIRED");
            base=byId.get(h.get("baseline_evaluation_id"));selected=byId.get(h.get("selected_evaluation_id"));
        }
        int registered=db.queryForObject("select count(*) from team_evolution_candidates where contract_id=?",Integer.class,root);
        long validated=byId.values().stream().filter(s->"VALIDATION".equals(s.split())).map(TeamEvolutionScoringService.Score::candidateId).distinct().count();
        boolean proposalUnknown=db.queryForObject("select count(*) from team_evolution_proposals where contract_id=? and state='UNKNOWN'",Integer.class,root)!=0;
        int proposalCalls=db.queryForObject("select count(*) from budget_reservations r join team_evolution_proposals p on p.trace_id=r.trace_id where p.contract_id=? and r.task_key=?",Integer.class,root,"team-evolution:"+root);
        boolean complete=!proposalUnknown&&f!=null&&base!=null&&selected!=null&&validated==registered&&!costs.unknownUsage()&&costs.unknownCalls()==0&&costs.violationCalls()==0&&costs.modelCalls()==costs.settledCalls();
        String recommendation="INCOMPLETE_NO_ADOPTION";
        if(complete) {
            boolean quality=(long)selected.passed()*10000>=(long)((Number)c.get("minimum_quality_bps")).intValue()*selected.denominator()
                    &&(long)selected.passed()*base.denominator()>=(long)base.passed()*selected.denominator();
            boolean dominates=(long)selected.passed()*base.denominator()>(long)base.passed()*selected.denominator()
                    ||selected.totalTokens()<base.totalTokens()||selected.totalLatencyMicros()<base.totalLatencyMicros();
            dominates=dominates&&selected.totalTokens()<=base.totalTokens()&&selected.totalLatencyMicros()<=base.totalLatencyMicros();
            recommendation=quality&&dominates&&!f.baselineId().equals(f.selectedCandidateId())?"BOUNDED_HOLDOUT_SUPPORT_NO_ADOPTION":"KEEP_BASELINE";
        }
        var states=new TreeMap<String,String>();executions.forEach(x->states.put((String)x.get("evaluation_id"),(String)x.get("state")));
        var batches=db.queryForList("select id,candidate_id,split_name,arm,state from team_evolution_evaluations where contract_id=? order by id",root).stream()
                .map(e->new Batch((String)e.get("id"),(String)e.get("candidate_id"),(String)e.get("split_name"),(String)e.get("arm"),states.getOrDefault(e.get("id"),(String)e.get("state")),byId.containsKey(e.get("id")))).toList();
        var result=new Closure((String)c.get("protocol_digest"),(String)c.get("dataset_digest"),frozen==null?null:contracts.hash(contracts.tree(frozen)),digest,(String)c.get("baseline_id"),f==null?null:f.selectedCandidateId(),recommendation,"HOLDOUT_QUALITY_THEN_PARETO_V1",false,false,complete,base,selected,costs,proposalCalls,((Number)c.get("attempts")).intValue(),registered,batches);
        db.update("insert into team_evolution_closures(contract_id,request_key,fingerprint,principal_digest,result_json,created_at) values(?,?,?,?,?,CURRENT_TIMESTAMP)",root,key,fp,contracts.principal(actor),contracts.encode(result));return result;
    }
    private void verifyAccounts(long root,TeamEvolutionScoringService.Costs costs) {
        var plan=db.queryForMap("select max_calls,max_output_tokens from team_evolution_budget_plans where contract_id=?",root);
        var reservations=db.queryForList("select state,output_bound from budget_reservations where task_key=? order by id for update","team-evolution:"+root);
        long outstanding=0;for(var r:reservations)if("UNKNOWN".equals(r.get("state")))outstanding=Math.addExact(outstanding,((Number)r.get("output_bound")).longValue());
        if(outstanding!=costs.reservedOutputTokens())throw new BusinessException("TEAM_ROOT_ACCOUNTING_MISMATCH");
        var accounts=db.queryForList("select * from budget_accounts where scope_type='TASK' and scope_key=? order by unit for update","team-evolution:"+root);
        if(accounts.size()!=3)throw new BusinessException("TEAM_ROOT_ACCOUNTING_MISMATCH");
        for(var a:accounts) {
            String unit=(String)a.get("unit");long limit=switch(unit){case "MODEL_CALLS"->((Number)plan.get("max_calls")).longValue();case "OUTPUT_TOKENS"->((Number)plan.get("max_output_tokens")).longValue();case "TOOL_CLAIMS"->0;default->-1;};
            long used="MODEL_CALLS".equals(unit)?costs.modelCalls():"OUTPUT_TOKENS".equals(unit)?costs.knownOutputTokens():0;
            long reserved="OUTPUT_TOKENS".equals(unit)?costs.reservedOutputTokens():0;
            if(((Number)a.get("limit_amount")).longValue()!=limit||((Number)a.get("revision")).longValue()!=1||((Number)a.get("margin_amount")).longValue()!=0
                    ||((java.math.BigDecimal)a.get("used_amount")).compareTo(java.math.BigDecimal.valueOf(used))!=0||((java.math.BigDecimal)a.get("reserved_amount")).compareTo(java.math.BigDecimal.valueOf(reserved))!=0)
                throw new BusinessException("TEAM_ROOT_ACCOUNTING_MISMATCH");
        }
    }
}
