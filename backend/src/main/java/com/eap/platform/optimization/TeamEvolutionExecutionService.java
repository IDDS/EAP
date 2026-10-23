package com.eap.platform.optimization;

import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.eap.platform.experiment.AgentExperimentClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Bounded synchronous adapter. No takeover, retries, search or adoption. */
@Service
public class TeamEvolutionExecutionService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TeamEvolutionService contracts;
    private final TeamEvolutionAdmissionService admission;
    private final AgentExperimentClient client;
    private final TransactionTemplate tx;
    @Value("${eap.team-evolution.slot-execution-enabled:false}") boolean enabled;
    @Value("${eap.team-evolution.scoring-enabled:false}") boolean scoringEnabled;
    public TeamEvolutionExecutionService(JdbcTemplate db,ObjectMapper json,TeamEvolutionService contracts,
            TeamEvolutionAdmissionService admission,AgentExperimentClient client,PlatformTransactionManager manager) {
        this.db=db;this.json=json;this.contracts=contracts;this.admission=admission;this.client=client;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public record Result(String evaluationId,String workflowKey,String state,String reasonCode,int dispatchedSlots,
                         int confirmedSlots,boolean scoringAvailable,boolean adoptionAvailable) {}
    private record Claim(String holder,Instant deadline,boolean acquired) {}
    private record Send(String operation,AgentExperimentClient.StatelessDispatch dispatch) {}
    private Map<String,Object> execution(String id,boolean lock) {
        var rows=db.queryForList("select * from team_evolution_executions where evaluation_id=?"+(lock?" for update":""),id);
        if(rows.isEmpty())throw new BusinessException("TEAM_EXECUTION_NOT_FOUND");return rows.getFirst();
    }
    public Result get(long root,String evaluation) {
        var e=contracts.evaluation(root,evaluation);var x=execution(e.id(),false);
        return new Result(e.id(),(String)x.get("workflow_key"),(String)x.get("state"),(String)x.get("reason_code"),
                db.queryForObject("select count(*) from team_evolution_dispatches where evaluation_id=?",Integer.class,e.id()),
                db.queryForObject("select count(*) from team_evolution_dispatches where evaluation_id=? and state='SUCCEEDED'",Integer.class,e.id()),scoringEnabled&&contracts.contractsEnabled(),false);
    }
    private boolean live(Map<String,Object> x,String holder) {
        return "RUNNING".equals(x.get("state"))&&holder.equals(x.get("holder"))&&((Timestamp)x.get("deadline")).toInstant().isAfter(Instant.now());
    }
    public Result execute(long root,String evaluation,JsonNode request,String actor) {
        if(!enabled||!contracts.contractsEnabled())throw new BusinessException("TEAM_SLOT_EXECUTION_DISABLED");
        contracts.fields(request,"requestKey");String key=contracts.key(request,"requestKey"),fp=contracts.hash(request);
        String id=evaluation.toLowerCase(Locale.ROOT);
        Claim claim=tx.execute(status->{
            var c=contracts.contract(root,true);contracts.owner(c,actor);contracts.evaluation(root,id);
            var existing=db.queryForList("select * from team_evolution_executions where evaluation_id=? for update",id);
            if(!existing.isEmpty()) {
                var x=existing.getFirst();
                if(!key.equals(x.get("request_key"))||!fp.equals(x.get("fingerprint")))throw new ConflictException("TEAM_ORIGINAL_EXECUTION_REQUIRED");
                if("RUNNING".equals(x.get("state"))&&!((Timestamp)x.get("deadline")).toInstant().isAfter(Instant.now()))stop(id,(String)x.get("holder"),"UNKNOWN","RESPONSIBILITY_EXPIRED");
                return new Claim((String)x.get("holder"),((Timestamp)x.get("deadline")).toInstant(),false);
            }
            contracts.evaluationOpen(root,contracts.evaluation(root,id));
            var p=db.queryForList("select approval_expires_at from team_evolution_budget_plans where contract_id=? for update",root);
            if(p.size()!=1)throw new BusinessException("TEAM_APPROVED_PLAN_REQUIRED");Instant until=((Timestamp)p.getFirst().get("approval_expires_at")).toInstant();
            if(!until.isAfter(Instant.now()))throw new BusinessException("TEAM_APPROVAL_EXPIRED");
            if(db.queryForObject("select count(*) from team_evolution_slots where evaluation_id=? and state<>'NOT_DISPATCHED'",Integer.class,id)!=0)throw new ConflictException("TEAM_ORIGINAL_SLOTS_REQUIRED");
            String holder=UUID.randomUUID().toString();
            db.update("insert into team_evolution_executions(evaluation_id,request_key,fingerprint,principal_digest,workflow_key,holder,deadline,state,started_at) values(?,?,?,?,?,?,?,'RUNNING',CURRENT_TIMESTAMP(6))",id,key,fp,contracts.principal(actor),"team-evaluation:"+id,holder,Timestamp.from(until));
            db.update("update team_evolution_evaluations set state='RUNNING' where id=? and state='NOT_DISPATCHED'",id);
            return new Claim(holder,until,true);
        });
        if(!claim.acquired())return get(root,id);
        try {
            while(true) {
                // The original sample responsibility starts BEFORE its first readiness HTTP.
                boolean pending=Boolean.TRUE.equals(tx.execute(status->beginSample(id,claim)));
                if(!pending){tx.execute(status->prepare(root,id,claim,json.createArrayNode()));break;}
                // No transaction spans either readiness HTTP or the actual one-shot send.
                var bindings=admission.verifyExecution(root,actor);
                Send send=tx.execute(status->prepare(root,id,claim,bindings));
                if(send==null)break;
                AgentExperimentClient.Receipt receipt;
                try {receipt=client.executeStateless(send.dispatch());}
                catch(RuntimeException ex){receipt=new AgentExperimentClient.Receipt("UNKNOWN","RECEIPT_UNKNOWN",null);}
                var received=receipt;
                boolean confirmed=Boolean.TRUE.equals(tx.execute(status->receive(root,id,claim,send,received)));
                if(!confirmed)break;
            }
        }catch(RuntimeException ex) {
            // Preparation can be rejected without spending; never retain exception/provider text.
            tx.executeWithoutResult(status->{
                var x=execution(id,true);
                boolean sent=db.queryForObject("select count(*) from team_evolution_dispatches where evaluation_id=? and state='DISPATCHED'",Integer.class,id)>0;
                if("RUNNING".equals(x.get("state")))stop(id,claim.holder(),sent?"UNKNOWN":"FAILED",sent?"RECEIPT_UNKNOWN":"PRE_DISPATCH_REJECTED");
            });
        }
        return get(root,id);
    }
    private boolean beginSample(String id,Claim claim) {
        var x=execution(id,true);if(!live(x,claim.holder()))return false;
        var slots=db.queryForList("select * from team_evolution_slots where evaluation_id=? order by sample_id,role_ordinal for update",id);
        order(slots);
        var next=slots.stream().filter(s->!"SUCCEEDED".equals(s.get("state"))).findFirst();if(next.isEmpty())return false;
        var s=next.get();
        if(((Number)s.get("role_ordinal")).intValue()==0&&"NOT_DISPATCHED".equals(s.get("state"))) {
            var prior=db.queryForList("select holder from team_evolution_sample_responsibilities where evaluation_id=? and sample_id=?",id,s.get("sample_id"));
            if(prior.isEmpty())db.update("insert into team_evolution_sample_responsibilities(evaluation_id,sample_id,holder,started_at) values(?,?,?,CURRENT_TIMESTAMP(6))",id,s.get("sample_id"),claim.holder());
            else if(!claim.holder().equals(prior.getFirst().get("holder")))throw new ConflictException("TEAM_ORIGINAL_SAMPLE_RESPONSIBILITY_REQUIRED");
        }
        return true;
    }
    private Send prepare(long root,String id,Claim claim,JsonNode bindings) {
        var x=execution(id,true);
        if(!live(x,claim.holder())){if("RUNNING".equals(x.get("state")))stop(id,claim.holder(),"UNKNOWN","RESPONSIBILITY_EXPIRED");return null;}
        var slots=db.queryForList("select * from team_evolution_slots where evaluation_id=? order by sample_id,role_ordinal for update",id);
        order(slots);
        if(slots.stream().allMatch(s->"SUCCEEDED".equals(s.get("state")))) {
            db.update("update team_evolution_executions set state='RECEIPTS_COMPLETE',finished_at=CURRENT_TIMESTAMP(6) where evaluation_id=? and holder=? and state='RUNNING'",id,claim.holder());
            db.update("update team_evolution_evaluations set state='RECEIPTS_COMPLETE' where id=?",id);return null;
        }
        var slot=slots.stream().filter(s->!"SUCCEEDED".equals(s.get("state"))).findFirst().orElseThrow();
        if(!"NOT_DISPATCHED".equals(slot.get("state")))throw new ConflictException("TEAM_SLOT_ALREADY_DISPATCHED");
        var c=contracts.contract(root,false);var protocol=contracts.tree((String)c.get("protocol_json"));
        int ordinal=((Number)slot.get("role_ordinal")).intValue();var role=protocol.path("roles").path(ordinal);
        String input=null;
        if(ordinal==0){for(var sample:contracts.tree((String)c.get("dataset_json")))if(sample.path("id").asText().equals(slot.get("sample_id")))input=sample.path("input").asText();}
        else {
            var previous=db.queryForList("select d.output_json,d.state from team_evolution_slots s join team_evolution_dispatches d on d.operation_key=s.operation_key where s.evaluation_id=? and s.sample_id=? and s.role_ordinal=?",id,slot.get("sample_id"),ordinal-1);
            if(previous.size()!=1||!"SUCCEEDED".equals(previous.getFirst().get("state")))throw new BusinessException("TEAM_COMPLETE_HANDOFF_REQUIRED");
            input=contracts.tree((String)previous.getFirst().get("output_json")).asText();
        }
        if(input==null||input.isBlank()||input.getBytes(StandardCharsets.UTF_8).length>2000)throw new BusinessException("TEAM_HANDOFF_INPUT_LIMIT");
        String config=db.queryForObject("select configuration_json from team_evolution_candidates where contract_id=? and candidate_id=(select candidate_id from team_evolution_evaluations where id=?)",String.class,root,id);
        String prompt=contracts.tree(config).path("rolePrompts").path(role.path("role").asText()).asText();
        var body=new LinkedHashMap<String,Object>();body.put("taskKey",slot.get("operation_key"));body.put("traceId",slot.get("trace_id"));body.put("profileDigest",bindings.path(ordinal).path("profileDigest").asText());body.put("systemPrompt",prompt);body.put("input",input);body.put("maxTokens",protocol.path("maxTokens").asInt());
        if(contracts.encode(body).getBytes(StandardCharsets.UTF_8).length>16384)throw new BusinessException("TEAM_RUNNER_BODY_LIMIT");
        var model=json.createObjectNode().put("model",role.path("model").asText()).put("max_tokens",protocol.path("maxTokens").asInt()).put("temperature",0).put("stream",false);
        model.set("messages",json.createArrayNode().add(json.createObjectNode().put("role","system").put("content",prompt)).add(json.createObjectNode().put("role","user").put("content",input)));
        if(db.update("update team_evolution_slots set state='DISPATCHED' where operation_key=? and state='NOT_DISPATCHED'",slot.get("operation_key"))!=1)throw new ConflictException("TEAM_SLOT_ALREADY_DISPATCHED");
        db.update("insert into team_evolution_dispatches(operation_key,evaluation_id,trace_id,agent_id,runtime_id,instance_key,actor_uid,actor_template,endpoint,profile_digest,body_json,model_request_digest,holder,state,started_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?,'DISPATCHED',CURRENT_TIMESTAMP(6))",slot.get("operation_key"),id,slot.get("trace_id"),role.path("agentId").asLong(),role.path("runtimeId").asLong(),role.path("instanceKey").asText(),role.path("actorUid").asText(),role.path("actorTemplate").asText(),role.path("endpoint").asText(),bindings.path(ordinal).path("profileDigest").asText(),contracts.encode(body),TeamEvolutionDigest.of(json,model),claim.holder());
        return new Send((String)slot.get("operation_key"),new AgentExperimentClient.StatelessDispatch(role.path("endpoint").asText(),role.path("actorUid").asText(),role.path("actorTemplate").asText(),bindings.path(ordinal).path("profileDigest").asText(),Collections.unmodifiableMap(body),claim.deadline()));
    }
    private void order(List<Map<String,Object>> slots) {
        slots.sort(Comparator.comparing((Map<String,Object> s)->(String)s.get("sample_id")).thenComparingInt(s->((Number)s.get("role_ordinal")).intValue()));
    }
    private boolean receive(long root,String id,Claim claim,Send send,AgentExperimentClient.Receipt receipt) {
        var x=execution(id,true);
        if(!live(x,claim.holder())){if("RUNNING".equals(x.get("state")))stop(id,claim.holder(),"UNKNOWN","RESPONSIBILITY_EXPIRED");return false;}
        var d=db.queryForMap("select * from team_evolution_dispatches where operation_key=? for update",send.operation());
        var body=receipt==null?null:receipt.body();
        var reservations=d.get("reservation_id")==null?List.<Map<String,Object>>of():db.queryForList("select * from budget_reservations where id=?",d.get("reservation_id"));
        boolean valid=receipt!=null&&"SUCCEEDED".equals(receipt.status())&&body!=null&&"SUCCEEDED".equals(body.path("status").asText())
                &&Objects.equals(d.get("trace_id"),body.path("traceId").asText())&&body.path("output").isTextual()&&body.path("output").asText().length()<=16000
                &&Objects.equals(d.get("gateway_finish_reason"),body.path("finishReason").asText())&&TeamEvolutionDigest.of(json,body.path("output")).equals(d.get("gateway_output_digest"))
                &&reservations.size()==1&&"SETTLED".equals(reservations.getFirst().get("state"))&&Objects.equals(d.get("trace_id"),reservations.getFirst().get("trace_id"))
                &&Objects.equals(d.get("agent_id"),reservations.getFirst().get("agent_id"))&&("team-evolution:"+root).equals(reservations.getFirst().get("task_key"))
                &&"DISPATCHED".equals(d.get("state"))&&claim.holder().equals(d.get("holder"));
        if(!valid){stop(id,claim.holder(),"UNKNOWN","GATEWAY_RECEIPT_UNCONFIRMED");return false;}
        db.update("update team_evolution_dispatches set state='SUCCEEDED',output_json=?,finished_at=CURRENT_TIMESTAMP(6) where operation_key=? and holder=? and state='DISPATCHED'",contracts.encode(body.path("output")),send.operation(),claim.holder());
        db.update("update team_evolution_slots set state='SUCCEEDED' where operation_key=? and state='DISPATCHED'",send.operation());return true;
    }
    private void stop(String id,String holder,String state,String reason) {
        int changed=db.update("update team_evolution_executions set state=?,reason_code=?,finished_at=CURRENT_TIMESTAMP(6) where evaluation_id=? and holder=? and state='RUNNING'",state,reason,id,holder);
        if(changed!=1)return;
        db.update("update team_evolution_dispatches set state='UNKNOWN',finished_at=CURRENT_TIMESTAMP(6) where evaluation_id=? and holder=? and state='DISPATCHED'",id,holder);
        db.update("update team_evolution_slots set state='UNKNOWN' where evaluation_id=? and state='DISPATCHED'",id);
        db.update("update team_evolution_evaluations set state=? where id=?",state,id);
    }
}
