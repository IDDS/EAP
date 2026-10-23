package com.eap.platform.optimization;

import com.eap.platform.agent.*;
import com.eap.platform.budget.BudgetLedger;
import com.eap.platform.common.*;
import com.eap.platform.experiment.AgentExperimentClient;
import com.eap.platform.gateway.OpenAiUpstreamClient;
import com.eap.platform.registry.*;
import com.eap.platform.workflow.WorkflowTaskClient;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.team-evolution.contracts-enabled=true","eap.team-evolution.budget-enabled=true",
        "eap.budget.enabled=true","eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid/realm",
        "eap.security.jwk-set-uri=https://idp.invalid/jwks","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class TeamEvolutionAdmissionIntegrationTests {
    @Autowired TeamEvolutionService contracts; @Autowired TeamEvolutionAdmissionService admission;
    @Autowired BudgetLedger budgets; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    @Autowired AgentService agents; @Autowired MockMvc mvc;
    @MockitoBean RuntimeConnectionService connections; @MockitoBean AgentExperimentClient client;
    @MockitoBean OpenAiUpstreamClient upstream; @MockitoBean WorkflowTaskClient workflow;
    long first,second;String firstInstance,secondInstance;
    @BeforeEach void setup() {
        db.update("delete from team_evolution_closures");db.update("delete from team_evolution_holdouts");db.update("delete from team_evolution_frontiers");db.update("delete from team_evolution_scores");db.update("delete from team_evolution_sample_responsibilities");
        db.update("delete from team_evolution_dispatches");db.update("delete from team_evolution_executions");
        RequestContextHolder.resetRequestAttributes();
        db.update("delete from team_evolution_proposals");db.update("delete from team_evolution_budget_plans");
        for(String table:List.of("budget_limit_changes","budget_settlements","budget_reservation_items","budget_reservations","budget_accounts","team_evolution_slots","team_evolution_evaluations","team_evolution_attempts"))db.update("delete from "+table);
        for(int n=8;n>=0;n--)db.update("delete from team_evolution_candidates where generation=?",n);db.update("delete from team_evolution_contracts");
        when(connections.executable(any())).thenReturn(true);
        when(connections.inspectionGeneration(anyString())).thenAnswer(i->"generation-"+i.getArgument(0));
        first=fixture("extractor");second=fixture("classifier");firstInstance="eap-agents/budget-team-"+first;secondInstance="eap-agents/budget-team-"+second;
        when(client.inspectProfile(any())).thenAnswer(i->profile(i.getArgument(0)));
    }
    @AfterEach void noExecution() {
        verify(client,never()).execute(any());verify(client,never()).profile(any());
        verify(connections,never()).dispatchToken(any());verifyNoInteractions(upstream,workflow);
        assertThat(db.queryForObject("select count(*) from budget_reservations",Long.class)).isZero();
        assertThat(db.queryForObject("select count(*) from team_evolution_slots where state<>'NOT_DISPATCHED'",Long.class)).isZero();
        RequestContextHolder.resetRequestAttributes();
    }
    long fixture(String role) {
        long agent=agents.create(new AgentDtos.AgentRequest("TEAM-BUDGET-"+UUID.randomUUID(),"Team admission","","Test","Owner","Test",RiskLevel.LOW)).id();agents.updateStatus(agent,AgentLifecycleStatus.ACTIVE);
        String instance="eap-agents/budget-team-"+agent,template="budget-team-template-"+agent,uid="budget-team-uid-"+agent;
        db.update("insert into agent_runtime_instances(agent_id,instance_key,endpoint,protocol,dispatch_authenticated,status,load_score,platform_actor_uid,platform_actor_template,created_at,updated_at) values(?,?,?,'SUBSTRATE',true,'ONLINE',0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",agent,instance,"substrate://"+instance,uid,"eap-agents/"+template);
        String config=contracts.encode(Map.of("sourceType","RUNNER","systemPrompt",role+" baseline","model","test-model"));
        db.update("insert into substrate_agent_deployments(deployment_key,agent_id,version,source_type,status,config_json,image_ref,atespace,template_name,instance_key,steps_json,requested_by,created_at,updated_at) values(?,?,1,'RUNNER','READY',?,?,'eap-agents',?,?,'[]','fixture',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","budget-team-dep-"+agent,agent,config,"registry.test/runner@sha256:"+"b".repeat(64),template,instance);
        db.update("insert into platform_runtime_creations(agent_id,request_key,deployment_key,instance_key,actor_uid,revoked,attempted,delivery_request) values(?,?,?,?,?,false,true,?)",agent,"creation-"+agent,"budget-team-dep-"+agent,instance,uid,UUID.randomUUID().toString());return agent;
    }
    AgentExperimentClient.RuntimeProfile profile(String endpoint) {
        long agent=endpoint.endsWith("-"+first)?first:second;String role=agent==first?"extractor":"classifier";
        var p=json.createObjectNode().put("protocol","EAP_STATELESS_PROMPT_V1").put("agentId",Long.toString(agent)).put("model","test-model")
                .put("systemPrompt",role+" baseline").put("maxTokens",256).put("temperature",0).put("tools","DISABLED").put("context","EMPTY").put("profileDigest",(agent==first?"a":"b").repeat(64));
        return new AgentExperimentClient.RuntimeProfile(p,"budget-team-uid-"+agent,"eap-agents/budget-team-template-"+agent);
    }
    ObjectNode contractRequest(String key) {
        var r=json.createObjectNode().put("requestKey",key).put("publicSamplesConfirmed",true).put("maxCandidates",4).put("maxAttempts",6).put("maxGeneration",2).put("minimumQualityBps",10000).put("maxTokens",128);
        r.set("roles",json.createArrayNode().add(json.createObjectNode().put("role","extractor").put("agentId",first).put("instanceKey",firstInstance)).add(json.createObjectNode().put("role","classifier").put("agentId",second).put("instanceKey",secondInstance)));
        var samples=json.createArrayNode();for(String split:List.of("TRAIN","VALIDATION","HOLDOUT"))samples.add(json.createObjectNode().put("id",split+"-1").put("groupId",split+"-g").put("split",split).put("input","public "+split).put("expected","answer "+split));r.set("samples",samples);return r;
    }
    TeamEvolutionService.Summary root(String key){return contracts.submit(contractRequest(key),"owner");}
    ObjectNode request(String key) {
        return json.createObjectNode().put("requestKey",key).put("planKey","independent-"+key).put("businessTask","Public synthetic classification fixture")
                .put("independentBudgetConfirmed",true).put("maxCalls",12).put("maxOutputTokens",1536).put("proposalMode","OFFLINE_ONLY")
                .put("approvalExpiresAt",Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MILLIS).toString());
    }
    long count(String table){return db.queryForObject("select count(*) from "+table,Long.class);}
    void rejected(long id,ObjectNode r,String code) {assertThatThrownBy(()->admission.freeze(id,r,"owner")).hasMessage(code);assertThat(count("team_evolution_budget_plans")).isZero();}

    @Test void replacingMachineEpochCannotReuseAnApprovedPlanEvenWithSameUidAndProfile() {
        var root=root("generation-frozen");admission.freeze(root.id(),request("generation-frozen"),"owner");
        when(connections.inspectionGeneration(firstInstance)).thenReturn("replacement-generation");
        assertThatThrownBy(()->admission.check(root.id(),json.createObjectNode(),"owner")).hasMessage("TEAM_FROZEN_LIVE_PROFILE_CHANGED");
    }
    @Test void machineEpochChangingDuringProfileReadRollsBackApproval() {
        var root=root("generation-race");when(connections.inspectionGeneration(firstInstance)).thenReturn("original-generation","replacement-generation");
        rejected(root.id(),request("generation-race"),"TEAM_IDENTITY_GENERATION_CHANGED");assertThat(count("budget_accounts")).isZero();
    }
    @Test void completeIndependentPlanFreezesThreeAccountsAndReadOnlyCheckDoesNotReserveOrDispatch() {
        var root=root("freeze");var r=request("freeze");var plan=admission.freeze(root.id(),r,"owner");
        assertThat(plan.requiredCalls()).isEqualTo(12);assertThat(plan.budgetAllocated()).isTrue();assertThat(plan.executionAvailable()).isFalse();assertThat(plan.sharedCapacityReserved()).isFalse();
        assertThat(db.queryForList("select limit_amount from budget_accounts where scope_type='TASK' order by unit",Long.class)).containsExactly(12L,1536L,0L);
        var ready=admission.check(root.id(),json.createObjectNode(),"owner");assertThat(ready.readOnlyChecksPassed()).isTrue();assertThat(ready.executionAvailable()).isFalse();
        assertThat(ready.liveBindingsDigest()).isEqualTo(plan.liveBindingsDigest());assertThat(contracts.get(root.id()).executionState()).isEqualTo("DISABLED_NO_EXECUTOR");
        assertThat(count("budget_limit_changes")).isEqualTo(3);assertThat(db.queryForObject("select sum(used_amount+reserved_amount) from budget_accounts",Long.class)).isZero();
    }
    @Test void originalPlanCanBeReadAfterRevocationAndExpiryWithoutRenewalButCheckRejects() {
        var root=root("original");var r=request("original");var original=admission.freeze(root.id(),r,"owner");
        db.update("update platform_runtime_creations set revoked=true where instance_key=?",firstInstance);clearInvocations(client);
        assertThat(admission.freeze(root.id(),r,"owner")).isEqualTo(original);verifyNoInteractions(client);
        assertThatThrownBy(()->admission.check(root.id(),json.createObjectNode(),"owner")).hasMessage("TEAM_CREATION_UNCONFIRMED");
        db.update("update team_evolution_budget_plans set approval_expires_at=? where contract_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),root.id());
        assertThat(admission.get(root.id()).budgetAllocated()).isTrue();
        assertThatThrownBy(()->admission.check(root.id(),json.createObjectNode(),"owner")).hasMessage("TEAM_APPROVAL_EXPIRED");
        var changed=r.deepCopy();changed.put("approvalExpiresAt",Instant.now().plusSeconds(600).toString());
        assertThatThrownBy(()->admission.freeze(root.id(),changed,"owner")).hasMessage("TEAM_ORIGINAL_BUDGET_PLAN_REQUIRED");assertThat(count("budget_limit_changes")).isEqualTo(3);
    }
    @Test void fullPrincipalIsPreservedWithoutTruncationOrOversizedLedgerActor() {
        String actor="owner-"+"x".repeat(200);var root=contracts.submit(contractRequest("long-owner"),actor);var r=request("long-owner");
        var p=admission.freeze(root.id(),r,actor);assertThat(admission.freeze(root.id(),r,actor)).isEqualTo(p);
        assertThat(db.queryForList("select updated_by from budget_accounts",String.class)).containsOnly("team-principal:"+contracts.principal(actor));
        assertThatThrownBy(()->admission.freeze(root.id(),r,actor.substring(0,128))).hasMessage("TEAM_ORIGINAL_PRINCIPAL_REQUIRED");
    }
    @Test void planCannotBindAnotherContractOrPrincipalAndNewKeyCannotExpandFrozenBudget() {
        var a=root("first");var b=root("second");var r=request("one-plan");admission.freeze(a.id(),r,"owner");
        assertThatThrownBy(()->admission.freeze(b.id(),r,"owner")).hasMessage("TEAM_BUDGET_PLAN_ALREADY_BOUND");
        var alias=r.deepCopy();alias.put("planKey",r.path("planKey").asText().toUpperCase(Locale.ROOT));assertThatThrownBy(()->admission.freeze(b.id(),alias,"owner")).hasMessage("TEAM_BUDGET_PLAN_ALREADY_BOUND");
        assertThatThrownBy(()->admission.freeze(a.id(),r,"foreign")).hasMessage("TEAM_ORIGINAL_PRINCIPAL_REQUIRED");
        assertThatThrownBy(()->admission.freeze(a.id(),request("replacement"),"owner")).hasMessage("TEAM_ORIGINAL_BUDGET_PLAN_REQUIRED");
        assertThatThrownBy(()->admission.check(a.id(),json.createObjectNode(),"foreign")).hasMessage("TEAM_ORIGINAL_PRINCIPAL_REQUIRED");assertThat(count("team_evolution_budget_plans")).isEqualTo(1);
    }
    @Test void wholeValidationAndIndependentHoldoutMustFitAndModelProposalCostsCannotBeOmitted() {
        var root=root("complete-plan");var low=request("low");low.put("maxCalls",11);rejected(root.id(),low,"TEAM_COMPLETE_PLAN_BUDGET_REQUIRED");
        var tokens=request("tokens");tokens.put("maxOutputTokens",1535);rejected(root.id(),tokens,"TEAM_COMPLETE_PLAN_BUDGET_REQUIRED");
        var no=request("no");no.put("independentBudgetConfirmed",false);rejected(root.id(),no,"TEAM_INDEPENDENT_BUDGET_CONFIRMATION_REQUIRED");
        var model=request("model");model.put("proposalMode","MODEL");rejected(root.id(),model,"TEAM_ONLY_OFFLINE_PROPOSALS_SUPPORTED");verifyNoInteractions(client);
    }
    @Test void unknownFieldsInvalidCapsAndExpiredDeadlinesNeverInspectOrAllocate() {
        var root=root("strict");for(String field:List.of("usageSource","score","traceId","actorUid","budgetRevision")){var r=request(field);r.put(field,"forged");rejected(root.id(),r,"TEAM_FIELDS_REJECTED");}
        for(String field:List.of("maxCalls","maxOutputTokens")){var r=request(field);r.put(field,2.5);rejected(root.id(),r,"TEAM_LIMIT_REJECTED");}
        for(long offset:List.of(-1L,10L,901L)){var r=request("deadline"+offset);r.put("approvalExpiresAt",Instant.now().plusSeconds(offset).toString());rejected(root.id(),r,"TEAM_APPROVAL_DEADLINE_REJECTED");}
        verifyNoInteractions(client);assertThat(count("budget_accounts")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"PLATFORM","FIRST","SECOND"})
    void usedReservedAndMarginInEverySharedScopeAreIncludedBeforeAnyRuntimeRead(String scope) {
        var root=root("shared-"+scope);String type=scope.equals("PLATFORM")?"PLATFORM":"AGENT",key=scope.equals("PLATFORM")?"default":Long.toString(scope.equals("FIRST")?first:second);
        budgets.setLimit(new BudgetLedger.Limit(type,key,"MODEL_CALLS",14L,1,0),"fixture");
        db.update("update budget_accounts set used_amount=1,reserved_amount=1 where scope_type=? and scope_key=?",type,key);
        rejected(root.id(),request("shared"),"TEAM_SHARED_BUDGET_INSUFFICIENT");verifyNoInteractions(client);
        assertThat(db.queryForObject("select revision from budget_accounts where scope_type=? and scope_key=?",Long.class,type,key)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"UID","TEMPLATE","MODEL","PROMPT","MAX_TOKENS","AGENT","TOOLS","CONTEXT","TEMPERATURE","DIGEST"})
    void liveIdentityAndExecutionSemanticsMustMatchFrozenContract(String change) {
        var root=root("live-"+change);var original=profile("substrate://"+firstInstance);var p=(ObjectNode)original.profile();String uid=original.actorUid(),template=original.template();
        switch(change){case "UID"->uid="replacement";case "TEMPLATE"->template="replacement";case "MODEL"->p.put("model","different");case "PROMPT"->p.put("systemPrompt","different");case "MAX_TOKENS"->p.put("maxTokens",127);case "AGENT"->p.put("agentId",second);case "TOOLS"->p.put("tools","ENABLED");case "CONTEXT"->p.put("context","SESSION");case "TEMPERATURE"->p.put("temperature",1);case "DIGEST"->p.put("profileDigest","invalid");}
        when(client.inspectProfile("substrate://"+firstInstance)).thenReturn(new AgentExperimentClient.RuntimeProfile(p,uid,template));
        rejected(root.id(),request(change),Set.of("UID","TEMPLATE").contains(change)?"TEAM_LIVE_IDENTITY_CHANGED":"TEAM_LIVE_PROFILE_CHANGED");assertThat(count("budget_accounts")).isZero();
    }
    @Test void readinessCannotUseAChangedProfileEvenIfItsDesiredFieldsStillMatch() {
        var root=root("profile-drift");admission.freeze(root.id(),request("profile-drift"),"owner");
        var p=profile("substrate://"+firstInstance);((ObjectNode)p.profile()).put("profileDigest","c".repeat(64));when(client.inspectProfile("substrate://"+firstInstance)).thenReturn(p);
        assertThatThrownBy(()->admission.check(root.id(),json.createObjectNode(),"owner")).hasMessage("TEAM_FROZEN_LIVE_PROFILE_CHANGED");
    }
    @Test void secondRoleFailureRollsBackWholePlanAndNeverStoresUntrustedExceptionText() {
        var root=root("atomic");when(client.inspectProfile("substrate://"+secondInstance)).thenThrow(new IllegalArgumentException("PRIVATE_UNTRUSTED_SENTINEL"));
        rejected(root.id(),request("atomic"),"TEAM_LIVE_PROFILE_UNCONFIRMED");assertThat(count("budget_accounts")).isZero();assertThat(count("budget_limit_changes")).isZero();
    }
    @Test void sharedBudgetShrinkDuringRuntimeReadsRejectsFinalFreezeAtomically() {
        var root=root("shrink-during-read");
        when(client.inspectProfile("substrate://"+secondInstance)).thenAnswer(i->{
            budgets.setLimit(new BudgetLedger.Limit("PLATFORM","default","MODEL_CALLS",1L,0,0),"independent-fixture-change");
            return profile(i.getArgument(0));
        });
        rejected(root.id(),request("shrink-during-read"),"TEAM_SHARED_BUDGET_INSUFFICIENT");
        assertThat(count("budget_accounts")).isEqualTo(1);assertThat(count("budget_limit_changes")).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from budget_accounts where scope_type='TASK'",Long.class)).isZero();
    }
    @Test void concurrentSamePlanFreezesOnceAndCompetingPlanCannotClaimSameRoot() throws Exception {
        var root=root("concurrency");var request=request("concurrency");var pool=Executors.newFixedThreadPool(3);
        try{var fs=new ArrayList<Future<TeamEvolutionAdmissionService.Plan>>();for(int i=0;i<3;i++)fs.add(pool.submit(()->admission.freeze(root.id(),request,"owner")));
            var results=new HashSet<TeamEvolutionAdmissionService.Plan>();for(var f:fs)results.add(f.get(20,TimeUnit.SECONDS));assertThat(results).hasSize(1);
        }finally{pool.shutdownNow();}
        verify(client,times(2)).inspectProfile(any());assertThat(count("budget_accounts")).isEqualTo(3);assertThat(count("budget_limit_changes")).isEqualTo(3);
    }
    @Test void currentDesiredDeploymentOrCredentialApprovalChangeInvalidatesReadiness() {
        var root=root("desired");admission.freeze(root.id(),request("desired"),"owner");when(connections.executable(any())).thenReturn(false);
        assertThatThrownBy(()->admission.check(root.id(),json.createObjectNode(),"owner")).hasMessage("TEAM_RUNTIME_UNCONFIRMED");when(connections.executable(any())).thenReturn(true);
        db.update("update substrate_agent_deployments set image_ref=? where deployment_key=?","registry.test/other@sha256:"+"c".repeat(64),"budget-team-dep-"+first);
        assertThatThrownBy(()->admission.check(root.id(),json.createObjectNode(),"owner")).hasMessage("TEAM_DEPLOYMENT_UNCONFIRMED");
    }
    @Test void budgetCannotBeEditedOrConsumedByForgedTaskHeaderAndReadinessRejectsTampering() {
        var root=root("protected");var p=admission.freeze(root.id(),request("protected"),"owner");
        assertThatThrownBy(()->budgets.setLimit(new BudgetLedger.Limit("TASK",p.budgetScope(),"MODEL_CALLS",128L,0,1),"owner")).isInstanceOf(ConflictException.class);
        var http=new MockHttpServletRequest();http.addHeader("X-EAP-Task-Key",p.budgetScope());RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
        assertThatThrownBy(()->budgets.admitModel(json.createObjectNode().put("max_tokens",128),first,null,null,"trace","forged-team")).hasMessage("根预算只能由服务端持久意图关联");RequestContextHolder.resetRequestAttributes();
        db.update("update budget_accounts set limit_amount=128 where scope_key=? and unit='MODEL_CALLS'",p.budgetScope());
        assertThatThrownBy(()->admission.check(root.id(),json.createObjectNode(),"owner")).hasMessage("TEAM_FROZEN_BUDGET_UNCONFIRMED");
    }
    @Test void budgetAdmissionIsDisabledByDefaultSeparatelyFromMetadataContracts() {
        var root=root("disabled");TeamEvolutionAdmissionService target=org.springframework.test.util.AopTestUtils.getTargetObject(admission);
        boolean old=target.enabled;target.enabled=false;
        try{rejected(root.id(),request("disabled"),"TEAM_BUDGET_ADMISSION_DISABLED");}finally{target.enabled=old;}verifyNoInteractions(client);
    }
    @ParameterizedTest @ValueSource(strings={"VIEWER","OPERATOR","GOVERNANCE_ADMIN","PLATFORM_ADMIN"})
    void centralPermissionsGuardBothBudgetFreezeAndReadiness(String role) throws Exception {
        var root=root("permission-"+role);var auth=jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_"+role));
        int expected=role.equals("PLATFORM_ADMIN")?200:403;
        mvc.perform(post("/api/team-evolutions/"+root.id()+"/budget").with(auth).contentType("application/json").content(json.writeValueAsBytes(request("http-"+role)))).andExpect(status().is(expected));
        mvc.perform(post("/api/team-evolutions/"+root.id()+"/budget/check").with(auth).contentType("application/json").content("{}")).andExpect(status().is(expected));
        if(expected==200)mvc.perform(get("/api/team-evolutions/"+root.id()+"/budget").with(auth)).andExpect(status().isOk()).andExpect(jsonPath("$.data.executionAvailable").value(false));
        else verifyNoInteractions(client);
    }
}
