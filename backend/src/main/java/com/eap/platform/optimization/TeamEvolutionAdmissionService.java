package com.eap.platform.optimization;

import com.eap.platform.agent.AgentService;
import com.eap.platform.budget.BudgetLedger;
import com.eap.platform.common.BusinessException;
import com.eap.platform.common.ConflictException;
import com.eap.platform.experiment.AgentExperimentClient;
import com.eap.platform.registry.AgentRuntimeInstanceRepository;
import com.eap.platform.registry.RuntimeConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Prospective budget and read-only readiness. This service cannot dispatch a slot. */
@Service
public class TeamEvolutionAdmissionService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TeamEvolutionService contracts;
    private final BudgetLedger budgets;
    private final AgentService agents;
    private final AgentRuntimeInstanceRepository runtimes;
    private final RuntimeConnectionService connections;
    private final AgentExperimentClient client;
    @Value("${eap.team-evolution.budget-enabled:false}") boolean enabled;
    @Value("${eap.team-evolution.model-proposals-enabled:false}") boolean proposalsEnabled;
    public TeamEvolutionAdmissionService(JdbcTemplate db,ObjectMapper json,TeamEvolutionService contracts,BudgetLedger budgets,
            AgentService agents,AgentRuntimeInstanceRepository runtimes,RuntimeConnectionService connections,AgentExperimentClient client) {
        this.db=db;this.json=json;this.contracts=contracts;this.budgets=budgets;this.agents=agents;
        this.runtimes=runtimes;this.connections=connections;this.client=client;
    }
    public record Plan(long contractId,String budgetScope,String businessTaskDigest,long maxCalls,long maxOutputTokens,
                       long requiredCalls,String approvalExpiresAt,String liveBindingsDigest,String state,
                       boolean budgetAllocated,boolean sharedCapacityReserved,boolean executionAvailable,
                       String proposalMode,int maxProposalCalls,int maxProposalTokens) {}
    public record Readiness(long contractId,String checkedAt,String approvalExpiresAt,String liveBindingsDigest,
                            boolean readOnlyChecksPassed,boolean sharedCapacityReserved,boolean executionAvailable) {}
    private void enabled() { if(!enabled||!contracts.contractsEnabled())throw new BusinessException("TEAM_BUDGET_ADMISSION_DISABLED"); }
    private Map<String,Object> plan(long id) {
        var rows=db.queryForList("select * from team_evolution_budget_plans where contract_id=?",id);
        if(rows.isEmpty())throw new BusinessException("TEAM_BUDGET_PLAN_NOT_FOUND");return rows.getFirst();
    }
    private Plan view(Map<String,Object> p) {
        long id=((Number)p.get("contract_id")).longValue();
        return new Plan(id,"team-evolution:"+id,(String)p.get("business_task_digest"),((Number)p.get("max_calls")).longValue(),
                ((Number)p.get("max_output_tokens")).longValue(),((Number)p.get("required_calls")).longValue(),
                ((Timestamp)p.get("approval_expires_at")).toInstant().toString(),contracts.hash(contracts.tree((String)p.get("live_bindings_json"))),
                contracts.status().get("slotExecutionAvailable").equals(true)?"BUDGET_FROZEN_SLOT_ADAPTER_UNSCORED":"BUDGET_FROZEN_NO_EXECUTOR",true,false,false,(String)p.get("proposal_mode"),((Number)p.get("max_proposal_calls")).intValue(),((Number)p.get("max_proposal_tokens")).intValue());
    }
    @Transactional(readOnly=true)
    public Plan get(long id) { contracts.contract(id,false);return view(plan(id)); }
    private List<Long> owners(JsonNode protocol) {
        var result=new ArrayList<Long>();protocol.path("roles").forEach(r->result.add(r.path("agentId").asLong()));return result;
    }
    private void deadline(Instant until) {
        if(!until.isAfter(Instant.now()))throw new BusinessException("TEAM_APPROVAL_EXPIRED");
    }
    @Transactional
    public Plan freeze(long id,JsonNode request,String actor) {
        enabled();
        boolean model = "MODEL_SINGLE_ROLE".equals(request.path("proposalMode").asText());
        if(model) contracts.fields(request,"requestKey","planKey","businessTask","independentBudgetConfirmed","maxCalls","maxOutputTokens","approvalExpiresAt","proposalMode","proposalUseConfirmed","maxProposalCalls","maxProposalTokens");
        else contracts.fields(request,"requestKey","planKey","businessTask","independentBudgetConfirmed","maxCalls","maxOutputTokens","approvalExpiresAt","proposalMode");
        String key=contracts.key(request,"requestKey"),planKey=contracts.hash(contracts.key(request,"planKey")),fingerprint=contracts.hash(request);
        db.queryForObject("select id from team_evolution_admission where id=1 for update",Long.class);
        var root=contracts.contract(id,true);contracts.owner(root,actor);
        var prior=db.queryForList("select * from team_evolution_budget_plans where contract_id=?",id);
        if(!prior.isEmpty()) {
            var p=prior.getFirst();
            if(!key.equals(p.get("request_key"))||!fingerprint.equals(p.get("fingerprint")))throw new ConflictException("TEAM_ORIGINAL_BUDGET_PLAN_REQUIRED");
            return view(p); // Original metadata remains readable after expiry/revocation; no renewed allowance.
        }
        contracts.rootOpen(id);
        if(!db.queryForList("select contract_id from team_evolution_budget_plans where plan_key=?",planKey).isEmpty())throw new ConflictException("TEAM_BUDGET_PLAN_ALREADY_BOUND");
        if(!request.path("independentBudgetConfirmed").isBoolean()||!request.path("independentBudgetConfirmed").asBoolean())throw new BusinessException("TEAM_INDEPENDENT_BUDGET_CONFIRMATION_REQUIRED");
        if(!model&&!"OFFLINE_ONLY".equals(request.path("proposalMode").asText()))throw new BusinessException("TEAM_ONLY_OFFLINE_PROPOSALS_SUPPORTED");
        int proposalCalls=0,proposalTokens=0;
        if(model) {
            if(!proposalsEnabled)throw new BusinessException("TEAM_MODEL_PROPOSALS_DISABLED");
            if(!request.path("proposalUseConfirmed").isBoolean()||!request.path("proposalUseConfirmed").asBoolean())throw new BusinessException("TEAM_PROPOSAL_USE_CONFIRMATION_REQUIRED");
            proposalCalls=contracts.bounded(request,"maxProposalCalls",1,2);
            proposalTokens=contracts.bounded(request,"maxProposalTokens",64,512);
            if(proposalCalls>((Number)root.get("max_attempts")).intValue()-((Number)root.get("attempts")).intValue())throw new BusinessException("TEAM_ATTEMPT_LIMIT_REACHED");
        }
        String task=contracts.string(request,"businessTask",2000);
        long calls=contracts.bounded(request,"maxCalls",1,128),tokens=contracts.bounded(request,"maxOutputTokens",1,32768);
        Instant until;
        try {until=Instant.parse(contracts.string(request,"approvalExpiresAt",40));if(until.getNano()%1000!=0)throw new IllegalArgumentException();}
        catch(RuntimeException ex){throw new BusinessException("TEAM_APPROVAL_DEADLINE_REJECTED");}
        Instant now=Instant.now();
        if(until.isBefore(now.plusSeconds(30))||until.isAfter(now.plusSeconds(900)))throw new BusinessException("TEAM_APPROVAL_DEADLINE_REJECTED");
        var protocol=contracts.tree((String)root.get("protocol_json"));
        var samples=contracts.tree((String)root.get("dataset_json"));long validation=0,holdout=0;
        for(var sample:samples){if("VALIDATION".equals(sample.path("split").asText()))validation++;if("HOLDOUT".equals(sample.path("split").asText()))holdout++;}
        // Every bounded candidate (including baseline) plus independent baseline/selected holdout.
        long required=2*(validation*((Number)root.get("max_candidates")).longValue()+2*holdout)+proposalCalls;
        long requiredTokens=(calls-proposalCalls)*protocol.path("maxTokens").asLong()+(long)proposalCalls*proposalTokens;
        if(calls<required||tokens<requiredTokens)throw new BusinessException("TEAM_COMPLETE_PLAN_BUDGET_REQUIRED");
        var owners=owners(protocol);budgets.requireTeamCapacity(owners,calls,tokens);
        var observed=inspect(id,root,protocol,null,until);
        db.update("insert into team_evolution_budget_plans(contract_id,plan_key,request_key,fingerprint,principal_digest,business_task_json,business_task_digest,max_calls,max_output_tokens,required_calls,approval_expires_at,live_bindings_json,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                id,planKey,key,fingerprint,contracts.principal(actor),contracts.encode(task),contracts.hash(task),calls,tokens,required,Timestamp.from(until),contracts.encode(observed));
        db.update("update team_evolution_budget_plans set proposal_mode=?,max_proposal_calls=?,max_proposal_tokens=? where contract_id=?",model?"MODEL_SINGLE_ROLE":"OFFLINE_ONLY",proposalCalls,proposalTokens,id);
        budgets.freezeTeamBudget(id,owners,calls,tokens,"team-principal:"+contracts.principal(actor));deadline(until);
        return view(plan(id));
    }
    @Transactional(readOnly=true)
    public Readiness check(long id,JsonNode request,String actor) {
        enabled();contracts.fields(request);var root=contracts.contract(id,false);contracts.owner(root,actor);var p=plan(id);
        Instant until=((Timestamp)p.get("approval_expires_at")).toInstant();deadline(until);
        var protocol=contracts.tree((String)root.get("protocol_json"));
        budgets.requireTeamCapacity(owners(protocol),((Number)p.get("max_calls")).longValue(),((Number)p.get("max_output_tokens")).longValue());
        var observed=inspect(id,root,protocol,contracts.tree((String)p.get("live_bindings_json")),until);
        requireFrozenAccounts(id,p);deadline(until);
        return new Readiness(id,Instant.now().toString(),until.toString(),contracts.hash(observed),true,false,false);
    }
    /** Per-send check. Actual shared capacity is reserved atomically by the Gateway. */
    public JsonNode verifyExecution(long id,String actor) {
        enabled();var root=contracts.contract(id,false);contracts.owner(root,actor);var p=plan(id);
        Instant until=((Timestamp)p.get("approval_expires_at")).toInstant();deadline(until);
        var result=inspect(id,root,contracts.tree((String)root.get("protocol_json")),contracts.tree((String)p.get("live_bindings_json")),until);
        requireFrozenAccounts(id,p,true);deadline(until);return result;
    }
    private void requireFrozenAccounts(long id,Map<String,Object> p) {requireFrozenAccounts(id,p,false);}
    private void requireFrozenAccounts(long id,Map<String,Object> p,boolean execution) {
        var accounts=db.queryForList("select unit,limit_amount,margin_amount,revision,used_amount,reserved_amount,violated from budget_accounts where scope_type='TASK' and scope_key=?","team-evolution:"+id);
        if(accounts.size()!=3)throw new BusinessException("TEAM_FROZEN_BUDGET_UNCONFIRMED");
        for(var a:accounts) {
            long expected=switch((String)a.get("unit")){case "MODEL_CALLS"->((Number)p.get("max_calls")).longValue();case "OUTPUT_TOKENS"->((Number)p.get("max_output_tokens")).longValue();case "TOOL_CLAIMS"->0;default->-1;};
            if(a.get("limit_amount")==null||((Number)a.get("limit_amount")).longValue()!=expected||((Number)a.get("margin_amount")).longValue()!=0||((Number)a.get("revision")).longValue()!=1
                    ||(!execution&&(new java.math.BigDecimal(a.get("used_amount").toString()).signum()!=0||new java.math.BigDecimal(a.get("reserved_amount").toString()).signum()!=0))||Boolean.TRUE.equals(a.get("violated")))
                throw new BusinessException("TEAM_FROZEN_BUDGET_UNCONFIRMED");
        }
    }
    private JsonNode inspect(long id,Map<String,Object> root,JsonNode protocol,JsonNode frozen,Instant until) {
        var baseline=db.queryForObject("select configuration_json from team_evolution_candidates where contract_id=? and candidate_id=?",String.class,id,root.get("baseline_id"));
        var prompts=contracts.tree(baseline).path("rolePrompts");var observed=json.createArrayNode();int ordinal=0;
        for(var role:protocol.path("roles")) {
            deadline(until);requireBinding(role);
            String generation=connections.inspectionGeneration(role.path("instanceKey").asText());
            if(generation==null||generation.isBlank())throw new BusinessException("TEAM_IDENTITY_GENERATION_UNCONFIRMED");
            AgentExperimentClient.RuntimeProfile live;
            try {live=client.inspectProfile(role.path("endpoint").asText());}
            catch(RuntimeException ex){throw new BusinessException("TEAM_LIVE_PROFILE_UNCONFIRMED");}
            if(live==null||!role.path("actorUid").asText().equals(live.actorUid())||!role.path("actorTemplate").asText().equals(live.template()))throw new BusinessException("TEAM_LIVE_IDENTITY_CHANGED");
            var p=live.profile();String name=role.path("role").asText();
            if(p==null||!"EAP_STATELESS_PROMPT_V1".equals(p.path("protocol").asText())||!"EMPTY".equals(p.path("context").asText())||!"DISABLED".equals(p.path("tools").asText())
                    ||!role.path("agentId").asText().equals(p.path("agentId").asText())||!role.path("model").asText().equals(p.path("model").asText())
                    ||!prompts.path(name).asText().equals(p.path("systemPrompt").asText())||!p.path("profileDigest").asText().matches("[a-f0-9]{64}")
                    ||!p.path("maxTokens").isIntegralNumber()||!p.path("maxTokens").canConvertToInt()||p.path("maxTokens").asInt()<protocol.path("maxTokens").asInt()||p.path("maxTokens").asInt()>8192
                    ||!p.path("temperature").isNumber()||p.path("temperature").asDouble()!=0)throw new BusinessException("TEAM_LIVE_PROFILE_CHANGED");
            // Store allowlisted digests only, never arbitrary runtime fields or exception text.
            var selected=json.createObjectNode();for(String field:List.of("protocol","agentId","model","systemPrompt","maxTokens","context","tools","temperature"))selected.set(field,p.get(field));
            var binding=json.createObjectNode().put("role",name).put("actorUid",live.actorUid()).put("actorTemplate",live.template())
                    .put("identityGeneration",generation).put("profileDigest",p.path("profileDigest").asText()).put("profileContentDigest",contracts.hash(selected));
            if(frozen!=null&&!binding.equals(frozen.path(ordinal)))throw new BusinessException("TEAM_FROZEN_LIVE_PROFILE_CHANGED");
            observed.add(binding);ordinal++;requireBinding(role);deadline(until);
            if(!generation.equals(connections.inspectionGeneration(role.path("instanceKey").asText())))throw new BusinessException("TEAM_IDENTITY_GENERATION_CHANGED");
        }
        ordinal=0;for(var role:protocol.path("roles")) {
            requireBinding(role);
            if(!observed.path(ordinal++).path("identityGeneration").asText().equals(connections.inspectionGeneration(role.path("instanceKey").asText())))throw new BusinessException("TEAM_IDENTITY_GENERATION_CHANGED");
        }
        return observed;
    }
    private void requireBinding(JsonNode role) {
        long agent=role.path("agentId").asLong();
        if(!"ACTIVE".equals(agents.get(agent).lifecycleStatus().name()))throw new BusinessException("TEAM_AGENT_DISABLED");
        var runtime=runtimes.findByInstanceKey(role.path("instanceKey").asText()).orElseThrow(()->new BusinessException("TEAM_RUNTIME_UNCONFIRMED"));
        var rows=db.queryForList("select id,agent_id,endpoint,protocol,status,platform_actor_uid,platform_actor_template,imported_actor_uid from agent_runtime_instances where instance_key=?",role.path("instanceKey").asText());
        if(rows.size()!=1)throw new BusinessException("TEAM_RUNTIME_UNCONFIRMED");var r=rows.getFirst();
        if(((Number)r.get("id")).longValue()!=role.path("runtimeId").asLong()||((Number)r.get("agent_id")).longValue()!=agent||!"SUBSTRATE".equals(r.get("protocol"))||"OFFLINE".equals(r.get("status"))
                ||r.get("imported_actor_uid")!=null||!role.path("endpoint").asText().equals(r.get("endpoint"))||!role.path("actorUid").asText().equals(r.get("platform_actor_uid"))
                ||!role.path("actorTemplate").asText().equals(r.get("platform_actor_template"))||!connections.executable(runtime))throw new BusinessException("TEAM_RUNTIME_UNCONFIRMED");
        var created=db.queryForList("select actor_uid,deployment_key,revoked from platform_runtime_creations where instance_key=?",role.path("instanceKey").asText());
        if(created.size()!=1||!Boolean.FALSE.equals(created.getFirst().get("revoked"))||!role.path("actorUid").asText().equals(created.getFirst().get("actor_uid"))
                ||!role.path("deploymentKey").asText().equals(created.getFirst().get("deployment_key")))throw new BusinessException("TEAM_CREATION_UNCONFIRMED");
        var deployments=db.queryForList("select * from substrate_agent_deployments where deployment_key=?",role.path("deploymentKey").asText());
        if(deployments.size()!=1)throw new BusinessException("TEAM_DEPLOYMENT_UNCONFIRMED");var d=deployments.getFirst();
        if(!"READY".equals(d.get("status"))||!"RUNNER".equals(d.get("source_type"))||((Number)d.get("agent_id")).longValue()!=agent||((Number)d.get("version")).longValue()!=role.path("deploymentVersion").asLong()
                ||!role.path("actorTemplate").asText().equals(d.get("atespace")+"/"+d.get("template_name"))||!role.path("imageRef").asText().equals(d.get("image_ref"))
                ||!role.path("configDigest").asText().equals(contracts.hash(contracts.tree((String)d.get("config_json")))))throw new BusinessException("TEAM_DEPLOYMENT_UNCONFIRMED");
    }
}
