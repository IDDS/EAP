package com.eap.platform.optimization;

import com.eap.platform.experiment.AgentExperimentDtos;
import com.eap.platform.experiment.AgentExperimentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.*;

/** Immutable registration and sealed holdout batches. Metadata contains no sample or prompt content. */
@Service
public class OptimizationEvidenceService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final AgentExperimentService experiments;
    public OptimizationEvidenceService(JdbcTemplate db,ObjectMapper json,AgentExperimentService experiments) {
        this.db=db;this.json=json;this.experiments=experiments;
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Evidence encoding failed");}}
    private JsonNode tree(String value) {try{return json.readTree(value);}catch(Exception e){throw new IllegalStateException("Evidence decoding failed");}}
    private String digest(Object value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("Evidence digest failed");}}
    public void register(long id,int planned,long output,List<OptimizationDtos.Sample> samples,String principalDigest) {
        int count=(int)samples.stream().filter(s->"HOLDOUT".equals(s.split())).count();
        int groups=(int)samples.stream().filter(s->"HOLDOUT".equals(s.split())).map(OptimizationDtos.Sample::groupId).distinct().count();
        db.update("insert into agent_optimization_protocols values (?,1,?,?,?,?,?)",id,principalDigest,planned,output,count,groups);
    }
    public boolean versionOne(long id) {return db.queryForObject("select count(*) from agent_optimization_protocols where run_id=? and version=1",Long.class,id)==1;}
    // Invoked inside the original seal transaction, before invalidating the search claim. No external execution here.
    public void seal(long id,String manual,String chosen,String dataset) {
        List<String> ids=new ArrayList<>();for(JsonNode s:tree(dataset))if("HOLDOUT".equals(s.path("split").asText()))ids.add(s.path("id").asText());
        Collections.sort(ids);
        for(var arm:List.of(Map.entry("MANUAL",manual),Map.entry("SELECTED",chosen))) {
            String pd=digest(Map.of("system_prompt",arm.getValue()));
            for(int offset=0;offset<ids.size();offset+=3) {
                var batch=List.copyOf(ids.subList(offset,Math.min(offset+3,ids.size())));
                // Identical prompts deliberately share one original evaluation, even across logical arms.
                String operation=digest(List.of(id,pd,"HOLDOUT",batch));
                db.update("insert into agent_optimization_holdout_batches(run_id,source,ordinal,candidate_digest,operation_key,sample_ids_json) values (?,?,?,?,?,?)",id,arm.getKey(),offset/3,pd,operation,encode(batch));
            }
        }
    }
    public List<Map<String,Object>> batches(long id) {
        return db.queryForList("select * from agent_optimization_holdout_batches where run_id=? order by case when source='MANUAL' then 0 else 1 end,ordinal",id);
    }
    private int count(JsonNode dataset,String split) {int n=0;for(JsonNode s:dataset)if(split.equals(s.path("split").asText()))n++;return n;}
    public OptimizationDtos.Evidence report(long id) {
        var root=db.queryForMap("select dataset_json,status,created_at,updated_at,CURRENT_TIMESTAMP as observed_at from agent_optimizations where id=?",id);
        JsonNode dataset=tree((String)root.get("dataset_json"));int groups=0;Set<String> distinct=new HashSet<>();
        for(JsonNode s:dataset)if("HOLDOUT".equals(s.path("split").asText()))distinct.add(s.path("groupId").asText());groups=distinct.size();
        var protocols=db.queryForList("select * from agent_optimization_protocols where run_id=?",id);
        List<OptimizationDtos.HoldoutArm> arms=new ArrayList<>();
        if(!protocols.isEmpty())for(String source:List.of("MANUAL","SELECTED")) {
            List<AgentExperimentDtos.Trial> baseline=new ArrayList<>(),candidate=new ArrayList<>();int completed=0,planned=0;
            for(var batch:batches(id))if(source.equals(batch.get("source"))) {
                planned++;
                if(batch.get("evaluation_id") instanceof Number n) {
                    long experiment=db.queryForObject("select experiment_id from agent_optimization_evaluations where id=?",Long.class,n.longValue());
                    var e=experiments.get(experiment);
                    if("COMPLETED".equals(e.status()))completed++;
                    e.trials().forEach(t->{if("BASELINE".equals(t.variant()))baseline.add(t);else if("CANDIDATE".equals(t.variant()))candidate.add(t);});
                }
            }
            int registered=count(dataset,"HOLDOUT");
            arms.add(new OptimizationDtos.HoldoutArm(source,registered,groups,planned,completed,metrics(baseline,registered),metrics(candidate,registered),failed(baseline),failed(candidate),notRun(baseline,registered),notRun(candidate,registered)));
        }
        int planned=protocols.isEmpty()?0:((Number)protocols.getFirst().get("planned_calls")).intValue();
        long output=protocols.isEmpty()?0:((Number)protocols.getFirst().get("planned_output_tokens")).longValue();
        return new OptimizationDtos.Evidence(protocols.isEmpty()?0:1,count(dataset,"TRAIN"),count(dataset,"VALIDATION"),count(dataset,"HOLDOUT"),groups,arms,cost(id,planned,output,root));
    }
    private int failed(List<AgentExperimentDtos.Trial> trials) {return (int)trials.stream().filter(t->"FAILED".equals(t.status())).count();}
    private int notRun(List<AgentExperimentDtos.Trial> trials,int registered) {
        return registered-(int)trials.stream().filter(t->Set.of("SUCCEEDED","FAILED","UNKNOWN").contains(t.status())).count();
    }
    public static OptimizationDtos.Metrics metrics(List<AgentExperimentDtos.Trial> ts,int registered) {
        int known=0,passed=0,succeeded=0,unknown=0;long input=0,output=0,latency=0,elapsed=0;boolean timeKnown=ts.size()==registered;
        for(var t:ts) {
            if("SUCCEEDED".equals(t.status())){succeeded++;if(Objects.equals(t.score(),1))passed++;}
            if("UNKNOWN".equals(t.status()))unknown++;
            if("PROVIDER_REPORTED".equals(t.usageSource())&&t.inputTokens()!=null&&t.outputTokens()!=null&&t.inputTokens()>=0&&t.outputTokens()>=0) {
                try{input=Math.addExact(input,t.inputTokens());output=Math.addExact(output,t.outputTokens());known++;}catch(ArithmeticException e){/* Keep incomplete totals unknown. */}
            }
            if(t.latencyMs()!=null&&t.endToEndMs()!=null&&t.latencyMs()>=0&&t.endToEndMs()>=0) {
                try{latency=Math.addExact(latency,t.latencyMs());elapsed=Math.addExact(elapsed,t.endToEndMs());}catch(ArithmeticException e){timeKnown=false;}
            }else timeKnown=false;
        }
        return new OptimizationDtos.Metrics(registered,passed,succeeded,unknown,known,known==registered?input:null,known==registered?output:null,timeKnown?latency:null,timeKnown?elapsed:null);
    }
    private OptimizationDtos.Cost cost(long id,int planned,long output,Map<String,Object> root) {
        var rows=db.queryForList("select r.id,r.state,r.input_tokens,r.output_tokens,r.created_at,r.settled_at,t.latency_ms,x.split,x.source from budget_reservations r join budget_reservation_items i on i.reservation_id=r.id join budget_accounts a on a.id=i.account_id left join agent_optimization_evaluations x on x.experiment_id=r.experiment_id and x.run_id=? left join agent_experiment_trials t on t.trace_id=r.trace_id where a.scope_type='TASK' and a.scope_key=? and a.unit='MODEL_CALLS' order by r.id",id,"optimization:"+id);
        Map<String,List<Map<String,Object>>> phases=new LinkedHashMap<>();
        for(String name:List.of("SEARCH","REFLECTION","MANUAL_VALIDATION","HOLDOUT"))phases.put(name,new ArrayList<>());
        for(var row:rows) {
            String name=row.get("split")==null?"REFLECTION":"HOLDOUT".equals(row.get("split"))?"HOLDOUT":"MANUAL".equals(row.get("source"))?"MANUAL_VALIDATION":"SEARCH";
            phases.get(name).add(row);
        }
        var totals=tokens(rows);int settled=(int)rows.stream().filter(r->"SETTLED".equals(r.get("state"))).count();
        int unknown=(int)rows.stream().filter(r->Set.of("DISPATCHED","UNKNOWN","VIOLATION").contains(r.get("state"))).count();
        List<OptimizationDtos.PhaseCost> parts=new ArrayList<>();
        phases.forEach((name,part)->{
            var tokens=tokens(part);List<Long> latencies=new ArrayList<>();
            for(var row:part) {
                Long duration=row.get("latency_ms") instanceof Number n?n.longValue():duration(row.get("created_at"),row.get("settled_at"));
                if(duration!=null&&duration>=0)latencies.add(duration);
            }
            Collections.sort(latencies);
            parts.add(new OptimizationDtos.PhaseCost(name,part.size(),tokens.known,tokens.input,tokens.output,percentile(latencies,.5),percentile(latencies,.95),latencies.size()));
        });
        Object end=Set.of("COMPLETED","FAILED","UNKNOWN").contains(root.get("status"))?root.get("updated_at"):root.get("observed_at");
        return new OptimizationDtos.Cost(planned,output,rows.size(),settled,unknown,totals.known,totals.input,totals.output,duration(root.get("created_at"),end),parts,"UNPRICED");
    }
    private record Tokens(int known,Long input,Long output) {}
    private Tokens tokens(List<Map<String,Object>> rows) {
        int known=0;long input=0,output=0;
        for(var row:rows)if(row.get("input_tokens") instanceof Number a&&row.get("output_tokens") instanceof Number b&&a.longValue()>=0&&b.longValue()>=0) {
            try{input=Math.addExact(input,a.longValue());output=Math.addExact(output,b.longValue());known++;}catch(ArithmeticException e){/* Unknown, not zero. */}
        }
        boolean complete=!rows.isEmpty()&&known==rows.size();return new Tokens(known,complete?input:null,complete?output:null);
    }
    private static Long duration(Object start,Object end) {
        if(!(start instanceof Timestamp a)||!(end instanceof Timestamp b))return null;
        long ms=b.getTime()-a.getTime();return ms<0?null:ms;
    }
    private static Long percentile(List<Long> sorted,double p) {return sorted.isEmpty()?null:sorted.get((int)Math.ceil(sorted.size()*p)-1);}
}
