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
        "eap.team-evolution.model-proposals-enabled=true","eap.team-evolution.holdout-enabled=true","eap.budget.enabled=true","eap.team-evolution.scoring-enabled=true","eap.team-evolution.slot-execution-enabled=true","eap.gateway.enabled=true","eap.gateway.upstream-base-url=http://synthetic.invalid","eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid/realm",
        "eap.security.jwk-set-uri=https://idp.invalid/jwks","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class TeamEvolutionProposalIntegrationTests {
    @Autowired TeamEvolutionOperationService operations;
    @Autowired TeamEvolutionProposalService proposals;
    @Autowired TeamEvolutionHoldoutService holdout;
    @Autowired TeamEvolutionService contracts; @Autowired TeamEvolutionAdmissionService admission;
    @Autowired BudgetLedger budgets; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
    @Autowired AgentService agents; @Autowired MockMvc mvc;
    @MockitoBean RuntimeConnectionService connections; @MockitoBean AgentExperimentClient client;
    @MockitoBean OpenAiUpstreamClient upstream; @MockitoBean WorkflowTaskClient workflow;
    @Autowired TeamEvolutionScoringService scoring;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired TeamEvolutionExecutionService execution; @Autowired RuntimeAccessController runtimeAccess;
    long first,second;String firstInstance,secondInstance;
    @BeforeEach void setup() {
        org.springframework.test.util.ReflectionTestUtils.setField(holdout,"enabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(budgets,"teamHoldoutEnabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(scoring,"enabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(budgets,"teamExecutionEnabled",true);
        db.update("delete from team_evolution_closures");db.update("delete from team_evolution_holdouts");db.update("delete from team_evolution_frontiers");db.update("delete from team_evolution_scores");db.update("delete from team_evolution_sample_responsibilities");
        db.update("delete from team_evolution_dispatches");db.update("delete from team_evolution_executions");
        RequestContextHolder.resetRequestAttributes();
        db.update("delete from team_evolution_proposals");db.update("delete from team_evolution_budget_plans");
        for(String table:List.of("budget_limit_changes","budget_settlements","budget_reservation_items","budget_reservations","budget_accounts","team_evolution_slots","team_evolution_evaluations","team_evolution_attempts"))db.update("delete from "+table);
        for(int n=8;n>=0;n--)db.update("delete from team_evolution_candidates where generation=?",n);db.update("delete from team_evolution_contracts");
        when(connections.executable(any())).thenReturn(true);
        when(connections.inspectionGeneration(anyString())).thenAnswer(i->db.queryForObject("select generation from runtime_connections where instance_key=?",String.class,(String)i.getArgument(0)));
        first=fixture("extractor");second=fixture("classifier");firstInstance="eap-agents/budget-team-"+first;secondInstance="eap-agents/budget-team-"+second;
        when(client.inspectProfile(any())).thenAnswer(i->profile(i.getArgument(0)));
        when(connections.authenticate(anyString(),anyString())).thenAnswer(i->{String instance=i.getArgument(0);var r=db.queryForMap("select id,agent_id from agent_runtime_instances where instance_key=?",instance);return new RuntimeConnectionService.Access(instance,((Number)r.get("agent_id")).longValue(),"generation-"+r.get("agent_id"),"runtime-"+r.get("id"));});
        when(connections.resolveTrace(any(),anyString())).thenAnswer(i->i.getArgument(1));
        when(upstream.chatCompletions(any())).thenAnswer(i->{var body=i.getArgument(0,JsonNode.class);if(body==null)return null;boolean secondRole=body.path("messages").path(0).path("content").asText().startsWith("classifier");
            var result=json.createObjectNode();result.set("choices",json.createArrayNode().add(json.createObjectNode().put("finish_reason","stop").set("message",json.createObjectNode().put("content",secondRole?"answer VALIDATION":"完整前序输出"))));
            result.set("usage",json.createObjectNode().put("prompt_tokens",2).put("completion_tokens",3));return org.springframework.http.ResponseEntity.ok(result);});
        when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(response("{\"system_prompt\":\"Improved public extraction\"}",true)));
        org.springframework.test.util.ReflectionTestUtils.setField(proposals,"enabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(admission,"proposalsEnabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(budgets,"teamProposalsEnabled",true);

    }
    @AfterEach void noRealNetworkOrInheritedExecution() {
        verify(client,never()).execute(any());verify(client,never()).profile(any());verifyNoInteractions(workflow);
        verify(client,never()).executeStateless(any());
        assertThat(TeamProposalAccess.current()).isNull();assertThat(RuntimeModelAccess.current()).isNull();RequestContextHolder.resetRequestAttributes();
    }
    long fixture(String role) {
        long agent=agents.create(new AgentDtos.AgentRequest("TEAM-BUDGET-"+UUID.randomUUID(),"Team admission","","Test","Owner","Test",RiskLevel.LOW)).id();agents.updateStatus(agent,AgentLifecycleStatus.ACTIVE);
        String instance="eap-agents/budget-team-"+agent,template="budget-team-template-"+agent,uid="budget-team-uid-"+agent;
        db.update("insert into agent_runtime_instances(agent_id,instance_key,endpoint,protocol,dispatch_authenticated,status,load_score,platform_actor_uid,platform_actor_template,created_at,updated_at) values(?,?,?,'SUBSTRATE',true,'ONLINE',0,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",agent,instance,"substrate://"+instance,uid,"eap-agents/"+template);
        String config=contracts.encode(Map.of("sourceType","RUNNER","systemPrompt",role+" baseline","model","test-model"));
        db.update("insert into substrate_agent_deployments(deployment_key,agent_id,version,source_type,status,config_json,image_ref,atespace,template_name,instance_key,steps_json,requested_by,created_at,updated_at) values(?,?,1,'RUNNER','READY',?,?,'eap-agents',?,?,'[]','fixture',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","budget-team-dep-"+agent,agent,config,"registry.test/runner@sha256:"+"b".repeat(64),template,instance);
        db.update("insert into platform_runtime_creations(agent_id,request_key,deployment_key,instance_key,actor_uid,revoked,attempted,delivery_request) values(?,?,?,?,?,false,true,?)",agent,"creation-"+agent,"budget-team-dep-"+agent,instance,uid,UUID.randomUUID().toString());db.update("insert into runtime_connections(instance_key,generation,expires_at,verified_at) values(?,?,?,CURRENT_TIMESTAMP)",instance,"generation-"+agent,java.sql.Timestamp.from(Instant.now().plusSeconds(900)));return agent;
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

    JsonNode response(String output,boolean usage) {
        var result=json.createObjectNode();result.set("choices",json.createArrayNode().add(json.createObjectNode().put("finish_reason","stop").set("message",json.createObjectNode().put("content",output))));
        if(usage)result.set("usage",json.createObjectNode().put("prompt_tokens",2).put("completion_tokens",3));return result;
    }
    ObjectNode approval(String key) {return request(key).put("proposalMode","MODEL_SINGLE_ROLE").put("proposalUseConfirmed",true).put("maxProposalCalls",1).put("maxProposalTokens",512).put("maxCalls",13).put("maxOutputTokens",2048);}
    TeamEvolutionService.Summary approved(String key) {var r=root(key);admission.freeze(r.id(),approval(key),"owner");return r;}
    ObjectNode proposeRequest(TeamEvolutionService.Summary r) {return json.createObjectNode().put("requestKey","proposal-original").put("parentId",r.baselineId()).put("role","extractor");}
    TeamEvolutionProposalService.Receipt propose(TeamEvolutionService.Summary r) {return proposals.propose(r.id(),proposeRequest(r),"owner");}
    @ParameterizedTest @ValueSource(strings={"deepseek-flash","deepseek-pro","test-model","deepseek-reasoner"})
    void boundedProposalThinkingPolicyIsFrozenOnlyForKnownModels(String model) {
        for(long agent:List.of(first,second)) {
            var config=(ObjectNode)contracts.tree(db.queryForObject("select config_json from substrate_agent_deployments where agent_id=?",String.class,agent));
            config.put("model",model);db.update("update substrate_agent_deployments set config_json=? where agent_id=?",contracts.encode(config),agent);
        }
        doAnswer(i->{var p=profile(i.getArgument(0));((ObjectNode)p.profile()).put("model",model);return p;}).when(client).inspectProfile(any());
        var r=approved("thinking-policy");var receipt=propose(r);assertThat(receipt.state()).isEqualTo("COMPLETED");
        var sent=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(upstream).chatCompletions(sent.capture());
        var frozen=db.queryForMap("select request_json,model_request_digest from team_evolution_proposals where contract_id=?",r.id());
        assertThat(contracts.tree((String)frozen.get("request_json"))).isEqualTo(sent.getValue());
        assertThat(TeamEvolutionDigest.of(json,sent.getValue())).isEqualTo(frozen.get("model_request_digest"));
        if(Set.of("deepseek-flash","deepseek-pro").contains(model))
            assertThat(sent.getValue().path("thinking")).isEqualTo(json.createObjectNode().put("type","disabled"));
        else assertThat(sent.getValue().has("thinking")).isFalse();
        assertThat(sent.getValue().path("max_tokens").asInt()).isEqualTo(512);
        clearInvocations(upstream,client);assertThat(propose(r)).isEqualTo(receipt);verifyNoInteractions(upstream,client);
    }
    @Test void trainOnlyDraftChargesRootAndPreservesOtherRoleWithoutRegistering() {
        var r=approved("train-only");var receipt=propose(r);
        assertThat(receipt.state()).isEqualTo("COMPLETED");assertThat(receipt.registered()).isFalse();assertThat(receipt.draftAvailable()).isTrue();
        assertThat(receipt.usageState()).isEqualTo("SETTLED");assertThat(receipt.inputTokens()).isEqualTo(2);assertThat(receipt.outputTokens()).isEqualTo(3);
        var captor=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(upstream).chatCompletions(captor.capture());
        var message=captor.getValue().path("messages").path(1).path("content").asText();
        assertThat(message).contains("public TRAIN","answer TRAIN","extractor baseline","classifier baseline").doesNotContain("public VALIDATION","answer VALIDATION","public HOLDOUT","answer HOLDOUT");
        var draft=json.valueToTree(proposals.content(r.id(),receipt.requestKey(),"owner"));
        assertThat(draft.path("configuration").path("rolePrompts").path("classifier").asText()).isEqualTo("classifier baseline");
        assertThat(draft.path("configuration").path("rolePrompts").path("extractor").asText()).isEqualTo("Improved public extraction");
        assertThat(count("team_evolution_candidates")).isEqualTo(1);assertThat(contracts.get(r.id()).attempts()).isEqualTo(1);
        assertThat(db.queryForObject("select used_amount from budget_accounts where scope_type='TASK' and scope_key=? and unit='MODEL_CALLS'",Long.class,"team-evolution:"+r.id())).isEqualTo(1);
        assertThat(scoring.frontier(r.id()).costs().modelCalls()).isEqualTo(1);
        var closure=holdout.close(r.id(),json.createObjectNode().put("requestKey","close"),"owner");assertThat(closure.proposalModelCalls()).isEqualTo(1);assertThat(closure.completeEvidence()).isFalse();
        clearInvocations(client,upstream);assertThat(propose(r)).isEqualTo(receipt);verifyNoInteractions(client,upstream);
    }
    @Test void sameRequestDoesNotResendAndChangedPayloadOrPrincipalIsRejected() {
        var r=approved("dedupe");var first=propose(r);assertThat(propose(r)).isEqualTo(first);
        assertThatThrownBy(()->proposals.propose(r.id(),proposeRequest(r).put("role","classifier"),"owner")).hasMessage("TEAM_ORIGINAL_PROPOSAL_REQUIRED");
        assertThatThrownBy(()->proposals.get(r.id(),first.requestKey(),"different")).hasMessage("TEAM_ORIGINAL_PRINCIPAL_REQUIRED");
        assertThatThrownBy(()->proposals.content(r.id(),first.requestKey(),"different")).hasMessage("TEAM_ORIGINAL_PRINCIPAL_REQUIRED");verify(upstream).chatCompletions(any());
    }
    @Test void parallelDuplicateHasOneSenderAndPendingBlocksSealAndClose() throws Exception {
        var r=approved("parallel");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(upstream.chatCompletions(any())).thenAnswer(i->{entered.countDown();assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();return org.springframework.http.ResponseEntity.ok(response("{\"system_prompt\":\"improved\"}",true));});
        var pool=Executors.newSingleThreadExecutor();
        try {var running=pool.submit(()->propose(r));assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();
            assertThat(propose(r).state()).isEqualTo("DISPATCHED");
            assertThatThrownBy(()->scoring.seal(r.id(),json.createObjectNode().put("requestKey","seal"),"owner")).hasMessage("TEAM_ORIGINAL_PROPOSAL_UNRESOLVED");
            assertThatThrownBy(()->holdout.close(r.id(),json.createObjectNode().put("requestKey","close"),"owner")).hasMessage("TEAM_PROPOSAL_STILL_RUNNING");
            release.countDown();assertThat(running.get(30,TimeUnit.SECONDS).state()).isEqualTo("COMPLETED");
        }finally{release.countDown();pool.shutdownNow();}verify(upstream).chatCompletions(any());
    }
    @ParameterizedTest @ValueSource(strings={"{}","{\"system_prompt\":\"\"}","{\"system_prompt\":\"x\",\"tools\":[]}","{\"system_prompt\":\"x\",\"system_prompt\":\"y\"}","{\"system_prompt\":\"x\"} {}","{\"system_prompt\":\"extractor baseline\"}","not json"})
    void rejectsUntrustedOutputWithoutRefund(String output) {
        var r=approved("bad-output");when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(response(output,true)));
        var result=propose(r);assertThat(result.state()).isEqualTo("FAILED");assertThat(result.usageState()).isEqualTo("SETTLED");assertThat(result.draftAvailable()).isFalse();
        assertThat(count("team_evolution_candidates")).isEqualTo(1);assertThat(scoring.frontier(r.id()).costs().modelCalls()).isEqualTo(1);
        assertThatThrownBy(()->proposals.content(r.id(),result.requestKey(),"owner")).hasMessage("TEAM_PROPOSAL_DRAFT_UNAVAILABLE");
    }
    @Test void unknownUsageRetainsReservationAndCannotBecomeFreeRetry() {
        var r=approved("unknown");when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(response("{\"system_prompt\":\"x\"}",false)));
        var result=propose(r);assertThat(result.state()).isEqualTo("UNKNOWN");assertThat(result.usageState()).isEqualTo("UNKNOWN");assertThat(propose(r)).isEqualTo(result);
        assertThat(db.queryForObject("select reserved_amount from budget_accounts where scope_type='TASK' and scope_key=? and unit='OUTPUT_TOKENS'",Long.class,"team-evolution:"+r.id())).isEqualTo(512);
        assertThatThrownBy(()->proposals.propose(r.id(),proposeRequest(r).put("requestKey","replacement"),"owner")).hasMessage("TEAM_PROPOSAL_LIMIT_REACHED");
        assertThatThrownBy(()->scoring.seal(r.id(),json.createObjectNode().put("requestKey","seal"),"owner")).hasMessage("TEAM_ORIGINAL_PROPOSAL_UNRESOLVED");
        var closed=holdout.close(r.id(),json.createObjectNode().put("requestKey","close"),"owner");assertThat(closed.completeEvidence()).isFalse();assertThat(closed.proposalModelCalls()).isEqualTo(1);verify(upstream).chatCompletions(any());
    }
    @Test void failedReadinessCreatesOriginalFailureWithoutCallingModel() {
        var r=approved("readiness");doThrow(new IllegalStateException("private exception body")).when(client).inspectProfile(any());
        var result=propose(r);assertThat(result.state()).isEqualTo("FAILED");assertThat(result.reasonCode()).isEqualTo("PROPOSAL_PRE_DISPATCH_REJECTED");assertThat(result.usageState()).isEqualTo("NO_RESERVATION");
        assertThat(propose(r)).isEqualTo(result);verifyNoInteractions(upstream);assertThat(count("budget_reservations")).isZero();
    }
    @Test void offlineConsentAndDisabledFlagsDoNotAuthorizeProposal() {
        var r=root("offline");admission.freeze(r.id(),request("offline"),"owner");assertThatThrownBy(()->propose(r)).hasMessage("TEAM_PROPOSAL_PURPOSE_UNAPPROVED");
        org.springframework.test.util.ReflectionTestUtils.setField(proposals,"enabled",false);assertThatThrownBy(()->propose(r)).hasMessage("TEAM_MODEL_PROPOSALS_DISABLED");verifyNoInteractions(upstream);
    }
    @Test void proposalAllowanceRequiresPurposeAndFullCombinedBudget() {
        var r=root("budget");var a=approval("budget");a.put("proposalUseConfirmed",false);assertThatThrownBy(()->admission.freeze(r.id(),a,"owner")).hasMessage("TEAM_PROPOSAL_USE_CONFIRMATION_REQUIRED");
        a.put("proposalUseConfirmed",true).put("maxCalls",12);assertThatThrownBy(()->admission.freeze(r.id(),a,"owner")).hasMessage("TEAM_COMPLETE_PLAN_BUDGET_REQUIRED");
        a.put("maxCalls",13).put("maxOutputTokens",2047);assertThatThrownBy(()->admission.freeze(r.id(),a,"owner")).hasMessage("TEAM_COMPLETE_PLAN_BUDGET_REQUIRED");
        a.put("maxOutputTokens",2048);var p=admission.freeze(r.id(),a,"owner");assertThat(p.requiredCalls()).isEqualTo(13);assertThat(p.maxProposalTokens()).isEqualTo(512);
        assertThatThrownBy(()->admission.freeze(r.id(),a.put("maxProposalCalls",2),"owner")).hasMessage("TEAM_ORIGINAL_BUDGET_PLAN_REQUIRED");
    }
    @Test void arbitraryGatewayTraceHasNoInProcessAuthority() {
        var r=approved("forged");var result=propose(r);var body=contracts.tree(db.queryForObject("select request_json from team_evolution_proposals where contract_id=?",String.class,r.id()));
        assertThatThrownBy(()->budgets.admitModel(body,first,null,null,result.traceId(),"forged-key")).hasMessageContaining("原意图");
        assertThat(count("budget_reservations")).isEqualTo(1);
    }
    @Test void disabledLedgerRejectsOriginalProposalWithoutUnbudgetedFallback() {
        var r=approved("disabled-ledger");org.springframework.test.util.ReflectionTestUtils.setField(budgets,"teamProposalsEnabled",false);
        var result=propose(r);assertThat(result.state()).isEqualTo("FAILED");assertThat(result.reasonCode()).isEqualTo("PROPOSAL_GATEWAY_REJECTED");verifyNoInteractions(upstream);
    }
    @Test void newModelFieldsAreNotAcceptedByOfflineBudget() {
        var r=root("extra-fields");var a=request("extra-fields").put("maxProposalCalls",1);assertThatThrownBy(()->admission.freeze(r.id(),a,"owner")).hasMessage("TEAM_FIELDS_REJECTED");
    }
    @Test void gatewayRechecksOtherRoleRevocationAfterReadiness() {
        var r=approved("revoke-race");var profile=profile("substrate://"+secondInstance);
        when(client.inspectProfile("substrate://"+secondInstance)).thenAnswer(i->{db.update("update platform_runtime_creations set revoked=true where instance_key=?",secondInstance);return profile;});
        var result=propose(r);assertThat(result.state()).isEqualTo("FAILED");verifyNoInteractions(upstream);
    }
    @Test void expiredOriginalResponsibilityCannotAdoptLateResponseOrResend() throws Exception {
        var r=approved("expiry-late");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(upstream.chatCompletions(any())).thenAnswer(i->{entered.countDown();assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();return org.springframework.http.ResponseEntity.ok(response("{\"system_prompt\":\"late improvement\"}",true));});
        var pool=Executors.newSingleThreadExecutor();
        try {var running=pool.submit(()->propose(r));assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();
            db.update("update team_evolution_budget_plans set approval_expires_at=? where contract_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),r.id());
            assertThat(proposals.get(r.id(),"proposal-original","owner").state()).isEqualTo("DISPATCHED");
            assertThat(propose(r).state()).isEqualTo("UNKNOWN");release.countDown();assertThat(running.get(30,TimeUnit.SECONDS).state()).isEqualTo("UNKNOWN");
            var original=proposals.get(r.id(),"proposal-original","owner");assertThat(original.usageState()).isEqualTo("SETTLED");assertThat(original.draftAvailable()).isFalse();
            assertThat(holdout.close(r.id(),json.createObjectNode().put("requestKey","closed-expiry"),"owner").completeEvidence()).isFalse();
        }finally{release.countDown();pool.shutdownNow();}verify(upstream).chatCompletions(any());
    }
    @Test void unknownCannotUseSecondExplicitProposalAllowance() {
        var r=root("unknown-second");admission.freeze(r.id(),approval("unknown-second").put("maxProposalCalls",2).put("maxCalls",14).put("maxOutputTokens",2560),"owner");
        when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(response("{\"system_prompt\":\"x\"}",false)));
        assertThat(propose(r).state()).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->proposals.propose(r.id(),proposeRequest(r).put("requestKey","new-key"),"owner")).hasMessage("TEAM_ORIGINAL_PROPOSAL_UNRESOLVED");verify(upstream).chatCompletions(any());
    }
    @Test void originalDispatchRejectsChangedModelPayloadAndAlreadyReservedReplay() throws Exception {
        var r=approved("payload");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(upstream.chatCompletions(any())).thenAnswer(i->{entered.countDown();assertThat(release.await(20,TimeUnit.SECONDS)).isTrue();return org.springframework.http.ResponseEntity.ok(response("{\"system_prompt\":\"bounded change\"}",true));});
        var pool=Executors.newSingleThreadExecutor();
        try {var running=pool.submit(()->propose(r));assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();
            var original=proposals.get(r.id(),"proposal-original","owner");var body=contracts.tree(db.queryForObject("select request_json from team_evolution_proposals where contract_id=?",String.class,r.id()));
            var changed=body.deepCopy();((ObjectNode)changed).put("model","replacement-model");
            assertThatThrownBy(()->TeamProposalAccess.call(r.id(),original.traceId(),()->budgets.admitModel(changed,first,null,null,original.traceId(),"ignored"))).hasMessageContaining("载荷已变化");
            assertThatThrownBy(()->TeamProposalAccess.call(r.id(),original.traceId(),()->budgets.admitModel(body,first,null,null,original.traceId(),"ignored"))).hasMessageContaining("调用意图已保存");
            release.countDown();assertThat(running.get(30,TimeUnit.SECONDS).state()).isEqualTo("COMPLETED");
        }finally{release.countDown();pool.shutdownNow();}assertThat(count("budget_reservations")).isEqualTo(1);verify(upstream).chatCompletions(any());
    }
    @Test void originalProposalListIsBoundedReadOnlyAndPreservesUnknownWithoutBody() {
        var r=approved("history");when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(response("{\"system_prompt\":\"draft\"}",false)));
        var original=propose(r);clearInvocations(upstream,client);
        assertThat(proposals.list(r.id(),"owner")).containsExactly(original);
        assertThat(proposals.list(r.id(),"owner").getFirst().state()).isEqualTo("UNKNOWN");
        assertThatThrownBy(()->proposals.list(r.id(),"another")).hasMessage("TEAM_ORIGINAL_PRINCIPAL_REQUIRED");
        assertThat(count("team_evolution_proposals")).isEqualTo(1);assertThat(count("budget_reservations")).isEqualTo(1);verifyNoInteractions(upstream,client);
    }
    @Test void exactRoutesApplyCentralPermissionsAndNeverReturnDraftInMetadata() throws Exception {
        var r=approved("permissions");var result=propose(r);String path="/api/team-evolutions/"+r.id()+"/proposals/"+result.requestKey();
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        for(String role:List.of("VIEWER","OPERATOR","GOVERNANCE_ADMIN")) {
            mvc.perform(get("/api/team-evolutions/"+r.id()+"/proposals").with(jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_"+role)))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_"+role)))).andExpect(status().isForbidden());
            mvc.perform(post("/api/team-evolutions/"+r.id()+"/proposals").contentType("application/json").content(proposeRequest(r).toString()).with(jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_"+role)))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/team-evolutions/"+r.id()+"/proposals").with(jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].state").value("COMPLETED")).andExpect(jsonPath("$.data[0].configuration").doesNotExist());
        mvc.perform(get(path).with(jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))).andExpect(status().isOk()).andExpect(jsonPath("$.data.configuration").doesNotExist()).andExpect(jsonPath("$.data.state").value("COMPLETED"));
        mvc.perform(get(path+"/content").with(jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))).andExpect(status().isOk()).andExpect(jsonPath("$.data.configuration.rolePrompts.extractor").value("Improved public extraction"));
        mvc.perform(put(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")))).andExpect(status().isForbidden());
    }
}
