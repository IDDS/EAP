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
import java.time.Duration;
import java.util.*;

/** Consumes only original, complete Gateway-backed validation evidence. No model or Runner calls. */
@Service
public class TeamEvolutionScoringService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TeamEvolutionService contracts;
    @Value("${eap.team-evolution.scoring-enabled:false}") boolean enabled;
    public TeamEvolutionScoringService(JdbcTemplate db,ObjectMapper json,TeamEvolutionService contracts) {
        this.db=db;this.json=json;this.contracts=contracts;
    }
    public record Sample(String sampleId,boolean passed,long inputTokens,long outputTokens,long latencyMicros) {}
    public record Score(String evaluationId,String candidateId,String protocolDigest,String validationDigest,
                        String split,String arm,String batchDigest,String scorer,String latencyProtocol,String usageSource,int denominator,int passed,int qualityBps,
                        long inputTokens,long outputTokens,long totalTokens,long totalLatencyMicros,
                        String proofDigest,List<Sample> samples) {}
    public record Costs(int modelCalls,int settledCalls,int unknownCalls,int pendingCalls,int violationCalls,
                        long knownInputTokens,long knownOutputTokens,boolean unknownUsage,long reservedOutputTokens) {}
    public record Frontier(boolean sealed,String protocolDigest,String validationDigest,String baselineId,
                           List<String> candidates,List<String> frontier,Map<String,String> decisions,List<Score> scores,
                           String selectedCandidateId,String selectionPolicy,String recommendation,Costs costs) {}
    private void available() {if(!enabled||!contracts.contractsEnabled())throw new BusinessException("TEAM_SCORING_DISABLED");}
    private void require(boolean condition) {if(!condition)throw new BusinessException("TEAM_SCORE_EVIDENCE_INCOMPLETE");}
    private long number(Map<String,Object> row,String key) {require(row.get(key) instanceof Number);return ((Number)row.get(key)).longValue();}
    private Instant time(Map<String,Object> row,String key) {require(row.get(key) instanceof Timestamp);return ((Timestamp)row.get(key)).toInstant();}
    private boolean same(Object a,Object b) {return Objects.equals(a,b);}
    private <T> T decode(String value,Class<T> type) {
        try{return json.readValue(value,type);}catch(Exception ex){throw new BusinessException("TEAM_RECORD_UNREADABLE");}
    }
    @Transactional(readOnly=true)
    public Score get(long root,String evaluation) {
        contracts.evaluation(root,evaluation);
        var rows=db.queryForList("select result_json from team_evolution_scores where evaluation_id=?",evaluation.toLowerCase(Locale.ROOT));
        if(rows.isEmpty())throw new BusinessException("TEAM_SCORE_NOT_FOUND");
        return decode((String)rows.getFirst().get("result_json"),Score.class);
    }
    @Transactional
    public Score score(long root,String evaluation,JsonNode request,String actor) {
        available();contracts.fields(request,"requestKey");String key=contracts.key(request,"requestKey"),fp=contracts.hash(request);
        var c=contracts.contract(root,true);contracts.owner(c,actor);var e=contracts.evaluation(root,evaluation);
        var prior=db.queryForList("select * from team_evolution_scores where evaluation_id=?",e.id());
        if(!prior.isEmpty()) {
            if(!same(key,prior.getFirst().get("request_key"))||!same(fp,prior.getFirst().get("fingerprint")))throw new ConflictException("TEAM_ORIGINAL_SCORE_REQUIRED");
            return decode((String)prior.getFirst().get("result_json"),Score.class);
        }
        contracts.evaluationOpen(root,e);
        Score result;
        try{result=derive(root,c,e);}catch(BusinessException ex){throw ex;}
        catch(ArithmeticException|NullPointerException|IllegalArgumentException ex){throw new BusinessException("TEAM_SCORE_EVIDENCE_INCOMPLETE");}
        db.update("insert into team_evolution_scores(evaluation_id,request_key,fingerprint,principal_digest,proof_digest,result_json,created_at) values(?,?,?,?,?,?,CURRENT_TIMESTAMP)",e.id(),key,fp,contracts.principal(actor),result.proofDigest(),contracts.encode(result));
        return result;
    }
    private Score derive(long root,Map<String,Object> c,TeamEvolutionService.Evaluation e) {
        var executions=db.queryForList("select * from team_evolution_executions where evaluation_id=? for update",e.id());require(executions.size()==1);
        var x=executions.getFirst();require("RECEIPTS_COMPLETE".equals(x.get("state"))&&"RECEIPTS_COMPLETE".equals(e.state())&&same(x.get("principal_digest"),c.get("principal_digest")));
        var plans=db.queryForList("select * from team_evolution_budget_plans where contract_id=?",root);require(plans.size()==1);var plan=plans.getFirst();
        Instant start=time(x,"started_at"),end=time(x,"finished_at"),deadline=time(x,"deadline");
        require(!end.isBefore(start)&&!end.isAfter(deadline)&&deadline.equals(time(plan,"approval_expires_at")));
        var protocol=contracts.tree((String)c.get("protocol_json"));var roles=protocol.path("roles");
        require(roles.isArray()&&roles.size()==2&&"EXACT_TEXT_V1".equals(protocol.path("scorer").asText())&&"SERIAL_PREVIOUS_OUTPUT_V1".equals(protocol.path("handoff").asText()));
        var bindings=contracts.tree((String)plan.get("live_bindings_json"));require(bindings.isArray()&&bindings.size()==2);
        var configuration=contracts.tree(db.queryForObject("select configuration_json from team_evolution_candidates where contract_id=? and candidate_id=?",String.class,root,e.candidateId()));
        var dataset=contracts.tree((String)c.get("dataset_json"));var samples=new TreeMap<String,JsonNode>();var validation=json.createArrayNode();
        for(var sample:dataset)if(e.split().equals(sample.path("split").asText())){require(samples.put(sample.path("id").asText(),sample)==null);validation.add(sample);}
        require(!samples.isEmpty()&&samples.size()<=("HOLDOUT".equals(e.split())?10:3)&&same(contracts.hash(dataset),c.get("dataset_digest"))&&("HOLDOUT".equals(e.split())?same(contracts.hash(validation),db.queryForObject("select holdout_digest from team_evolution_holdouts where contract_id=?",String.class,root)):same(contracts.hash(validation),c.get("validation_digest")))&&same(contracts.hash(protocol),c.get("protocol_digest")));
        var slots=db.queryForList("select * from team_evolution_slots where evaluation_id=? order by sample_id,role_ordinal for update",e.id());
        // Sample IDs permit punctuation/case. Canonical order must not depend on a DB collation.
        slots.sort(Comparator.comparing((Map<String,Object> s)->(String)s.get("sample_id")).thenComparingInt(s->((Number)s.get("role_ordinal")).intValue()));
        var dispatches=db.queryForList("select * from team_evolution_dispatches where evaluation_id=? order by operation_key for update",e.id());
        var markers=db.queryForList("select * from team_evolution_sample_responsibilities where evaluation_id=? order by sample_id for update",e.id());
        require(slots.size()==samples.size()*2&&dispatches.size()==slots.size()&&markers.size()==samples.size());
        var proof=new ArrayList<Object>();proof.add(c.get("protocol_digest"));proof.add(c.get("validation_digest"));proof.add(e.split());proof.add(e.arm());proof.add(contracts.hash(validation));proof.add(e.candidateId());proof.add(configuration);proof.add(x);proof.add(markers);
        var results=new ArrayList<Sample>();long totalInput=0,totalOutput=0,totalLatency=0;int passed=0,index=0;Instant lastFinish=start;
        for(var sample:samples.values()) {
            String sid=sample.path("id").asText();var marker=markers.stream().filter(m->same(m.get("sample_id"),sid)).findFirst().orElseThrow(()->new BusinessException("TEAM_SCORE_EVIDENCE_INCOMPLETE"));
            Instant sampleStart=time(marker,"started_at"),previousFinish=sampleStart;
            require(same(marker.get("holder"),x.get("holder"))&&!sampleStart.isBefore(lastFinish));
            String input=sample.path("input").asText(),output=null;long inputTokens=0,outputTokens=0;
            for(int ordinal=0;ordinal<2;ordinal++) {
                var s=slots.get(index++);var role=roles.path(ordinal);var binding=bindings.path(ordinal);
                require(same(s.get("sample_id"),sid)&&number(s,"role_ordinal")==ordinal&&same(s.get("role_name"),role.path("role").asText())&&"SUCCEEDED".equals(s.get("state")));
                var d=dispatches.stream().filter(v->same(v.get("operation_key"),s.get("operation_key"))).findFirst().orElseThrow(()->new BusinessException("TEAM_SCORE_EVIDENCE_INCOMPLETE"));
                require("SUCCEEDED".equals(d.get("state"))&&same(d.get("holder"),x.get("holder"))&&same(d.get("trace_id"),s.get("trace_id")));
                for(String name:List.of("agentId","runtimeId"))require(number(d,name.equals("agentId")?"agent_id":"runtime_id")==role.path(name).asLong());
                for(var pair:Map.of("instance_key","instanceKey","actor_uid","actorUid","actor_template","actorTemplate","endpoint","endpoint").entrySet())require(same(d.get(pair.getKey()),role.path(pair.getValue()).asText()));
                require(!binding.path("identityGeneration").asText().isBlank()&&same(d.get("profile_digest"),binding.path("profileDigest").asText()));
                var body=contracts.tree((String)d.get("body_json"));
                require(same(body.path("taskKey").asText(),s.get("operation_key"))&&same(body.path("traceId").asText(),s.get("trace_id"))&&same(body.path("profileDigest").asText(),d.get("profile_digest")));
                String prompt=configuration.path("rolePrompts").path(role.path("role").asText()).asText();
                require(input.equals(body.path("input").asText())&&prompt.equals(body.path("systemPrompt").asText())&&body.path("maxTokens").asInt()==protocol.path("maxTokens").asInt());
                var model=json.createObjectNode().put("model",role.path("model").asText()).put("max_tokens",protocol.path("maxTokens").asInt()).put("temperature",0).put("stream",false);
                model.set("messages",json.createArrayNode().add(json.createObjectNode().put("role","system").put("content",prompt)).add(json.createObjectNode().put("role","user").put("content",input)));
                require(same(TeamEvolutionDigest.of(json,model),d.get("model_request_digest")));
                var value=contracts.tree((String)d.get("output_json"));require(value.isTextual()&&same(TeamEvolutionDigest.of(json,value),d.get("gateway_output_digest"))&&Set.of("stop","length").contains(d.get("gateway_finish_reason")));
                Instant sent=time(d,"started_at"),finished=time(d,"finished_at");require(!sent.isBefore(previousFinish)&&!finished.isBefore(sent)&&!finished.isAfter(end));
                var reservations=db.queryForList("select * from budget_reservations where id=? for update",d.get("reservation_id"));require(reservations.size()==1);var r=reservations.getFirst();
                require("SETTLED".equals(r.get("state"))&&"MODEL".equals(r.get("operation"))&&same(r.get("trace_id"),s.get("trace_id"))&&same(r.get("agent_id"),d.get("agent_id"))&&same(r.get("task_key"),"team-evolution:"+root)&&r.get("experiment_id")==null);
                long in=number(r,"input_tokens"),out=number(r,"output_tokens");require(in>=0&&out>=0&&out<=protocol.path("maxTokens").asLong()&&number(r,"output_bound")==protocol.path("maxTokens").asLong());
                require(!time(r,"created_at").isBefore(sent)&&!time(r,"settled_at").isBefore(time(r,"created_at"))&&!time(r,"settled_at").isAfter(finished));
                var settlements=db.queryForList("select * from budget_settlements where reservation_id=? for update",r.get("id"));require(settlements.size()==1);var settled=settlements.getFirst();
                require("SETTLED".equals(settled.get("state"))&&number(settled,"input_tokens")==in&&number(settled,"output_tokens")==out);
                var items=db.queryForList("select a.scope_type,a.scope_key,a.unit,i.reserved_amount from budget_reservation_items i join budget_accounts a on a.id=i.account_id where i.reservation_id=? order by a.scope_type,a.scope_key,a.unit",r.get("id"));
                var expected=new TreeSet<String>();for(String scope:List.of("PLATFORM:default","AGENT:"+role.path("agentId").asLong(),"TASK:team-evolution:"+root))for(String unit:List.of("MODEL_CALLS","OUTPUT_TOKENS"))expected.add(scope+":"+unit);
                var actual=new TreeSet<String>();for(var item:items){actual.add(item.get("scope_type")+":"+item.get("scope_key")+":"+item.get("unit"));require(number(item,"reserved_amount")==("MODEL_CALLS".equals(item.get("unit"))?1:protocol.path("maxTokens").asLong()));}require(items.size()==6&&expected.equals(actual));
                proof.add(s);proof.add(d);proof.add(r);proof.add(settled);proof.add(items);
                inputTokens=Math.addExact(inputTokens,in);outputTokens=Math.addExact(outputTokens,out);output=value.asText();input=output;previousFinish=finished;
            }
            var duration=Duration.between(sampleStart,previousFinish);long micros=Math.addExact(Math.multiplyExact(duration.getSeconds(),1_000_000),duration.getNano()/1000);
            boolean pass=sample.path("expected").asText().equals(output);if(pass)passed++;
            results.add(new Sample(sid,pass,inputTokens,outputTokens,micros));totalInput=Math.addExact(totalInput,inputTokens);totalOutput=Math.addExact(totalOutput,outputTokens);totalLatency=Math.addExact(totalLatency,micros);lastFinish=previousFinish;
        }
        return new Score(e.id(),e.candidateId(),(String)c.get("protocol_digest"),(String)c.get("validation_digest"),e.split(),e.arm(),contracts.hash(validation),"EXACT_TEXT_V1","PLATFORM_SAMPLE_RESPONSIBILITY_MICROS_V1","GATEWAY_SETTLED",results.size(),passed,passed*10000/results.size(),totalInput,totalOutput,Math.addExact(totalInput,totalOutput),totalLatency,contracts.hash(proofValue(proof)),List.copyOf(results));
    }
    // Jackson's default SQL timestamp encoding loses microseconds; preserve the original timing proof.
    private Object proofValue(Object value) {
        if(value instanceof Timestamp timestamp)return timestamp.toInstant().toString();
        if(value instanceof Map<?,?> map){var result=new TreeMap<String,Object>();map.forEach((k,v)->result.put(k.toString(),proofValue(v)));return result;}
        if(value instanceof List<?> list)return list.stream().map(this::proofValue).toList();
        return value;
    }
    @Transactional(readOnly=true)
    public Frontier frontier(long root) {
        var c=contracts.contract(root,false);var prior=db.queryForList("select result_json from team_evolution_frontiers where contract_id=?",root);
        return prior.isEmpty()?assess(root,c,false):decode((String)prior.getFirst().get("result_json"),Frontier.class);
    }
    @Transactional
    public Frontier seal(long root,JsonNode request,String actor) {
        available();contracts.fields(request,"requestKey");String key=contracts.key(request,"requestKey"),fp=contracts.hash(request);
        var c=contracts.contract(root,true);contracts.owner(c,actor);var prior=db.queryForList("select * from team_evolution_frontiers where contract_id=?",root);
        if(!prior.isEmpty()) {
            if(!same(key,prior.getFirst().get("request_key"))||!same(fp,prior.getFirst().get("fingerprint")))throw new ConflictException("TEAM_ORIGINAL_FRONTIER_REQUIRED");
            return decode((String)prior.getFirst().get("result_json"),Frontier.class);
        }
        if(db.queryForObject("select count(*) from team_evolution_proposals where contract_id=? and state in ('PREPARING','DISPATCHED','UNKNOWN')",Integer.class,root)!=0)throw new ConflictException("TEAM_ORIGINAL_PROPOSAL_UNRESOLVED");
        // Match Gateway lock order. Acquiring this lock does not refresh a MySQL RR snapshot.
        db.queryForObject("select id from budget_admission_lock where id=1 for update",Long.class);
        // Serialize against original receipt writers too, without holding locks during network I/O.
        var executions=db.queryForList("select x.evaluation_id,x.state from team_evolution_executions x join team_evolution_evaluations e on e.id=x.evaluation_id where e.contract_id=? order by x.evaluation_id for update",root);
        if(executions.stream().anyMatch(x->"RUNNING".equals(x.get("state"))))throw new ConflictException("TEAM_VALIDATION_STILL_RUNNING");
        Frontier result=assess(root,c,true);
        if(result.costs().pendingCalls()!=0)throw new ConflictException("TEAM_COST_SETTLEMENT_PENDING");
        if(result.decisions().containsValue("BASELINE_UNCONFIRMED")||result.scores().stream().noneMatch(s->s.candidateId().equals(result.baselineId())))throw new BusinessException("TEAM_BASELINE_SCORE_REQUIRED");
        // Complete but unscored evidence must be scored before freezing, rather than silently excluded.
        if(executions.stream().anyMatch(x->"RECEIPTS_COMPLETE".equals(x.get("state"))&&result.scores().stream().noneMatch(s->s.evaluationId().equals(x.get("evaluation_id")))))throw new ConflictException("TEAM_COMPLETE_SCORE_REQUIRED");
        db.update("insert into team_evolution_frontiers(contract_id,request_key,fingerprint,principal_digest,result_json,created_at) values(?,?,?,?,?,CURRENT_TIMESTAMP)",root,key,fp,contracts.principal(actor),contracts.encode(result));return result;
    }
    private Frontier assess(long root,Map<String,Object> c,boolean sealed) {
        var candidates=db.queryForList("select candidate_id from team_evolution_candidates where contract_id=? order by candidate_id",String.class,root);
        var rows=db.queryForList("select s.result_json from team_evolution_scores s join team_evolution_evaluations e on e.id=s.evaluation_id where e.contract_id=? and e.split_name='VALIDATION' order by e.candidate_id",String.class,root);
        var scores=rows.stream().map(r->decode(r,Score.class)).toList();var byId=new TreeMap<String,Score>();scores.forEach(s->byId.put(s.candidateId(),s));
        String baseline=(String)c.get("baseline_id");Score base=byId.get(baseline);int floor=((Number)c.get("minimum_quality_bps")).intValue();
        var decisions=new TreeMap<String,String>();var eligible=new TreeMap<String,Score>();
        for(String id:candidates) {
            var s=byId.get(id);String reason;
            if(s==null)reason="EVIDENCE_INCOMPLETE";
            else if(base==null)reason="BASELINE_UNCONFIRMED";
            else if((long)s.passed()*10000<(long)floor*s.denominator()||(long)s.passed()*base.denominator()<(long)base.passed()*s.denominator())reason="QUALITY_GATE_REJECTED";
            else {reason="FRONTIER";eligible.put(id,s);}decisions.put(id,reason);
        }
        for(var entry:eligible.entrySet())for(var other:eligible.entrySet())if(!entry.getKey().equals(other.getKey())&&dominates(other.getValue(),entry.getValue())){decisions.put(entry.getKey(),"DOMINATED");break;}
        var frontier=decisions.entrySet().stream().filter(e->"FRONTIER".equals(e.getValue())).map(Map.Entry::getKey).toList();
        Comparator<Score> order=(a,b)->{int q=Long.compare((long)b.passed()*a.denominator(),(long)a.passed()*b.denominator());if(q!=0)return q;int t=Long.compare(a.totalTokens(),b.totalTokens());if(t!=0)return t;int l=Long.compare(a.totalLatencyMicros(),b.totalLatencyMicros());return l!=0?l:a.candidateId().compareTo(b.candidateId());};
        String selected=frontier.stream().map(byId::get).min(order).map(Score::candidateId).orElse(null);
        return new Frontier(sealed,(String)c.get("protocol_digest"),(String)c.get("validation_digest"),baseline,candidates,frontier,Collections.unmodifiableMap(decisions),scores,selected,"QUALITY_DESC_TOKENS_ASC_LATENCY_ASC_ID_ASC_V1","NO_ADOPTION_HOLDOUT_REQUIRED",costs(root,sealed));
    }
    private boolean dominates(Score a,Score b) {
        long aq=(long)a.passed()*b.denominator(),bq=(long)b.passed()*a.denominator();
        return aq>=bq&&a.totalTokens()<=b.totalTokens()&&a.totalLatencyMicros()<=b.totalLatencyMicros()&&(aq>bq||a.totalTokens()<b.totalTokens()||a.totalLatencyMicros()<b.totalLatencyMicros());
    }
    Costs costs(long root,boolean current) {
        var rows=db.queryForList("select * from budget_reservations where task_key=? order by id"+(current?" for update":""),"team-evolution:"+root);
        int settled=0,unknown=0,pending=0,violations=0;long in=0,out=0;boolean missing=false;
        for(var r:rows) {
            String state=(String)r.get("state");if("SETTLED".equals(state))settled++;else if("UNKNOWN".equals(state))unknown++;else if("VIOLATION".equals(state))violations++;else pending++;
            if(r.get("input_tokens")==null||r.get("output_tokens")==null)missing=true;
            else {in=Math.addExact(in,number(r,"input_tokens"));out=Math.addExact(out,number(r,"output_tokens"));}
        }
        var amounts=db.queryForList("select reserved_amount from budget_accounts where scope_type='TASK' and scope_key=? and unit='OUTPUT_TOKENS'"+(current?" for update":""),Long.class,"team-evolution:"+root);
        long reserved=0;for(long amount:amounts)reserved=Math.addExact(reserved,amount);
        return new Costs(rows.size(),settled,unknown,pending,violations,in,out,missing,reserved);
    }
}
