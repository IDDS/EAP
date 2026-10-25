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
class TeamEvolutionExecutionIntegrationTests {
    @Autowired TeamEvolutionOperationService operations;
    @Autowired TeamEvolutionProposalService proposals;
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
        org.springframework.test.util.ReflectionTestUtils.setField(proposals,"enabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(admission,"proposalsEnabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(budgets,"teamProposalsEnabled",true);
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
        when(client.executeStateless(any())).thenAnswer(i->i.getArgument(0)==null?null:runner(i.getArgument(0)));

    }
    @AfterEach void noRealNetworkOrInheritedExecution() {
        verify(client,never()).execute(any());verify(client,never()).profile(any());verifyNoInteractions(workflow);
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
    AgentExperimentClient.Receipt runner(AgentExperimentClient.StatelessDispatch d) {
        var b=d.body();var role=d.endpoint().endsWith("-"+first)?first:second;
        var model=json.createObjectNode().put("model","test-model").put("max_tokens",128).put("temperature",0).put("stream",false);
        model.set("messages",json.createArrayNode().add(json.createObjectNode().put("role","system").put("content",(String)b.get("systemPrompt"))).add(json.createObjectNode().put("role","user").put("content",(String)b.get("input"))));
        var req=new MockHttpServletRequest("POST","/api/runtime-access/v1/chat/completions");req.addHeader("X-EAP-Runtime-Key",role==first?firstInstance:secondInstance);req.addHeader("X-EAP-Runtime-Token","synthetic-fixture");req.addHeader("X-EAP-Trace-Id",b.get("traceId"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        try {
            var response=runtimeAccess.chat(model,req).getBody();var choice=response.path("choices").path(0);
            var receipt=json.createObjectNode().put("status","SUCCEEDED").put("traceId",(String)b.get("traceId")).put("output",choice.path("message").path("content").asText()).put("finishReason",choice.path("finish_reason").asText());
            receipt.set("usage",json.createObjectNode().put("prompt_tokens",99999).put("completion_tokens",99999));receipt.put("latencyMs",-1);
            return new AgentExperimentClient.Receipt("SUCCEEDED",null,receipt);
        }finally{RequestContextHolder.resetRequestAttributes();}
    }
    record Fixture(long id,String evaluation) {}
    Fixture prepared(String key) {
        var root=root(key);admission.freeze(root.id(),request(key),"owner");
        var evaluation=contracts.enroll(root.id(),json.createObjectNode().put("requestKey","eval-"+key).put("candidateId",root.baselineId()).put("split","VALIDATION"),"owner");
        return new Fixture(root.id(),evaluation.id());
    }
    ObjectNode executeRequest(){return json.createObjectNode().put("requestKey","original-execution");}
    TeamEvolutionExecutionService.Result execute(Fixture f){return execution.execute(f.id(),f.evaluation(),executeRequest(),"owner");}
    @Test void twoOrderedRolesUseCompleteOutputAndActualGatewayCostWithoutAdoption() {
        var f=prepared("success");var result=execute(f);
        assertThat(result.state()).isEqualTo("RECEIPTS_COMPLETE");assertThat(result.confirmedSlots()).isEqualTo(2);assertThat(result.scoringAvailable()).isTrue();assertThat(result.adoptionAvailable()).isFalse();
        var calls=org.mockito.ArgumentCaptor.forClass(AgentExperimentClient.StatelessDispatch.class);verify(client,times(2)).executeStateless(calls.capture());
        assertThat(calls.getAllValues().get(1).body().get("input")).isEqualTo("完整前序输出");
        assertThat(calls.getAllValues().get(0).body()).doesNotContainKeys("expected","usage","score");
        assertThat(db.queryForList("select state from budget_reservations order by id",String.class)).containsExactly("SETTLED","SETTLED");
        assertThat(db.queryForObject("select sum(output_tokens) from budget_reservations",Long.class)).isEqualTo(6);
        assertThat(db.queryForObject("select used_amount from budget_accounts where scope_type='TASK' and unit='OUTPUT_TOKENS'",Long.class)).isEqualTo(6);
        assertThat(db.queryForObject("select count(*) from team_evolution_dispatches where reservation_id is not null and gateway_output_digest is not null and finished_at>=started_at",Long.class)).isEqualTo(2);
        clearInvocations(client,upstream);assertThat(execute(f)).isEqualTo(result);verifyNoInteractions(client,upstream);
        assertThatThrownBy(()->execution.execute(f.id(),f.evaluation(),json.createObjectNode().put("requestKey","replacement"),"owner")).isInstanceOf(ConflictException.class);
    }
    @Test void concurrentDuplicateSubmissionCannotCreateTwoSenders() throws Exception {
        var f=prepared("parallel");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(client.executeStateless(any())).thenAnswer(i->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return runner(i.getArgument(0));});
        var pool=Executors.newSingleThreadExecutor();
        try {var future=pool.submit(()->execute(f));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();assertThat(execute(f).state()).isEqualTo("RUNNING");release.countDown();assertThat(future.get(30,TimeUnit.SECONDS).state()).isEqualTo("RECEIPTS_COMPLETE");}
        finally{release.countDown();pool.shutdownNow();}
        verify(client,times(2)).executeStateless(any());assertThat(count("team_evolution_executions")).isEqualTo(1);
    }
    @Test void receiptWithSelfReportedUsageButNoGatewayEvidenceStopsUnknown() {
        var f=prepared("fake");when(client.executeStateless(any())).thenAnswer(i->{var d=i.getArgument(0,AgentExperimentClient.StatelessDispatch.class);return new AgentExperimentClient.Receipt("SUCCEEDED",null,json.createObjectNode().put("status","SUCCEEDED").put("traceId",(String)d.body().get("traceId")).put("output","fake").put("finishReason","stop"));});
        assertThat(execute(f).state()).isEqualTo("UNKNOWN");assertThat(count("budget_reservations")).isZero();verify(client,times(1)).executeStateless(any());
        assertThat(contracts.evaluation(f.id(),f.evaluation()).slots().stream().map(TeamEvolutionService.Slot::state)).containsExactly("UNKNOWN","NOT_DISPATCHED");
        clearInvocations(client);execute(f);verifyNoInteractions(client);
    }
    @Test void runnerCannotReplaceGatewayOutputEvenWithCorrectTraceAndUsage() {
        var f=prepared("forged-output");when(client.executeStateless(any())).thenAnswer(i->{var r=runner(i.getArgument(0));((ObjectNode)r.body()).put("output","replacement");return r;});
        assertThat(execute(f).state()).isEqualTo("UNKNOWN");assertThat(count("budget_reservations")).isEqualTo(1);assertThat(db.queryForObject("select state from budget_reservations",String.class)).isEqualTo("SETTLED");
    }
    @Test void unknownUsageRetainsOutputReservationAndStopsSecondRole() {
        var f=prepared("unknown-usage");when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(json.createObjectNode().set("choices",json.createArrayNode().add(json.createObjectNode().put("finish_reason","stop").set("message",json.createObjectNode().put("content","ok"))))));
        assertThat(execute(f).state()).isEqualTo("UNKNOWN");assertThat(db.queryForObject("select state from budget_reservations",String.class)).isEqualTo("UNKNOWN");
        assertThat(db.queryForObject("select reserved_amount from budget_accounts where scope_type='TASK' and unit='OUTPUT_TOKENS'",Long.class)).isEqualTo(128);verify(client,times(1)).executeStateless(any());
    }
    @Test void oversizedCompleteHandoffFailsWithoutTruncationOrSecondCall() {
        var f=prepared("long-handoff");when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(response("中".repeat(667),true)));
        assertThat(execute(f).state()).isEqualTo("FAILED");assertThat(count("team_evolution_dispatches")).isEqualTo(1);verify(client,times(1)).executeStateless(any());
        assertThat(contracts.tree(db.queryForObject("select output_json from team_evolution_dispatches",String.class)).asText()).hasSize(667);
    }
    @Test void encodedBodyOverflowIsRejectedBeforePersistingOrSendingSlot() {
        var root=root("body-limit");admission.freeze(root.id(),request("body-limit"),"owner");
        var candidate=contracts.propose(root.id(),json.createObjectNode().put("requestKey","escaped-prompt").put("parentId",root.baselineId()).set("configuration",json.createObjectNode().set("rolePrompts",json.createObjectNode().put("extractor","\u0001".repeat(8000)).put("classifier","classifier baseline"))),"owner");
        var e=contracts.enroll(root.id(),json.createObjectNode().put("requestKey","escaped-eval").put("candidateId",candidate.candidateId()).put("split","VALIDATION"),"owner");
        assertThat(execute(new Fixture(root.id(),e.id())).state()).isEqualTo("FAILED");verify(client,never()).executeStateless(any());assertThat(count("team_evolution_dispatches")).isZero();
    }
    @Test void revokedOriginalApprovalStopsBeforeSendAndOriginalReplayDoesNotRevive() {
        var f=prepared("revoked");db.update("update platform_runtime_creations set revoked=true where instance_key=?",firstInstance);
        assertThat(execute(f).state()).isEqualTo("FAILED");clearInvocations(client);db.update("update platform_runtime_creations set revoked=false where instance_key=?",firstInstance);execute(f);verifyNoInteractions(client);
    }
    @Test void expiredHolderCannotAcceptLateReceiptOrAcquireReplacement() {
        var f=prepared("expired");when(client.executeStateless(any())).thenAnswer(i->{var r=runner(i.getArgument(0));db.update("update team_evolution_executions set deadline=? where evaluation_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),f.evaluation());return r;});
        assertThat(execute(f).state()).isEqualTo("UNKNOWN");assertThat(execution.get(f.id(),f.evaluation()).confirmedSlots()).isZero();execute(f);verify(client,times(1)).executeStateless(any());
    }
    @Test void providerFailureKeepsOneUnknownIntentAndDoesNotRetry() {
        var f=prepared("provider-failure");when(upstream.chatCompletions(any())).thenThrow(new IllegalStateException("PRIVATE_PROVIDER_SENTINEL"));
        assertThat(execute(f).state()).isEqualTo("UNKNOWN");assertThat(count("budget_reservations")).isEqualTo(1);assertThat(db.queryForObject("select state from budget_reservations",String.class)).isEqualTo("UNKNOWN");
        assertThat(execution.get(f.id(),f.evaluation()).reasonCode()).doesNotContain("PRIVATE");execute(f);verify(client,times(1)).executeStateless(any());
    }
    @Test void repeatedGatewayCallUsesTheOriginalReservationAndNeverSpendsTwice() {
        var f=prepared("gateway-repeat");when(client.executeStateless(any())).thenAnswer(i->{var d=i.getArgument(0,AgentExperimentClient.StatelessDispatch.class);var r=runner(d);
            assertThatThrownBy(()->runner(d)).isInstanceOf(com.eap.platform.gateway.GatewayException.class).satisfies(e->assertThat(((com.eap.platform.gateway.GatewayException)e).getCode()).isEqualTo("budget_invocation_recorded"));return r;});
        assertThat(execute(f).state()).isEqualTo("RECEIPTS_COMPLETE");verify(upstream,times(2)).chatCompletions(any());assertThat(count("budget_reservations")).isEqualTo(2);
    }
    @Test void originalExpiredResponsibilityCanOnlyCloseUnknownWithoutTakingOver() {
        var f=prepared("expired-before-recovery");
        db.update("insert into team_evolution_executions(evaluation_id,request_key,fingerprint,principal_digest,workflow_key,holder,deadline,state,started_at) values(?,?,?,?,?,?,?,'RUNNING',CURRENT_TIMESTAMP)",f.evaluation(),"original-execution",contracts.hash(executeRequest()),contracts.principal("owner"),"team-evaluation:"+f.evaluation(),UUID.randomUUID().toString(),java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        db.update("update team_evolution_evaluations set state='RUNNING' where id=?",f.evaluation());
        assertThat(execute(f).state()).isEqualTo("UNKNOWN");verify(client,never()).executeStateless(any());assertThat(count("team_evolution_dispatches")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"MODEL","INPUT","TOOLS","BOUND","INSTANCE","GENERATION","REAPPROVED","UID","REVOKED","DEPLOYMENT","TEMPLATE","INACTIVE","DISABLED","SHARED_CAP","TASK_HEADER","LEGACY"})
    void gatewayRejectsChangedRequestOrAuthorityBeforeUpstream(String change) {
        var f=prepared("gateway-"+change);
        when(client.executeStateless(any())).thenAnswer(i->{var d=i.getArgument(0,AgentExperimentClient.StatelessDispatch.class);
            switch(change){case "MODEL"->d=new AgentExperimentClient.StatelessDispatch(d.endpoint(),d.actorUid(),d.template(),d.profileDigest(),new LinkedHashMap<>(d.body()){{put("systemPrompt","changed");}},d.deadline());
                case "INPUT"->d=new AgentExperimentClient.StatelessDispatch(d.endpoint(),d.actorUid(),d.template(),d.profileDigest(),new LinkedHashMap<>(d.body()){{put("input","changed");}},d.deadline());
                case "INSTANCE"->d=new AgentExperimentClient.StatelessDispatch("substrate://"+secondInstance,d.actorUid(),d.template(),d.profileDigest(),d.body(),d.deadline());
                case "REAPPROVED"->{db.update("update runtime_connections set generation='replacement' where instance_key=?",firstInstance);doAnswer(a->{var runtime=db.queryForMap("select id from agent_runtime_instances where instance_key=?",firstInstance);return new RuntimeConnectionService.Access(firstInstance,first,"replacement","runtime-"+runtime.get("id"));}).when(connections).authenticate(eq(firstInstance),anyString());}
                case "GENERATION"->db.update("update runtime_connections set generation='replacement' where instance_key=?",firstInstance);
                case "UID"->db.update("update agent_runtime_instances set platform_actor_uid='replacement' where instance_key=?",firstInstance);
                case "REVOKED"->db.update("update platform_runtime_creations set revoked=true where instance_key=?",firstInstance);
                case "DEPLOYMENT"->db.update("update substrate_agent_deployments set status='FAILED' where instance_key=?",firstInstance);
                case "TEMPLATE"->db.update("update substrate_agent_deployments set template_name='replacement' where instance_key=?",firstInstance);
                case "INACTIVE"->agents.updateStatus(first,AgentLifecycleStatus.DRAFT);
                case "DISABLED"->org.springframework.test.util.ReflectionTestUtils.setField(budgets,"teamExecutionEnabled",false);
                case "SHARED_CAP"->budgets.setLimit(new BudgetLedger.Limit("PLATFORM","default","MODEL_CALLS",0L,0,0),"fixture");
            }
            if(Set.of("TOOLS","BOUND","TASK_HEADER","LEGACY").contains(change)) {
                var expected=json.createObjectNode().put("model","test-model").put("max_tokens",change.equals("BOUND")?129:128).put("temperature",0).put("stream",false);
                expected.set("messages",json.createArrayNode().add(json.createObjectNode().put("role","system").put("content",(String)d.body().get("systemPrompt"))).add(json.createObjectNode().put("role","user").put("content",(String)d.body().get("input"))));
                if(change.equals("TOOLS"))expected.set("tools",json.createArrayNode());
                if(change.equals("TASK_HEADER")){var req=new MockHttpServletRequest();req.addHeader("X-EAP-Task-Key","team-evolution:"+f.id());RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));}
                var dispatch=d;var access=connections.authenticate(firstInstance,"synthetic-fixture");
                try {if(change.equals("LEGACY"))budgets.admitModel(expected,first,access.endpointKey(),null,(String)dispatch.body().get("traceId"),"fallback");
                    else RuntimeModelAccess.call(access,()->budgets.admitModel(expected,first,access.endpointKey(),null,(String)dispatch.body().get("traceId"),"fallback"));}
                finally{RequestContextHolder.resetRequestAttributes();}
                throw new AssertionError("Gateway must reject");
            }
            if(change.equals("REAPPROVED")){var original=d;assertThatThrownBy(()->runner(original)).hasMessage("原计划机器身份epoch已变化");return new AgentExperimentClient.Receipt("UNKNOWN","RECEIPT_UNKNOWN",null);}
            return runner(d);
        });
        assertThat(execute(f).state()).isEqualTo("UNKNOWN");verifyNoInteractions(upstream);assertThat(count("budget_reservations")).isZero();
    }
    @Test void centralPermissionsProtectExecutionAndMetadataHasNoContent() throws Exception {
        var f=prepared("permissions");String path="/api/team-evolutions/"+f.id()+"/evaluations/"+f.evaluation()+"/execution";
        for(String role:List.of("VIEWER","OPERATOR","GOVERNANCE_ADMIN"))mvc.perform(post(path).with(jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_"+role))).contentType("application/json").content(executeRequest().toString())).andExpect(status().isForbidden());
        mvc.perform(post(path).with(jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))).contentType("application/json").content(executeRequest().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("RECEIPTS_COMPLETE"));
        mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))).andExpect(status().isOk()).andExpect(jsonPath("$.data.output").doesNotExist());
    }
    ObjectNode scoreRequest(){return json.createObjectNode().put("requestKey","original-score");}
    ObjectNode frontierRequest(){return json.createObjectNode().put("requestKey","original-frontier");}
    TeamEvolutionScoringService.Score score(Fixture f){return scoring.score(f.id(),f.evaluation(),scoreRequest(),"owner");}
    Fixture completed(String key){var f=prepared(key);assertThat(execute(f).state()).isEqualTo("RECEIPTS_COMPLETE");return f;}
    @Test void platformScoreUsesExactFinalOutputFullBatchAndSettledTwoRoleCosts() {
        var f=completed("platform-score");clearInvocations(client,upstream);var result=score(f);
        assertThat(result.denominator()).isEqualTo(1);assertThat(result.passed()).isEqualTo(1);assertThat(result.qualityBps()).isEqualTo(10000);
        assertThat(result.inputTokens()).isEqualTo(4);assertThat(result.outputTokens()).isEqualTo(6);assertThat(result.totalTokens()).isEqualTo(10);
        assertThat(result.usageSource()).isEqualTo("GATEWAY_SETTLED");assertThat(result.latencyProtocol()).isEqualTo("PLATFORM_SAMPLE_RESPONSIBILITY_MICROS_V1");
        assertThat(result.samples()).hasSize(1);assertThat(result.totalLatencyMicros()).isGreaterThanOrEqualTo(0);
        assertThat(result.proofDigest()).matches("[a-f0-9]{64}");assertThat(score(f)).isEqualTo(result);assertThat(scoring.get(f.id(),f.evaluation().toUpperCase(Locale.ROOT))).isEqualTo(result);
        verifyNoInteractions(client,upstream);
    }
    @ParameterizedTest @ValueSource(strings={"answer VALIDATION ","answer validation","answer\nVALIDATION"})
    void exactTextDoesNotNormalizeOrTrimFinalAnswer(String answer) {
        var f=prepared("exact-text");when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(response(answer,true)));
        assertThat(execute(f).state()).isEqualTo("RECEIPTS_COMPLETE");var result=score(f);
        assertThat(result.passed()).isZero();assertThat(result.qualityBps()).isZero();assertThat(result.totalTokens()).isEqualTo(10);
        var view=scoring.frontier(f.id());assertThat(view.decisions()).containsEntry(result.candidateId(),"QUALITY_GATE_REJECTED");assertThat(view.frontier()).isEmpty();
    }
    @Test void sampleResponsibilityStartsBeforeFirstReadinessAndIncludesBothRolesAndHandoff() {
        var f=prepared("timing");doAnswer(i->{assertThat(count("team_evolution_sample_responsibilities")).isEqualTo(1);return profile(i.getArgument(0));}).when(client).inspectProfile(anyString());
        assertThat(execute(f).state()).isEqualTo("RECEIPTS_COMPLETE");fixedTiming(f,2_000_123);
        var result=score(f);assertThat(result.totalLatencyMicros()).isEqualTo(2_000_123);assertThat(result.samples().getFirst().latencyMicros()).isEqualTo(2_000_123);
    }
    void fixedTiming(Fixture f,long micros) {
        Instant start=Instant.now().minusSeconds(10);Instant end=start.plusNanos(micros*1000);
        db.update("update team_evolution_executions set started_at=?,finished_at=? where evaluation_id=?",java.sql.Timestamp.from(start.minusSeconds(1)),java.sql.Timestamp.from(end.plusMillis(1)),f.evaluation());
        db.update("update team_evolution_sample_responsibilities set started_at=? where evaluation_id=?",java.sql.Timestamp.from(start),f.evaluation());
        var rows=db.queryForList("select d.operation_key,d.reservation_id,s.role_ordinal from team_evolution_dispatches d join team_evolution_slots s on s.operation_key=d.operation_key where d.evaluation_id=? order by s.role_ordinal",f.evaluation());
        for(var row:rows){boolean firstRole=((Number)row.get("role_ordinal")).intValue()==0;Instant sent=firstRole?start.plusMillis(10):start.plusMillis(500),finish=firstRole?start.plusMillis(100):end;
            db.update("update team_evolution_dispatches set started_at=?,finished_at=? where operation_key=?",java.sql.Timestamp.from(sent),java.sql.Timestamp.from(finish),row.get("operation_key"));
            db.update("update budget_reservations set created_at=?,settled_at=? where id=?",java.sql.Timestamp.from(sent),java.sql.Timestamp.from(finish),row.get("reservation_id"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"UNKNOWN","FAILED","NOT_DISPATCHED"})
    void incompleteOriginalBatchCannotProduceScoreOrZeroCostCandidate(String state) {
        var f=prepared("incomplete");
        if(state.equals("UNKNOWN"))doAnswer(i->{throw new IllegalStateException();}).when(client).executeStateless(any());
        if(state.equals("FAILED"))db.update("update platform_runtime_creations set revoked=true where instance_key=?",firstInstance);
        if(!state.equals("NOT_DISPATCHED"))assertThat(execute(f).state()).isEqualTo(state);
        assertThatThrownBy(()->score(f)).hasMessage("TEAM_SCORE_EVIDENCE_INCOMPLETE");assertThat(count("team_evolution_scores")).isZero();
        assertThat(scoring.frontier(f.id()).decisions().values()).containsExactly("EVIDENCE_INCOMPLETE");
        assertThatThrownBy(()->scoring.seal(f.id(),frontierRequest(),"owner")).hasMessage("TEAM_BASELINE_SCORE_REQUIRED");
    }
    @ParameterizedTest @ValueSource(strings={"MARKER","HOLDER","CLOCK","BODY","OUTPUT","PROFILE","ROLE","RESERVATION","SETTLEMENT","SCOPE","BOUND","USAGE","STATE","BATCH"})
    void alteredOrMissingOriginalProofIsRejectedWithoutWritingScore(String change) {
        var f=completed("proof-"+change);String operation=db.queryForObject("select operation_key from team_evolution_slots where evaluation_id=? and role_ordinal=0",String.class,f.evaluation());
        switch(change) {
            case "MARKER"->db.update("delete from team_evolution_sample_responsibilities where evaluation_id=?",f.evaluation());
            case "HOLDER"->db.update("update team_evolution_sample_responsibilities set holder='replacement' where evaluation_id=?",f.evaluation());
            case "CLOCK"->db.update("update team_evolution_sample_responsibilities set started_at=? where evaluation_id=?",java.sql.Timestamp.from(Instant.now().plusSeconds(10)),f.evaluation());
            case "BODY"->db.update("update team_evolution_dispatches set body_json='{}' where operation_key=?",operation);
            case "OUTPUT"->db.update("update team_evolution_dispatches set output_json='\"replacement\"' where operation_key=?",operation);
            case "PROFILE"->db.update("update team_evolution_dispatches set profile_digest=? where operation_key=?","e".repeat(64),operation);
            case "ROLE"->db.update("update team_evolution_dispatches set actor_uid='replacement' where operation_key=?",operation);
            case "RESERVATION"->db.update("update budget_reservations set task_key='unrelated' where id=(select reservation_id from team_evolution_dispatches where operation_key=?)",operation);
            case "SETTLEMENT"->db.update("delete from budget_settlements where reservation_id=(select reservation_id from team_evolution_dispatches where operation_key=?)",operation);
            case "SCOPE"->db.update("delete from budget_reservation_items where reservation_id=(select reservation_id from team_evolution_dispatches where operation_key=?) and account_id in(select id from budget_accounts where scope_type='TASK')",operation);
            case "BOUND"->db.update("update budget_reservations set output_bound=256 where id=(select reservation_id from team_evolution_dispatches where operation_key=?)",operation);
            case "USAGE"->db.update("update budget_reservations set input_tokens=null where id=(select reservation_id from team_evolution_dispatches where operation_key=?)",operation);
            case "STATE"->db.update("update budget_reservations set state='VIOLATION' where id=(select reservation_id from team_evolution_dispatches where operation_key=?)",operation);
            case "BATCH"->{db.update("delete from team_evolution_dispatches where operation_key=?",operation);db.update("delete from team_evolution_slots where operation_key=?",operation);}
        }
        clearInvocations(client,upstream);assertThatThrownBy(()->score(f)).hasMessage("TEAM_SCORE_EVIDENCE_INCOMPLETE");assertThat(count("team_evolution_scores")).isZero();verifyNoInteractions(client,upstream);
    }
    @Test void originalScoreMayBeReadAfterRevocationWithoutNewAuthorityOrSpend() {
        var f=completed("score-replay");var result=score(f);db.update("update platform_runtime_creations set revoked=true where instance_key=?",firstInstance);
        clearInvocations(client,upstream);assertThat(score(f)).isEqualTo(result);
        assertThatThrownBy(()->scoring.score(f.id(),f.evaluation(),json.createObjectNode().put("requestKey","replacement"),"owner")).hasMessage("TEAM_ORIGINAL_SCORE_REQUIRED");
        assertThatThrownBy(()->scoring.score(f.id(),f.evaluation(),scoreRequest(),"other")).hasMessage("TEAM_ORIGINAL_PRINCIPAL_REQUIRED");
        var fields=scoreRequest().put("score",1);assertThatThrownBy(()->scoring.score(f.id(),f.evaluation(),fields,"owner")).hasMessage("TEAM_FIELDS_REJECTED");verifyNoInteractions(client,upstream);
    }
    @Test void concurrentScoreRequestsPersistOnlyOneOriginalResult() throws Exception {
        var f=completed("score-concurrent");var pool=Executors.newFixedThreadPool(4);
        try {var tasks=new ArrayList<Future<TeamEvolutionScoringService.Score>>();for(int n=0;n<4;n++)tasks.add(pool.submit(()->score(f)));var expected=tasks.getFirst().get(20,TimeUnit.SECONDS);for(var task:tasks)assertThat(task.get(20,TimeUnit.SECONDS)).isEqualTo(expected);}
        finally{pool.shutdownNow();}assertThat(count("team_evolution_scores")).isEqualTo(1);
    }
    Fixture candidate(Fixture base,String prompt,String requestKey) {
        var root=contracts.get(base.id());String parent=root.baselineId();
        var c=contracts.propose(base.id(),json.createObjectNode().put("requestKey",requestKey).put("parentId",parent).set("configuration",json.createObjectNode().set("rolePrompts",json.createObjectNode().put("extractor",prompt).put("classifier","classifier baseline"))),"owner");
        assertThat(c.disposition()).isEqualTo("ACCEPTED");var e=contracts.enroll(base.id(),json.createObjectNode().put("requestKey","eval-"+requestKey).put("candidateId",c.candidateId()).put("split","VALIDATION"),"owner");return new Fixture(base.id(),e.id());
    }
    @Test void trustedFrontierDominanceSelectionAndSealUseAllRegisteredCandidates() {
        var base=completed("frontier");fixedTiming(base,2_000_000);var b=score(base);
        var lower=candidate(base,"extractor improved","lower-cost");when(upstream.chatCompletions(any())).thenAnswer(i->{var body=i.getArgument(0,JsonNode.class);var result=(ObjectNode)response(body.path("messages").path(0).path("content").asText().startsWith("classifier")?"answer VALIDATION":"完整前序输出",true);result.set("usage",json.createObjectNode().put("prompt_tokens",1).put("completion_tokens",1));return org.springframework.http.ResponseEntity.ok(result);});
        assertThat(execute(lower).state()).isEqualTo("RECEIPTS_COMPLETE");fixedTiming(lower,2_000_000);var best=score(lower);
        var incomplete=candidate(base,"extractor never sent","unrun");String missing=contracts.evaluation(base.id(),incomplete.evaluation()).candidateId();
        var result=scoring.seal(base.id(),frontierRequest(),"owner");assertThat(result.sealed()).isTrue();assertThat(result.candidates()).hasSize(3);
        assertThat(result.decisions()).containsEntry(b.candidateId(),"DOMINATED").containsEntry(best.candidateId(),"FRONTIER").containsEntry(missing,"EVIDENCE_INCOMPLETE");
        assertThat(result.frontier()).containsExactly(best.candidateId());assertThat(result.selectedCandidateId()).isEqualTo(best.candidateId());assertThat(result.recommendation()).isEqualTo("NO_ADOPTION_HOLDOUT_REQUIRED");
        assertThat(result.costs().modelCalls()).isEqualTo(4);assertThat(result.costs().knownOutputTokens()).isEqualTo(8);
        clearInvocations(client,upstream);assertThat(scoring.seal(base.id(),frontierRequest(),"owner")).isEqualTo(result);assertThat(scoring.frontier(base.id())).isEqualTo(result);assertThat(score(base)).isEqualTo(b);
        assertThatThrownBy(()->execute(incomplete)).hasMessage("TEAM_VALIDATION_FRONTIER_SEALED");
        assertThatThrownBy(()->contracts.enroll(base.id(),json.createObjectNode().put("requestKey","new-eval").put("candidateId",missing).put("split","VALIDATION"),"owner")).hasMessage("TEAM_VALIDATION_FRONTIER_SEALED");
        assertThatThrownBy(()->contracts.propose(base.id(),json.createObjectNode().put("requestKey","late").put("parentId",b.candidateId()).set("configuration",json.createObjectNode()),"owner")).hasMessage("TEAM_VALIDATION_FRONTIER_SEALED");
        assertThatThrownBy(()->scoring.seal(base.id(),json.createObjectNode().put("requestKey","replacement"),"owner")).hasMessage("TEAM_ORIGINAL_FRONTIER_REQUIRED");verifyNoInteractions(client,upstream);
    }
    @Test void exactTiesRemainOnFrontierAndSelectionUsesCandidateIdAsFinalTieBreak() {
        var base=completed("ties");var next=candidate(base,"extractor equivalent","equivalent");assertThat(execute(next).state()).isEqualTo("RECEIPTS_COMPLETE");fixedTiming(base,2_000_000);fixedTiming(next,2_000_000);var a=score(base);var b=score(next);
        var result=scoring.seal(base.id(),frontierRequest(),"owner");assertThat(result.frontier()).containsExactlyElementsOf(new TreeSet<>(List.of(a.candidateId(),b.candidateId())));assertThat(result.selectedCandidateId()).isEqualTo(result.frontier().getFirst());
    }
    @Test void baselineQualityGateRejectsCheapWrongCandidateAndIncludesItsCosts() {
        var base=completed("quality-floor");score(base);var next=candidate(base,"extractor wrong","wrong");when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(response("wrong",true)));assertThat(execute(next).state()).isEqualTo("RECEIPTS_COMPLETE");var wrong=score(next);
        var result=scoring.seal(base.id(),frontierRequest(),"owner");assertThat(result.decisions()).containsEntry(wrong.candidateId(),"QUALITY_GATE_REJECTED");assertThat(result.costs().modelCalls()).isEqualTo(4);assertThat(result.costs().knownOutputTokens()).isEqualTo(12);
    }
    @Test void missingBaselineAndCompleteUnscoredCandidatesCannotBeSilentlySealed() {
        var base=completed("unscored");var next=candidate(base,"extractor better","complete");assertThat(execute(next).state()).isEqualTo("RECEIPTS_COMPLETE");var scored=score(next);
        assertThat(scoring.frontier(base.id()).decisions()).containsEntry(scored.candidateId(),"BASELINE_UNCONFIRMED");assertThatThrownBy(()->scoring.seal(base.id(),frontierRequest(),"owner")).hasMessage("TEAM_BASELINE_SCORE_REQUIRED");score(base);
        var extra=candidate(base,"extractor third","third");assertThat(execute(extra).state()).isEqualTo("RECEIPTS_COMPLETE");assertThatThrownBy(()->scoring.seal(base.id(),frontierRequest(),"owner")).hasMessage("TEAM_COMPLETE_SCORE_REQUIRED");
    }
    @Test void unknownCandidateCannotBecomeFreeFastAndItsOriginalReservationIsRetained() {
        var base=completed("unknown-frontier");var baseline=score(base);var next=candidate(base,"extractor unknown","unknown");when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(response("unknown",false)));assertThat(execute(next).state()).isEqualTo("UNKNOWN");
        var result=scoring.seal(base.id(),frontierRequest(),"owner");assertThat(result.frontier()).containsExactly(baseline.candidateId());assertThat(result.costs().modelCalls()).isEqualTo(3);assertThat(result.costs().unknownCalls()).isEqualTo(1);assertThat(result.costs().unknownUsage()).isTrue();assertThat(result.costs().reservedOutputTokens()).isEqualTo(128);assertThat(result.costs().knownOutputTokens()).isEqualTo(6);
    }
    @Test void runningOriginalEvaluationPreventsSeal() throws Exception {
        var base=completed("running-seal");score(base);var next=candidate(base,"extractor pending","pending");var entered=new CountDownLatch(1);var release=new CountDownLatch(1);doAnswer(i->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return runner(i.getArgument(0));}).when(client).executeStateless(any());var pool=Executors.newSingleThreadExecutor();
        try {var result=pool.submit(()->execute(next));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->scoring.seal(base.id(),frontierRequest(),"owner")).hasMessage("TEAM_VALIDATION_STILL_RUNNING");release.countDown();assertThat(result.get(30,TimeUnit.SECONDS).state()).isEqualTo("RECEIPTS_COMPLETE");}
        finally{release.countDown();pool.shutdownNow();}
    }
    @Test void defaultOffScoringCannotWriteAndHistoricalReadsDoNotInvokeRuntime() {
        var f=completed("disabled-score");org.springframework.test.util.ReflectionTestUtils.setField(scoring,"enabled",false);assertThatThrownBy(()->score(f)).hasMessage("TEAM_SCORING_DISABLED");assertThat(count("team_evolution_scores")).isZero();
    }
    @Test void pendingGatewaySettlementPreventsImmutableCostSnapshot() {
        var base=completed("pending-cost");score(base);var next=candidate(base,"extractor unknown","unconfirmed-cost");when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(response("unknown",false)));assertThat(execute(next).state()).isEqualTo("UNKNOWN");
        db.update("update budget_reservations set state='DISPATCHED' where task_key=? and state='UNKNOWN'","team-evolution:"+base.id());
        assertThatThrownBy(()->scoring.seal(base.id(),frontierRequest(),"owner")).hasMessage("TEAM_COST_SETTLEMENT_PENDING");assertThat(count("team_evolution_frontiers")).isZero();assertThat(scoring.frontier(base.id()).costs().pendingCalls()).isEqualTo(1);
    }
    @Test void centralPermissionsProtectScoresAndSealingWhileViewerReadsOnlyMetrics() throws Exception {
        var f=completed("score-permissions");String root="/api/team-evolutions/"+f.id(),path=root+"/evaluations/"+f.evaluation()+"/score";
        for(String role:List.of("VIEWER","OPERATOR","GOVERNANCE_ADMIN"))for(String endpoint:List.of(path,root+"/frontier"))mvc.perform(post(endpoint).with(jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_"+role))).contentType("application/json").content(scoreRequest().toString())).andExpect(status().isForbidden());
        mvc.perform(post(path).with(jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))).contentType("application/json").content(scoreRequest().toString())).andExpect(status().isOk()).andExpect(jsonPath("$.data.usageSource").value("GATEWAY_SETTLED"));
        for(String endpoint:List.of(path,root+"/frontier")){var text=mvc.perform(get(endpoint).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(text).doesNotContain("完整前序输出","answer VALIDATION","extractor baseline","identityGeneration","businessTask");}
    }
    @ParameterizedTest @ValueSource(ints={6666,6667})
    void fullThreeSampleDenominatorUsesExactFractionsAtQualityThreshold(int floor) {
        var request=contractRequest("fraction").put("minimumQualityBps",floor);var samples=(com.fasterxml.jackson.databind.node.ArrayNode)request.path("samples");
        for(int n=2;n<=3;n++)samples.add(json.createObjectNode().put("id","VALIDATION-"+n).put("groupId","validation-g-"+n).put("split","VALIDATION").put("input","public VALIDATION "+n).put("expected",n==2?"answer VALIDATION":"different answer"));
        var root=contracts.submit(request,"owner");var budget=request("fraction").put("maxCalls",28).put("maxOutputTokens",3584);admission.freeze(root.id(),budget,"owner");
        var e=contracts.enroll(root.id(),json.createObjectNode().put("requestKey","fraction-eval").put("candidateId",root.baselineId()).put("split","VALIDATION"),"owner");var f=new Fixture(root.id(),e.id());assertThat(execute(f).state()).isEqualTo("RECEIPTS_COMPLETE");var result=score(f);
        assertThat(result.denominator()).isEqualTo(3);assertThat(result.passed()).isEqualTo(2);assertThat(result.qualityBps()).isEqualTo(6666);assertThat(result.inputTokens()).isEqualTo(12);assertThat(result.outputTokens()).isEqualTo(18);assertThat(result.samples()).hasSize(3);
        assertThat(scoring.frontier(root.id()).decisions()).containsEntry(root.baselineId(),floor==6666?"FRONTIER":"QUALITY_GATE_REJECTED");verify(client,times(6)).executeStateless(any());
    }
    @Test void qualityAndCostLatencyTradeoffKeepsBothNondominatedCandidates() {
        var base=completed("tradeoff");var next=candidate(base,"extractor fast","fast");assertThat(execute(next).state()).isEqualTo("RECEIPTS_COMPLETE");fixedTiming(base,2_000_000);fixedTiming(next,1_000_000);
        db.update("update budget_reservations set input_tokens=4 where id in(select reservation_id from team_evolution_dispatches where evaluation_id=?)",next.evaluation());db.update("update budget_settlements set input_tokens=4 where reservation_id in(select reservation_id from team_evolution_dispatches where evaluation_id=?)",next.evaluation());
        var b=score(base);var fast=score(next);var view=scoring.frontier(base.id());assertThat(view.frontier()).containsExactlyElementsOf(new TreeSet<>(List.of(b.candidateId(),fast.candidateId())));assertThat(view.selectedCandidateId()).isEqualTo(b.candidateId());
    }
    @Test void punctuationAndCaseSampleIdsUseOneCanonicalDispatchAndScoringOrder() {
        var request=contractRequest("sample-order");((ObjectNode)request.path("samples").path(1)).put("id","v_2");var samples=(com.fasterxml.jackson.databind.node.ArrayNode)request.path("samples");
        samples.add(json.createObjectNode().put("id","V-1").put("groupId","validation-other").put("split","VALIDATION").put("input","public VALIDATION second").put("expected","answer VALIDATION"));
        var root=contracts.submit(request,"owner");admission.freeze(root.id(),request("sample-order").put("maxCalls",20).put("maxOutputTokens",2560),"owner");var e=contracts.enroll(root.id(),json.createObjectNode().put("requestKey","sample-order-eval").put("candidateId",root.baselineId()).put("split","VALIDATION"),"owner");var f=new Fixture(root.id(),e.id());
        assertThat(execute(f).state()).isEqualTo("RECEIPTS_COMPLETE");assertThat(score(f).samples().stream().map(TeamEvolutionScoringService.Sample::sampleId)).containsExactly("V-1","v_2");
        var calls=org.mockito.ArgumentCaptor.forClass(AgentExperimentClient.StatelessDispatch.class);verify(client,times(4)).executeStateless(calls.capture());assertThat(calls.getAllValues().getFirst().body().get("input")).isEqualTo("public VALIDATION second");
    }
    @Test void concurrentSealRequestsPersistOneOriginalFrontier() throws Exception {
        var f=completed("seal-concurrent");score(f);var pool=Executors.newFixedThreadPool(4);
        try{var tasks=new ArrayList<Future<TeamEvolutionScoringService.Frontier>>();for(int n=0;n<4;n++)tasks.add(pool.submit(()->scoring.seal(f.id(),frontierRequest(),"owner")));var first=tasks.getFirst().get(20,TimeUnit.SECONDS);for(var task:tasks)assertThat(task.get(20,TimeUnit.SECONDS)).isEqualTo(first);}
        finally{pool.shutdownNow();}assertThat(count("team_evolution_frontiers")).isEqualTo(1);
    }
    @Test void sealWaitsForGatewayBudgetLockAndIncludesItsCompletedCost() throws Exception {
        var base=completed("late-settlement");score(base);var next=candidate(base,"extractor unconfirmed","late");
        doAnswer(i->{var r=runner(i.getArgument(0));((ObjectNode)r.body()).put("output","unconfirmed");return r;}).when(client).executeStateless(any());assertThat(execute(next).state()).isEqualTo("UNKNOWN");
        long reservation=db.queryForObject("select reservation_id from team_evolution_dispatches where evaluation_id=?",Long.class,next.evaluation());
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var writer=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{db.queryForObject("select id from budget_admission_lock where id=1 for update",Long.class);db.update("update budget_reservations set state='DISPATCHED',input_tokens=null,output_tokens=null where id=?",reservation);locked.countDown();try{assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException ex){throw new IllegalStateException(ex);}db.update("update budget_reservations set state='SETTLED',input_tokens=2,output_tokens=3 where id=?",reservation);return true;}));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();var seal=pool.submit(()->scoring.seal(base.id(),frontierRequest(),"owner"));
            assertThatThrownBy(()->seal.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);release.countDown();assertThat(writer.get(20,TimeUnit.SECONDS)).isTrue();var result=seal.get(20,TimeUnit.SECONDS);
            assertThat(result.costs().modelCalls()).isEqualTo(3);assertThat(result.costs().settledCalls()).isEqualTo(3);assertThat(result.costs().knownOutputTokens()).isEqualTo(9);assertThat(result.costs().pendingCalls()).isZero();
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Autowired TeamEvolutionHoldoutService holdout;
    ObjectNode holdoutRequest(){return json.createObjectNode().put("requestKey","original-holdout");}
    ObjectNode closeRequest(){return json.createObjectNode().put("requestKey","original-close");}
    TeamEvolutionHoldoutService.Holdout sealedHoldout(String key) {
        var f=completed(key);score(f);scoring.seal(f.id(),frontierRequest(),"owner");
        return holdout.freeze(f.id(),holdoutRequest(),"owner");
    }
    long holdoutRoot(TeamEvolutionHoldoutService.Holdout h){return db.queryForObject("select contract_id from team_evolution_evaluations where id=?",Long.class,h.baseline().id());}
    Fixture batch(long root,TeamEvolutionService.Evaluation e){return new Fixture(root,e.id());}
    void holdoutAnswers(){when(upstream.chatCompletions(any())).thenAnswer(i->{var b=i.getArgument(0,JsonNode.class);return b==null?null:org.springframework.http.ResponseEntity.ok(response(b.path("messages").path(0).path("content").asText().startsWith("classifier")?"answer HOLDOUT":"完整前序输出",true));});}
    @Test void independentHoldoutArmsUseDistinctOriginalSlotsEvenWhenBaselineIsSelected() {
        var h=sealedHoldout("independent-arms");long root=holdoutRoot(h);holdoutAnswers();
        assertThat(h.baseline().candidateId()).isEqualTo(h.selected().candidateId());assertThat(h.baseline().id()).isNotEqualTo(h.selected().id());
        assertThat(h.baseline().slots().stream().map(TeamEvolutionService.Slot::traceId)).doesNotContainAnyElementsOf(h.selected().slots().stream().map(TeamEvolutionService.Slot::traceId).toList());
        assertThat(h.baseline().split()).isEqualTo("HOLDOUT");assertThat(h.baseline().arm()).isEqualTo("BASELINE");assertThat(h.selected().arm()).isEqualTo("SELECTED");
        for(var e:List.of(h.baseline(),h.selected())){var f=batch(root,e);assertThat(execute(f).state()).isEqualTo("RECEIPTS_COMPLETE");var s=score(f);assertThat(s.split()).isEqualTo("HOLDOUT");assertThat(s.batchDigest()).isEqualTo(h.holdoutDigest());assertThat(s.passed()).isEqualTo(1);}
        var finalResult=holdout.close(root,closeRequest(),"owner");assertThat(finalResult.recommendation()).isEqualTo("KEEP_BASELINE");assertThat(finalResult.completeEvidence()).isTrue();
        assertThat(finalResult.costs().modelCalls()).isEqualTo(6);assertThat(finalResult.costs().knownOutputTokens()).isEqualTo(18);assertThat(finalResult.proposalModelCalls()).isZero();assertThat(finalResult.adoptionAvailable()).isFalse();assertThat(finalResult.stableBenefitVerified()).isFalse();
        assertThat(scoring.frontier(root).scores()).hasSize(1);assertThat(holdout.getClosure(root)).isEqualTo(finalResult);
        clearInvocations(client,upstream);assertThat(holdout.freeze(root,holdoutRequest(),"owner")).isEqualTo(holdout.get(root));assertThat(holdout.close(root,closeRequest(),"owner")).isEqualTo(finalResult);execute(batch(root,h.baseline()));score(batch(root,h.baseline()));verifyNoInteractions(client,upstream);
    }
    @Test void selectedHoldoutCanSupportOnlyFrozenCandidateWithoutApplyingIt() {
        var base=completed("candidate-holdout");fixedTiming(base,2_000_123);score(base);
        var next=candidate(base,"extractor selected","selected-holdout");assertThat(execute(next).state()).isEqualTo("RECEIPTS_COMPLETE");fixedTiming(next,1_000_123);score(next);
        var frontier=scoring.seal(base.id(),frontierRequest(),"owner");assertThat(frontier.selectedCandidateId()).isEqualTo(contracts.evaluation(base.id(),next.evaluation()).candidateId());
        var h=holdout.freeze(base.id(),holdoutRequest(),"owner");holdoutAnswers();
        var b=batch(base.id(),h.baseline());var s=batch(base.id(),h.selected());execute(b);fixedTiming(b,2_000_123);score(b);execute(s);fixedTiming(s,1_000_123);score(s);
        var closed=holdout.close(base.id(),closeRequest(),"owner");assertThat(closed.recommendation()).isEqualTo("BOUNDED_HOLDOUT_SUPPORT_NO_ADOPTION");assertThat(closed.costs().modelCalls()).isEqualTo(8);assertThat(closed.batches()).hasSize(4);assertThat(closed.adoptionAvailable()).isFalse();assertThat(scoring.frontier(base.id())).isEqualTo(frontier);
    }
    @Test void modelDraftThroughIndependentHoldoutChargesOneRootAndNeverAppliesCandidate() {
        var root=contracts.submit(contractRequest("proposal-to-holdout").put("maxCandidates",2).put("maxAttempts",2).put("maxGeneration",1),"owner");
        admission.freeze(root.id(),request("proposal-to-holdout").put("proposalMode","MODEL_SINGLE_ROLE")
                .put("proposalUseConfirmed",true).put("maxProposalCalls",1).put("maxProposalTokens",512)
                .put("maxCalls",9).put("maxOutputTokens",1536),"owner");
        var liveConfigs=db.queryForList("select agent_id,config_json from substrate_agent_deployments where agent_id in (?,?) order by agent_id",first,second);
        var proposalRequest=json.createObjectNode().put("requestKey","original-draft").put("parentId",root.baselineId()).put("role","extractor");
        when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(response("{\"system_prompt\":\"extractor improved from public training\"}",true)));
        var draft=proposals.propose(root.id(),proposalRequest,"owner");assertThat(draft.state()).isEqualTo("COMPLETED");
        assertThat(count("team_evolution_candidates")).isEqualTo(1);assertThat(count("team_evolution_evaluations")).isZero();
        var modelRequests=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(upstream).chatCompletions(modelRequests.capture());
        assertThat(modelRequests.getValue().toString()).contains("public TRAIN","answer TRAIN").doesNotContain("public VALIDATION","answer VALIDATION","public HOLDOUT","answer HOLDOUT");
        var content=proposals.content(root.id(),draft.requestKey(),"owner");
        var registration=json.createObjectNode().put("requestKey","reviewed-candidate").put("parentId",(String)content.get("parentId"));
        registration.set("configuration",(JsonNode)content.get("configuration"));
        var candidate=contracts.propose(root.id(),registration,"owner");assertThat(candidate.disposition()).isEqualTo("ACCEPTED");
        assertThat(contracts.get(root.id()).attempts()).isEqualTo(2);
        when(upstream.chatCompletions(any())).thenAnswer(i->{var b=i.getArgument(0,JsonNode.class);return b==null?null:org.springframework.http.ResponseEntity.ok(response(b.path("messages").path(0).path("content").asText().startsWith("classifier")?"answer VALIDATION":"完整前序输出",true));});
        for(String candidateId:List.of(root.baselineId(),candidate.candidateId())) {
            var e=contracts.enroll(root.id(),json.createObjectNode().put("requestKey",candidateId.equals(root.baselineId())?"validate-baseline":"validate-candidate").put("candidateId",candidateId).put("split","VALIDATION"),"owner");
            var f=batch(root.id(),e);assertThat(execute(f).state()).isEqualTo("RECEIPTS_COMPLETE");
            fixedTiming(f,candidateId.equals(root.baselineId())?2_000_123:1_000_123);score(f);
        }
        var frontier=scoring.seal(root.id(),frontierRequest(),"owner");assertThat(frontier.selectedCandidateId()).isEqualTo(candidate.candidateId());
        assertThat(frontier.costs().modelCalls()).isEqualTo(5);
        var h=holdout.freeze(root.id(),holdoutRequest(),"owner");holdoutAnswers();
        assertThat(h.baseline().id()).isNotEqualTo(h.selected().id());
        for(var e:List.of(h.baseline(),h.selected())) {
            var f=batch(root.id(),e);assertThat(execute(f).state()).isEqualTo("RECEIPTS_COMPLETE");
            fixedTiming(f,e.candidateId().equals(root.baselineId())?2_000_123:1_000_123);score(f);
        }
        var closed=holdout.close(root.id(),closeRequest(),"owner");
        assertThat(closed.completeEvidence()).isTrue();assertThat(closed.recommendation()).isEqualTo("BOUNDED_HOLDOUT_SUPPORT_NO_ADOPTION");
        assertThat(closed.proposalModelCalls()).isEqualTo(1);assertThat(closed.costs().modelCalls()).isEqualTo(9);
        assertThat(closed.costs().settledCalls()).isEqualTo(9);assertThat(closed.costs().knownOutputTokens()).isEqualTo(27);
        assertThat(closed.batches()).hasSize(4);assertThat(closed.adoptionAvailable()).isFalse();assertThat(closed.stableBenefitVerified()).isFalse();
        assertThat(db.queryForList("select distinct task_key from budget_reservations",String.class)).containsExactly("team-evolution:"+root.id());
        assertThat(db.queryForObject("select used_amount from budget_accounts where scope_type='TASK' and scope_key=? and unit='MODEL_CALLS'",Long.class,"team-evolution:"+root.id())).isEqualTo(9);
        assertThat(db.queryForObject("select used_amount from budget_accounts where scope_type='TASK' and scope_key=? and unit='OUTPUT_TOKENS'",Long.class,"team-evolution:"+root.id())).isEqualTo(27);
        assertThat(db.queryForObject("select sum(reserved_amount) from budget_accounts where scope_type='TASK' and scope_key=?",Long.class,"team-evolution:"+root.id())).isZero();
        assertThat(db.queryForList("select agent_id,config_json from substrate_agent_deployments where agent_id in (?,?) order by agent_id",first,second)).isEqualTo(liveConfigs);
        verify(client,times(8)).executeStateless(any());verify(upstream,times(9)).chatCompletions(modelRequests.capture());
        for(var body:modelRequests.getAllValues())assertThat(body.has("thinking")).isFalse();
        clearInvocations(upstream,client);
        assertThat(proposals.propose(root.id(),proposalRequest,"owner")).isEqualTo(draft);
        assertThat(contracts.propose(root.id(),registration,"owner")).isEqualTo(candidate);
        assertThat(holdout.close(root.id(),closeRequest(),"owner")).isEqualTo(closed);
        for(var e:List.of(h.baseline(),h.selected())){execute(batch(root.id(),e));score(batch(root.id(),e));}
        verifyNoInteractions(upstream,client);
    }
    @Test void failedHoldoutQualityKeepsBaselineAndCannotReselectValidationFrontier() {
        var base=completed("bad-holdout");fixedTiming(base,2_000_123);score(base);var next=candidate(base,"extractor chosen","chosen");execute(next);fixedTiming(next,1_000_123);score(next);var frozen=scoring.seal(base.id(),frontierRequest(),"owner");var h=holdout.freeze(base.id(),holdoutRequest(),"owner");
        holdoutAnswers();var b=batch(base.id(),h.baseline());execute(b);score(b);
        when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(response("wrong",true)));var s=batch(base.id(),h.selected());execute(s);assertThat(score(s).passed()).isZero();
        var closed=holdout.close(base.id(),closeRequest(),"owner");assertThat(closed.recommendation()).isEqualTo("KEEP_BASELINE");assertThat(closed.costs().knownOutputTokens()).isEqualTo(24);assertThat(scoring.frontier(base.id())).isEqualTo(frozen);
    }
    @Test void unknownHoldoutPreservesPaidCallAndOutputReservationWithoutReplay() {
        var h=sealedHoldout("unknown-holdout");long root=holdoutRoot(h);when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(response("unknown",false)));
        var b=batch(root,h.baseline());assertThat(execute(b).state()).isEqualTo("UNKNOWN");assertThatThrownBy(()->score(b)).hasMessage("TEAM_SCORE_EVIDENCE_INCOMPLETE");
        var closed=holdout.close(root,closeRequest(),"owner");assertThat(closed.recommendation()).isEqualTo("INCOMPLETE_NO_ADOPTION");assertThat(closed.completeEvidence()).isFalse();assertThat(closed.costs().unknownCalls()).isEqualTo(1);assertThat(closed.costs().reservedOutputTokens()).isEqualTo(128);assertThat(closed.costs().modelCalls()).isEqualTo(3);
        clearInvocations(client,upstream);execute(b);assertThatThrownBy(()->execute(batch(root,h.selected()))).hasMessage("TEAM_ROOT_CLOSED");verifyNoInteractions(client,upstream);
    }
    @Test void rootMayCloseUnrunHoldoutAsIncompleteButCannotLaterSpendOrRenew() {
        var h=sealedHoldout("unrun-holdout");long root=holdoutRoot(h);var closed=holdout.close(root,closeRequest(),"owner");assertThat(closed.completeEvidence()).isFalse();assertThat(closed.costs().modelCalls()).isEqualTo(2);
        assertThatThrownBy(()->execute(batch(root,h.baseline()))).hasMessage("TEAM_ROOT_CLOSED");assertThatThrownBy(()->holdout.close(root,json.createObjectNode().put("requestKey","new-close"),"owner")).hasMessage("TEAM_ORIGINAL_CLOSURE_REQUIRED");
    }
    @Test void rootCanCloseBeforeHoldoutEnrollmentAndOriginalClosureRemainsImmutable() {
        var f=completed("close-no-holdout");score(f);scoring.seal(f.id(),frontierRequest(),"owner");assertThat(holdout.close(f.id(),closeRequest(),"owner").holdoutDigest()).isNull();
        assertThatThrownBy(()->holdout.freeze(f.id(),holdoutRequest(),"owner")).hasMessage("TEAM_ROOT_CLOSED");
    }
    @Test void unrunRootClosesWithoutInventingAFrontierOrSelection() {
        var f=prepared("close-before-baseline");
        var closed=holdout.close(f.id(),closeRequest(),"owner");
        assertThat(closed.recommendation()).isEqualTo("INCOMPLETE_NO_ADOPTION");
        assertThat(closed.completeEvidence()).isFalse();
        assertThat(closed.frontierDigest()).isNull();assertThat(closed.selectedCandidateId()).isNull();
        assertThat(closed.costs().modelCalls()).isZero();assertThat(count("team_evolution_frontiers")).isZero();
        assertThat(holdout.close(f.id(),closeRequest(),"owner")).isEqualTo(closed);
        clearInvocations(client,upstream);
        assertThatThrownBy(()->execute(f)).hasMessage("TEAM_ROOT_CLOSED");
        verifyNoInteractions(client,upstream);
    }
    @Test void firstUnknownClosesBeforeBaselineScoreAndRetainsOriginalCommitment() {
        var f=prepared("first-unknown-close");
        when(upstream.chatCompletions(any())).thenReturn(org.springframework.http.ResponseEntity.ok(response("unknown",false)));
        assertThat(execute(f).state()).isEqualTo("UNKNOWN");
        var closed=holdout.close(f.id(),closeRequest(),"owner");
        assertThat(closed.recommendation()).isEqualTo("INCOMPLETE_NO_ADOPTION");assertThat(closed.completeEvidence()).isFalse();
        assertThat(closed.frontierDigest()).isNull();assertThat(closed.selectedCandidateId()).isNull();
        assertThat(closed.costs().modelCalls()).isEqualTo(1);assertThat(closed.costs().unknownCalls()).isEqualTo(1);
        assertThat(closed.costs().reservedOutputTokens()).isEqualTo(128);
        clearInvocations(client,upstream);execute(f);verifyNoInteractions(client,upstream);
        assertThat(holdout.getClosure(f.id())).isEqualTo(closed);
    }
    @Test void unsealedCompleteBatchMustBeScoredBeforeClosingAsIncomplete() {
        var f=completed("unsealed-unscored-close");
        assertThatThrownBy(()->holdout.close(f.id(),closeRequest(),"owner")).hasMessage("TEAM_COMPLETE_SCORE_REQUIRED");
        score(f);var closed=holdout.close(f.id(),closeRequest(),"owner");
        assertThat(closed.completeEvidence()).isFalse();assertThat(closed.frontierDigest()).isNull();
        assertThat(closed.costs().modelCalls()).isEqualTo(2);
        assertThat(count("team_evolution_frontiers")).isZero();
    }
    @Test void completedHoldoutMustBeScoredBeforeFinalCostFreeze() {
        var h=sealedHoldout("unscored-holdout");long root=holdoutRoot(h);holdoutAnswers();var f=batch(root,h.baseline());execute(f);
        assertThatThrownBy(()->holdout.close(root,closeRequest(),"owner")).hasMessage("TEAM_COMPLETE_SCORE_REQUIRED");score(f);assertThat(holdout.close(root,closeRequest(),"owner").completeEvidence()).isFalse();
    }
    @Test void runningHoldoutCannotBeClosedWhileOriginalSenderOwnsResponsibility() throws Exception {
        var h=sealedHoldout("running-holdout");long root=holdoutRoot(h);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);holdoutAnswers();
        when(client.executeStateless(any())).thenAnswer(i->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return runner(i.getArgument(0));});var pool=Executors.newSingleThreadExecutor();
        try{var future=pool.submit(()->execute(batch(root,h.baseline())));assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->holdout.close(root,closeRequest(),"owner")).hasMessage("TEAM_EXECUTION_STILL_RUNNING");release.countDown();assertThat(future.get(30,TimeUnit.SECONDS).state()).isEqualTo("RECEIPTS_COMPLETE");}
        finally{release.countDown();pool.shutdownNow();}
    }
    @Test void concurrentOriginalHoldoutEnrollmentCreatesOnlyTwoIndependentBatches() throws Exception {
        var f=completed("parallel-holdout");score(f);scoring.seal(f.id(),frontierRequest(),"owner");var pool=Executors.newFixedThreadPool(4);
        try{var futures=new ArrayList<Future<TeamEvolutionHoldoutService.Holdout>>();for(int n=0;n<4;n++)futures.add(pool.submit(()->holdout.freeze(f.id(),holdoutRequest(),"owner")));var first=futures.getFirst().get(30,TimeUnit.SECONDS);for(var future:futures)assertThat(future.get(30,TimeUnit.SECONDS)).isEqualTo(first);}finally{pool.shutdownNow();}
        assertThat(count("team_evolution_holdouts")).isEqualTo(1);assertThat(count("team_evolution_evaluations")).isEqualTo(3);assertThat(count("team_evolution_slots")).isEqualTo(6);
    }
    @Test void concurrentOriginalRootClosuresShareOneCostSnapshot() throws Exception {
        var h=sealedHoldout("parallel-close");long root=holdoutRoot(h);var pool=Executors.newFixedThreadPool(4);
        try{var futures=new ArrayList<Future<TeamEvolutionHoldoutService.Closure>>();for(int n=0;n<4;n++)futures.add(pool.submit(()->holdout.close(root,closeRequest(),"owner")));var first=futures.getFirst().get(30,TimeUnit.SECONDS);for(var future:futures)assertThat(future.get(30,TimeUnit.SECONDS)).isEqualTo(first);}finally{pool.shutdownNow();}assertThat(count("team_evolution_closures")).isEqualTo(1);
    }
    @Test void holdoutEnrollmentRequiresSealedSelectionAndOriginalPrincipalAndKey() {
        var f=completed("holdout-authority");score(f);assertThatThrownBy(()->holdout.freeze(f.id(),holdoutRequest(),"owner")).hasMessage("TEAM_SEALED_FRONTIER_REQUIRED");scoring.seal(f.id(),frontierRequest(),"owner");
        assertThatThrownBy(()->holdout.freeze(f.id(),holdoutRequest(),"other")).hasMessage("TEAM_ORIGINAL_PRINCIPAL_REQUIRED");holdout.freeze(f.id(),holdoutRequest(),"owner");
        assertThatThrownBy(()->holdout.freeze(f.id(),json.createObjectNode().put("requestKey","replacement"),"owner")).hasMessage("TEAM_ORIGINAL_HOLDOUT_REQUIRED");
        assertThatThrownBy(()->holdout.close(f.id(),closeRequest(),"other")).hasMessage("TEAM_ORIGINAL_PRINCIPAL_REQUIRED");
    }
    @ParameterizedTest @ValueSource(strings={"candidateId","maxCalls","approvalExpiresAt","traceId","score","usage"})
    void holdoutAndClosureRejectClientSelectionBudgetAndReceiptFields(String field) {
        var h=sealedHoldout("holdout-fields");long root=holdoutRoot(h);var request=holdoutRequest().put(field,"client-value");assertThatThrownBy(()->holdout.freeze(root,request,"owner")).hasMessage("TEAM_FIELDS_REJECTED");assertThatThrownBy(()->holdout.close(root,request,"owner")).hasMessage("TEAM_FIELDS_REJECTED");
    }
    @Test void approvalExpiryCannotBeReplacedByNewHoldoutPhaseOrExecutionKey() {
        var f=completed("holdout-expiry");score(f);scoring.seal(f.id(),frontierRequest(),"owner");db.update("update team_evolution_budget_plans set approval_expires_at=? where contract_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),f.id());
        assertThatThrownBy(()->holdout.freeze(f.id(),holdoutRequest(),"owner")).hasMessage("TEAM_APPROVAL_EXPIRED");assertThat(count("team_evolution_holdouts")).isZero();
    }
    @Test void gatewayHoldoutFlagIsCheckedAgainBeforeAnyUpstreamSpend() {
        var h=sealedHoldout("holdout-gateway-off");long root=holdoutRoot(h);org.springframework.test.util.ReflectionTestUtils.setField(budgets,"teamHoldoutEnabled",false);clearInvocations(upstream);
        assertThat(execute(batch(root,h.baseline())).state()).isEqualTo("UNKNOWN");verifyNoInteractions(upstream);assertThat(count("budget_reservations")).isEqualTo(2);
    }
    @Test void holdoutAndClosureMetadataUseCentralPermissionsWithoutContentLeakage() throws Exception {
        var h=sealedHoldout("holdout-access");long root=holdoutRoot(h);holdout.close(root,closeRequest(),"owner");String path="/api/team-evolutions/"+root;
        for(String role:List.of("VIEWER","OPERATOR","GOVERNANCE_ADMIN"))for(String endpoint:List.of("/holdout","/closure"))mvc.perform(post(path+endpoint).with(jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_"+role))).contentType("application/json").content(holdoutRequest().toString())).andExpect(status().isForbidden());
        for(String endpoint:List.of("/holdout","/closure")){var value=mvc.perform(get(path+endpoint).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(value).doesNotContain("answer HOLDOUT","extractor baseline","完整前序输出","identityGeneration","businessTask");}
        mvc.perform(post(path+"/closure").with(jwt().jwt(j->j.subject("owner")).authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"))).contentType("application/json").content(closeRequest().toString())).andExpect(status().isOk());
    }
    @Test void closureWaitsForBudgetWriterAndIncludesCurrentCostAndAccounts() throws Exception {
        var h=sealedHoldout("close-current");long root=holdoutRoot(h);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var writer=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(s->{
                db.queryForObject("select id from budget_admission_lock where id=1 for update",Long.class);locked.countDown();
                try{assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException ex){throw new RuntimeException(ex);}
                db.update("insert into budget_reservations(invocation_key,fingerprint,agent_id,task_key,trace_id,operation,output_bound,state,fence,input_tokens,output_tokens,created_at,settled_at) values(?,?,?,?,'late-fixture-trace','MODEL',128,'SETTLED','fixture-fence',2,3,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","late-close-"+root,"fixture",first,"team-evolution:"+root);
                db.update("update budget_accounts set used_amount=used_amount+case when unit='MODEL_CALLS' then 1 when unit='OUTPUT_TOKENS' then 3 else 0 end where scope_type='TASK' and scope_key=?","team-evolution:"+root);return true;
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();var closer=pool.submit(()->holdout.close(root,closeRequest(),"owner"));
            assertThatThrownBy(()->closer.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);release.countDown();assertThat(writer.get(20,TimeUnit.SECONDS)).isTrue();
            var result=closer.get(20,TimeUnit.SECONDS);assertThat(result.costs().modelCalls()).isEqualTo(3);assertThat(result.costs().knownOutputTokens()).isEqualTo(9);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void maximumTenHoldoutSamplesConsumeExactlyOriginalFortyTwoCallPlan() {
        var request=contractRequest("max-holdout").put("maxCandidates",1);var samples=(com.fasterxml.jackson.databind.node.ArrayNode)request.path("samples");
        for(int n=2;n<=10;n++)samples.add(json.createObjectNode().put("id","holdout-"+n).put("groupId","holdout-g-"+n).put("split","HOLDOUT").put("input","public holdout "+n).put("expected","answer HOLDOUT"));
        var c=contracts.submit(request,"owner");admission.freeze(c.id(),request("max-holdout").put("maxCalls",42).put("maxOutputTokens",5376),"owner");
        var e=contracts.enroll(c.id(),json.createObjectNode().put("requestKey","max-eval").put("candidateId",c.baselineId()).put("split","VALIDATION"),"owner");var f=batch(c.id(),e);execute(f);score(f);scoring.seal(c.id(),frontierRequest(),"owner");var h=holdout.freeze(c.id(),holdoutRequest(),"owner");holdoutAnswers();
        for(var arm:List.of(h.baseline(),h.selected())){var b=batch(c.id(),arm);assertThat(execute(b).confirmedSlots()).isEqualTo(20);var scored=score(b);assertThat(scored.denominator()).isEqualTo(10);assertThat(scored.passed()).isEqualTo(10);}
        var closed=holdout.close(c.id(),closeRequest(),"owner");assertThat(closed.completeEvidence()).isTrue();assertThat(closed.costs().modelCalls()).isEqualTo(42);assertThat(closed.costs().knownOutputTokens()).isEqualTo(126);assertThat(closed.costs().reservedOutputTokens()).isZero();
    }
    @Test void holdoutFlagBlocksNewEnrollmentAndClosureWhenDisabled() {
        var f=completed("holdout-off");score(f);scoring.seal(f.id(),frontierRequest(),"owner");org.springframework.test.util.ReflectionTestUtils.setField(holdout,"enabled",false);
        assertThatThrownBy(()->holdout.freeze(f.id(),holdoutRequest(),"owner")).hasMessage("TEAM_HOLDOUT_DISABLED");assertThatThrownBy(()->holdout.close(f.id(),closeRequest(),"owner")).hasMessage("TEAM_HOLDOUT_DISABLED");assertThat(count("team_evolution_holdouts")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"REVISION","CAP","MARGIN","USED","RESERVED"})
    void closureCannotHideFrozenRootAccountMismatch(String change) {
        var h=sealedHoldout("account-mismatch");long root=holdoutRoot(h);String column=switch(change){case "REVISION"->"revision";case "CAP"->"limit_amount";case "MARGIN"->"margin_amount";case "USED"->"used_amount";default->"reserved_amount";};
        db.update("update budget_accounts set "+column+"="+column+"+1 where scope_type='TASK' and scope_key=? and unit='OUTPUT_TOKENS'","team-evolution:"+root);
        assertThatThrownBy(()->holdout.close(root,closeRequest(),"owner")).hasMessage("TEAM_ROOT_ACCOUNTING_MISMATCH");assertThat(count("team_evolution_closures")).isZero();
    }
    @Test void newDispatchAndLedgerTimestampsRetainFractionalSeconds() {
        var f=completed("fractional-clock");
        for(String table:List.of("team_evolution_executions","team_evolution_sample_responsibilities","team_evolution_dispatches","budget_reservations")) {
            String column=table.equals("budget_reservations")?"created_at":"started_at";
            var times=db.queryForList("select "+column+" from "+table,java.sql.Timestamp.class);
            assertThat(times).isNotEmpty();assertThat(times.stream().anyMatch(t->t.getNanos()!=0)).as(table+" preserves actual database fractional seconds").isTrue();
        }
        var result=score(f);assertThat(result.totalLatencyMicros()).isPositive();
    }
    @Test void durableReceiptsForBudgetExecutionScoreFrontierHoldoutAndClosureNeverReexecute() {
        var h=sealedHoldout("receipt-pipeline");long root=holdoutRoot(h);holdoutAnswers();
        for(var e:List.of(h.baseline(),h.selected())) {var f=batch(root,e);execute(f);score(f);}
        var closure=holdout.close(root,closeRequest(),"owner");var originalCosts=scoring.costs(root,false);
        long reservations=count("budget_reservations"),dispatches=count("team_evolution_dispatches");
        clearInvocations(client,upstream,connections);
        var budgetReceipt=operations.receipt(TeamEvolutionOperationService.Action.BUDGET,"receipt-pipeline",root,null,"owner");
        assertThat(budgetReceipt.result()).isEqualTo(admission.get(root));
        assertThat(operations.receipt(TeamEvolutionOperationService.Action.EXECUTE,"original-execution",root,h.baseline().id(),"owner").result()).isEqualTo(execution.get(root,h.baseline().id()));
        assertThat(operations.receipt(TeamEvolutionOperationService.Action.SCORE,"original-score",root,h.baseline().id(),"owner").result()).isEqualTo(scoring.get(root,h.baseline().id()));
        assertThat(operations.receipt(TeamEvolutionOperationService.Action.FRONTIER,"original-frontier",root,null,"owner").result()).isEqualTo(scoring.frontier(root));
        assertThat(operations.receipt(TeamEvolutionOperationService.Action.HOLDOUT,"original-holdout",root,null,"owner").result()).isEqualTo(holdout.get(root));
        assertThat(operations.receipt(TeamEvolutionOperationService.Action.CLOSE,"original-close",root,null,"owner").result()).isEqualTo(closure);
        assertThat(operations.receipt(TeamEvolutionOperationService.Action.CLOSE,"different-key",root,null,"owner").state()).isEqualTo("NOT_FOUND");
        assertThat(count("budget_reservations")).isEqualTo(reservations);assertThat(count("team_evolution_dispatches")).isEqualTo(dispatches);assertThat(scoring.costs(root,false)).isEqualTo(originalCosts);
        verifyNoInteractions(client,upstream,connections);
    }
    @Test void closedRootRejectsAnyNewBudgetBeforeInspectingRuntimeEvenIfPlanIsMissing() {
        var root=root("closed-missing-plan");
        // Defensive inconsistent-state fixture: the normal close API already requires an original budget.
        db.update("insert into team_evolution_closures(contract_id,request_key,fingerprint,principal_digest,result_json,created_at) values(?,'original-close',?,?,?,CURRENT_TIMESTAMP)",root.id(),"a".repeat(64),contracts.principal("owner"),"{}");
        clearInvocations(client,connections);
        assertThatThrownBy(()->admission.freeze(root.id(),request("cannot-resurrect"),"owner")).hasMessage("TEAM_ROOT_CLOSED");
        assertThat(count("team_evolution_budget_plans")).isZero();assertThat(count("budget_accounts")).isZero();verifyNoInteractions(client,connections);
    }

}
