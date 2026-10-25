package com.eap.platform.budget;

import com.eap.platform.agent.*;
import com.eap.platform.common.ConflictException;
import com.eap.platform.gateway.*;
import com.eap.platform.governance.*;
import com.eap.platform.observability.*;
import com.eap.platform.telemetry.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import java.util.*;
import java.util.concurrent.*;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.budget.enabled=true","eap.gateway.enabled=true","eap.gateway.ingestion-token=budget-test-token","eap.gateway.upstream-base-url=http://upstream.invalid","eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid/realm","eap.security.jwk-set-uri=https://idp.invalid/jwks","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class BudgetLedgerIntegrationTests {
    @Autowired BudgetLedger ledger;@Autowired AgentService agents;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;@Autowired OpenAiCompatibleGatewayService gateway;@Autowired MockMvc mvc;
    @MockitoBean OpenAiUpstreamClient upstream;@MockitoBean GovernancePreflightService governance;
    @MockitoBean ModelTelemetryService telemetry;@MockitoBean ContentObservationService content;@MockitoBean ToolInvocationService tools;
    Long agent,other;
    @BeforeEach void setup() {
        RequestContextHolder.resetRequestAttributes();
        for(String table:List.of("budget_limit_changes","budget_settlements","budget_reservation_items","budget_reservations","budget_accounts"))db.update("delete from "+table);
        agent=newAgent();other=newAgent();
        when(governance.evaluate(any())).thenReturn(new GovernanceDataDtos.PreflightResponse(1L,"budget-pass",agent,GovernanceDataAction.ALLOW,GovernanceGatewayDirective.PROCEED,true,0,List.of(),List.of(),"test","test","test",GovernanceApprovalStatus.NOT_REQUIRED,null,null,null,null,Instant.now(),"NOT_APPLICABLE"));
        when(upstream.chatCompletions(any())).thenReturn(ResponseEntity.ok(output(2)));
    }
    @AfterEach void done(){RequestContextHolder.resetRequestAttributes();}
    Long newAgent(){var a=agents.create(new AgentDtos.AgentRequest("BUDGET-"+UUID.randomUUID(),"Budget test","","Test","Owner","Test",RiskLevel.LOW));agents.updateStatus(a.id(),AgentLifecycleStatus.ACTIVE);return a.id();}
    JsonNode request(){return json.createObjectNode().put("model","test-model").put("max_tokens",4).set("messages",json.createArrayNode().add(json.createObjectNode().put("role","user").put("content","public sample")));}
    JsonNode output(long count){var o=json.createObjectNode();o.set("usage",json.createObjectNode().put("prompt_tokens",7).put("completion_tokens",count));o.set("choices",json.createArrayNode().add(json.createObjectNode().set("message",json.createObjectNode().put("content","ok"))));return o;}
    BudgetLedger.Ticket reserve(String key){return ledger.admitModel(request(),agent,null,null,"trace",key);}
    BudgetLedger.Account account(String scope,String key,String unit){return ledger.dashboard().accounts().stream().filter(a->scope.equals(a.scopeType())&&key.equals(a.scopeKey())&&unit.equals(a.unit())).findFirst().orElseThrow();}
    BudgetLedger.Account limit(String scope,String key,String unit,Long amount,long revision){return ledger.setLimit(new BudgetLedger.Limit(scope,key,unit,amount,0,revision),"budget-author");}

    @Test void competingReplicasCanCommitOnlyLastAvailableReservation()throws Exception {
        limit("PLATFORM","default","MODEL_CALLS",1L,0);
        var replica=new BudgetLedger(db,json,agents,manager);replica.enabled=true;
        var pool=Executors.newFixedThreadPool(8);var start=new CountDownLatch(1);List<Future<Boolean>> fs=new ArrayList<>();
        try {
            for(int i=0;i<8;i++){int n=i;fs.add(pool.submit(()->{start.await();try{(n%2==0?ledger:replica).admitModel(request(),n%2==0?agent:other,null,null,"trace", "concurrent-"+n);return true;}catch(GatewayException ex){assertThat(ex.getStatus().value()).isEqualTo(429);return false;}}));}
            start.countDown();int passed=0;for(var f:fs)if(f.get(20,TimeUnit.SECONDS))passed++;
            assertThat(passed).isEqualTo(1);assertThat(account("PLATFORM","default","MODEL_CALLS").reservedAmount()).isEqualByComparingTo("1");assertThat(ledger.dashboard().reservations()).hasSize(1);
        } finally {pool.shutdownNow();}
    }
    @Test void shrinkingLimitsPreservesUsedAndInFlightAndNeverResetsAtMidnight() {
        var t=reserve("shrink-1");limit("PLATFORM","default","OUTPUT_TOKENS",2L,0);
        assertThat(account("PLATFORM","default","OUTPUT_TOKENS").reservedAmount()).isEqualByComparingTo("4");
        assertThatThrownBy(()->reserve("shrink-2")).isInstanceOf(GatewayException.class);
        db.update("update budget_reservations set created_at=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(86400)),t.id());
        ledger.complete(t,output(3));assertThat(account("PLATFORM","default","OUTPUT_TOKENS").usedAmount()).isEqualByComparingTo("3");
        limit("PLATFORM","default","OUTPUT_TOKENS",10L,1);assertThat(account("PLATFORM","default","OUTPUT_TOKENS").usedAmount()).isEqualByComparingTo("3");
        reserve("shrink-3");assertThat(account("PLATFORM","default","OUTPUT_TOKENS").reservedAmount()).isEqualByComparingTo("4");
    }
    @Test void missingUsageRetainsOutputReservationAndConsumesExactlyOnePromise() {
        var t=reserve("missing-usage");ledger.complete(t,json.createObjectNode());ledger.unconfirmed(t,"late-finally");
        assertThat(account("PLATFORM","default","MODEL_CALLS").usedAmount()).isEqualByComparingTo("1");assertThat(account("PLATFORM","default","MODEL_CALLS").reservedAmount()).isZero();
        assertThat(account("PLATFORM","default","OUTPUT_TOKENS").usedAmount()).isZero();assertThat(account("PLATFORM","default","OUTPUT_TOKENS").reservedAmount()).isEqualByComparingTo("4");
        assertThat(ledger.dashboard().reservations().getFirst().state()).isEqualTo("UNKNOWN");assertThat(ledger.dashboard().reservations().getFirst().outputTokens()).isNull();
    }
    @Test void duplicateSettlementDoesNotDoubleChargeAndChangedReceiptCannotOverwrite() {
        var t=reserve("settle-once");ledger.complete(t,output(2));ledger.complete(t,output(2));
        assertThat(account("PLATFORM","default","OUTPUT_TOKENS").usedAmount()).isEqualByComparingTo("2");assertThat(db.queryForObject("select count(*) from budget_settlements",Long.class)).isEqualTo(1);
        assertThatThrownBy(()->ledger.complete(t,output(3))).isInstanceOf(ConflictException.class);assertThat(account("PLATFORM","default","OUTPUT_TOKENS").usedAmount()).isEqualByComparingTo("2");
    }
    @Test void staleHolderAndLateReceiptCannotReleaseUnknownCommitment() {
        var t=reserve("lost-holder");assertThatThrownBy(()->ledger.complete(new BudgetLedger.Ticket(t.id(),"wrong-holder"),output(2))).isInstanceOf(ConflictException.class);
        ledger.unconfirmed(t,"REQUEST_TIMEOUT");assertThatThrownBy(()->ledger.complete(t,output(2))).isInstanceOf(ConflictException.class);
        assertThat(account("PLATFORM","default","OUTPUT_TOKENS").reservedAmount()).isEqualByComparingTo("4");
    }
    @Test void repeatedIntentAndChangedPayloadNeverGetAnotherAllowance() {
        reserve("stable-key");assertThatThrownBy(()->reserve("stable-key")).isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("budget_invocation_recorded"));
        var changed=request().deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("max_tokens",3);
        assertThatThrownBy(()->ledger.admitModel(changed,agent,null,null,"trace","stable-key")).isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("budget_invocation_conflict"));assertThat(ledger.dashboard().reservations()).hasSize(1);
    }
    @Test void providerOverrunPreservesActualCountAndBlocksFurtherModelAdmissions() {
        var t=reserve("overrun-1");ledger.complete(t,output(12));
        assertThat(account("PLATFORM","default","OUTPUT_TOKENS").usedAmount()).isEqualByComparingTo("12");assertThat(account("PLATFORM","default","OUTPUT_TOKENS").violated()).isTrue();
        limit("PLATFORM","default","OUTPUT_TOKENS",100L,0);assertThatThrownBy(()->reserve("overrun-2")).isInstanceOf(GatewayException.class);assertThat(ledger.dashboard().reservations().getFirst().outputTokens()).isEqualTo(12);
    }
    @Test void declaredBoundsCannotBeOmittedFractionalOrBypassedWithLargeN() {
        for(String key:List.of("max_tokens","n")) {
            var req=(com.fasterxml.jackson.databind.node.ObjectNode)request();if("max_tokens".equals(key))req.remove(key);else req.put("n",4294967297L);
            assertThatThrownBy(()->ledger.admitModel(req,agent,null,null,"trace",key)).isInstanceOf(GatewayException.class);
        }
        var req=(com.fasterxml.jackson.databind.node.ObjectNode)request();req.put("max_tokens",2.5);assertThatThrownBy(()->ledger.admitModel(req,agent,null,null,"trace","fractional")).isInstanceOf(GatewayException.class);assertThat(ledger.dashboard().reservations()).isEmpty();
    }
    @Test void taskOwnershipIsCheckedBeforeReservationAndSharedWithAgentAndPlatform() {
        db.update("insert into agent_tasks(agent_id,task_type,status,created_at,updated_at) values(?,'TEST','PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",agent);
        long id=db.queryForObject("select max(id) from agent_tasks where agent_id=?",Long.class,agent);
        var t=ledger.admitModel(request(),agent,null,id,"trace","task-proof");ledger.complete(t,output(2));
        assertThat(account("TASK","agent-task:"+id,"MODEL_CALLS").usedAmount()).isEqualByComparingTo("1");assertThat(account("AGENT",agent.toString(),"MODEL_CALLS").usedAmount()).isEqualByComparingTo("1");
        assertThatThrownBy(()->ledger.admitModel(request(),other,null,id,"trace","foreign-task")).isInstanceOf(GatewayException.class);assertThat(ledger.dashboard().reservations()).hasSize(1);
    }
    @Test void workflowTaskHeaderIsBoundToPersistedDispatchIntent() {
        db.update("insert into workflow_definitions(code,name,status,created_at,updated_at) values(?,'Budget workflow','ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","budget-workflow-"+agent);
        long wf=db.queryForObject("select max(id) from workflow_definitions",Long.class);
        db.update("insert into workflow_executions(workflow_id,run_key,status,objective,requester,snapshot_json,steps_json,created_at,started_at) values(?,'budget-run','RUNNING','public task','test','{}',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",wf,"[{\"status\":\"DISPATCHING\",\"taskKey\":\"budget-run:0\",\"agentId\":"+agent+"}]");
        var req=new MockHttpServletRequest();req.addHeader("X-EAP-Task-Key","budget-run:0");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        var t=reserve("workflow-shared");ledger.complete(t,output(2));assertThat(account("TASK","budget-run:0","MODEL_CALLS").usedAmount()).isEqualByComparingTo("1");
        assertThatThrownBy(()->ledger.admitModel(request(),other,null,null,"trace","forged-workflow")).isInstanceOf(GatewayException.class);
    }
    @Test void toolClaimIsAtomicWithOuterPolicyTransactionAndHasItsOwnUnit() {
        limit("AGENT",agent.toString(),"TOOL_CLAIMS",1L,0);
        assertThatThrownBy(()->new TransactionTemplate(manager).executeWithoutResult(status->{ledger.claimTool(agent,"runtime","rollback-claim","fingerprint","trace");throw new IllegalStateException("rollback outer policy");})).isInstanceOf(IllegalStateException.class);
        assertThat(account("AGENT",agent.toString(),"TOOL_CLAIMS").usedAmount()).isZero();assertThat(ledger.dashboard().reservations()).isEmpty();
        ledger.claimTool(agent,"runtime","committed-claim","fingerprint","trace");assertThat(account("AGENT",agent.toString(),"TOOL_CLAIMS").usedAmount()).isEqualByComparingTo("1");
        assertThatThrownBy(()->ledger.claimTool(agent,"runtime","another-claim","fingerprint","trace")).isInstanceOf(GatewayException.class);assertThat(ledger.dashboard().reservations()).hasSize(1);
    }
    @Test void gatewaySettlesProviderUsageBeforeWithholdingOutputAndUnknownNetworkDoesNotFreeBudget() {
        gateway.chatCompletions(request(),agent,null,null,"real-path",GatewayDtos.ClassificationHints.empty());
        assertThat(account("PLATFORM","default","OUTPUT_TOKENS").usedAmount()).isEqualByComparingTo("2");
        when(upstream.chatCompletions(any())).thenThrow(new RestClientException("public simulated disconnect"));
        assertThatThrownBy(()->gateway.chatCompletions(request(),agent,null,null,"lost-path",GatewayDtos.ClassificationHints.empty())).isInstanceOf(GatewayException.class);
        assertThat(account("PLATFORM","default","OUTPUT_TOKENS").reservedAmount()).isEqualByComparingTo("4");assertThat(ledger.dashboard().reservations().getFirst().state()).isEqualTo("UNKNOWN");
    }
    @Test void gatewayRejectsBeforeUpstreamWhenSharedBudgetIsExhausted() {
        limit("PLATFORM","default","MODEL_CALLS",0L,0);
        assertThatThrownBy(()->gateway.chatCompletions(request(),agent,null,null,"zero-budget",GatewayDtos.ClassificationHints.empty())).isInstanceOf(GatewayException.class);
        verify(upstream,never()).chatCompletions(any());assertThat(ledger.dashboard().reservations()).isEmpty();
    }
    @Test void streamingMissingUsageIsUnknownAndExplicitZeroUsageIsKnown() {
        var first=reserve("stream-unknown");ledger.completeStream(first,"data: {\"choices\":[]}\n\ndata: [DONE]\n\n");assertThat(ledger.dashboard().reservations().getFirst().state()).isEqualTo("UNKNOWN");
        var second=reserve("stream-zero");ledger.completeStream(second,"data: {\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}\n\ndata: [DONE]\n\n");assertThat(ledger.dashboard().reservations().getFirst().state()).isEqualTo("SETTLED");assertThat(account("PLATFORM","default","OUTPUT_TOKENS").reservedAmount()).isEqualByComparingTo("4");
    }
    @ParameterizedTest @CsvSource({"VIEWER,false","OPERATOR,false","GOVERNANCE_ADMIN,true","PLATFORM_ADMIN,true","DATA_OWNER,false"})
    void permissionsAndBudgetLimitsMatchCapabilities(String role,boolean allowed)throws Exception {
        var identity=jwt().jwt(j->j.subject("budget-author")).authorities(new SimpleGrantedAuthority("ROLE_"+role));
        mvc.perform(get("/api/platform/me").with(identity)).andExpect(jsonPath("$.data.capabilities.manageBudgets").value(allowed));
        mvc.perform(put("/api/budgets/accounts").with(identity).contentType("application/json").content("{\"scopeType\":\"PLATFORM\",\"scopeKey\":\"default\",\"unit\":\"MODEL_CALLS\",\"limitAmount\":12,\"marginAmount\":1,\"expectedRevision\":0}")).andExpect(status().is(allowed?200:403));
        mvc.perform(get("/api/budgets").with(identity)).andExpect(status().is(role.equals("DATA_OWNER")?403:200));
        if(allowed){assertThat(account("PLATFORM","default","MODEL_CALLS").updatedBy()).isEqualTo("budget-author");assertThatThrownBy(()->limit("PLATFORM","default","MODEL_CALLS",1L,0)).isInstanceOf(ConflictException.class);}
    }
    @Test void gatewayAdmissionHeadersProvePreDispatchRejectionButNotRecordedIntent()throws Exception {
        limit("AGENT",agent.toString(),"MODEL_CALLS",0L,0);
        mvc.perform(post("/api/gateway/v1/chat/completions").header("X-EAP-Gateway-Token","budget-test-token").header("X-EAP-Agent-Id",agent).contentType("application/json").content(request().toString())).andExpect(status().is(429)).andExpect(header().string("X-EAP-Admission-Outcome","REJECTED"));
        limit("AGENT",agent.toString(),"MODEL_CALLS",2L,1);
        var req=post("/api/gateway/v1/chat/completions").header("X-EAP-Gateway-Token","budget-test-token").header("X-EAP-Agent-Id",agent).header("X-EAP-Invocation-Key","http-once-001").contentType("application/json").content(request().toString());
        mvc.perform(req).andExpect(status().isOk());
        mvc.perform(req).andExpect(status().is(409)).andExpect(header().string("X-EAP-Admission-Outcome","RECORDED"));verify(upstream,times(1)).chatCompletions(any());
    }
    @Test void experimentScopeComesFromRunningServerIntentAndCannotBeResetByCallerKey() {
        db.update("insert into agent_experiments(agent_id,instance_key,actor_uid,template,endpoint,image_ref,deployment_version,submission_key,request_fingerprint,profile_digest,profile_json,samples_json,max_tokens,requested_by,status,created_at,updated_at) values(?,'public-instance','public-actor','public-template','public-endpoint','public-image',1,?,'fingerprint','digest','{}','[]',4,'test','RUNNING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",agent,UUID.randomUUID().toString());
        long experiment=db.queryForObject("select max(id) from agent_experiments where agent_id=?",Long.class,agent);
        db.update("insert into agent_experiment_variants(experiment_id,variant_name,config_digest,system_prompt) values(?,'BASELINE','digest','public prompt')",experiment);
        long variant=db.queryForObject("select max(id) from agent_experiment_variants where experiment_id=?",Long.class,experiment);
        String trace=UUID.randomUUID().toString().replace("-", "");
        db.update("insert into agent_experiment_trials(experiment_id,variant_id,sample_index,status,task_key,trace_id,usage_source) values(?,?,0,'RUNNING',?,?,'UNKNOWN')",experiment,variant,"budget-trial-"+trace,trace);
        limit("EXPERIMENT",Long.toString(experiment),"MODEL_CALLS",1L,0);
        var req=new MockHttpServletRequest();req.addHeader("X-EAP-Invocation-Key","experiment-key-one");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        var ticket=ledger.admitModel(request(),agent,null,null,trace,"first");ledger.complete(ticket,output(2));
        assertThat(account("EXPERIMENT",Long.toString(experiment),"MODEL_CALLS").usedAmount()).isEqualByComparingTo("1");
        req.removeHeader("X-EAP-Invocation-Key");req.addHeader("X-EAP-Invocation-Key","experiment-key-two");
        assertThatThrownBy(()->ledger.admitModel(request(),agent,null,null,trace,"second")).isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("budget_invocation_recorded"));
        db.update("update agent_experiment_trials set status='SUCCEEDED' where trace_id=?",trace);
        assertThatThrownBy(()->ledger.admitModel(request(),agent,null,null,trace,"closed")).isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("experiment_not_running"));
        assertThat(ledger.dashboard().reservations()).hasSize(1);
    }

    long optimization(String state,long calls,long tokens) {
        db.update("insert into agent_optimizations(agent_id,instance_key,submission_key,fingerprint,profile_json,dataset_json,dataset_digest,manual_prompt,engine,engine_version,max_tokens,max_metric_calls,max_calls,max_output_tokens,status,requested_by,public_samples_confirmed,reflection_use_confirmed,created_at,updated_at) values(?,'public-instance',?,'fingerprint','{}','[]','digest','public manual','GEPA','gepa-0.1.4',4,8,?,?,?,'test',true,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",agent,UUID.randomUUID().toString(),calls,tokens,state);
        long id=db.queryForObject("select max(id) from agent_optimizations where agent_id=?",Long.class,agent);
        new TransactionTemplate(manager).executeWithoutResult(s->ledger.createOptimizationBudget(id,calls,tokens,"root-author"));return id;
    }
    String reflection(long root,int ordinal) {
        String trace=UUID.randomUUID().toString().replace("-","");
        db.update("insert into agent_optimization_reflections(run_id,ordinal,operation_key,trace_id,fingerprint,prompt_text,status,created_at) values(?,?,?,?,?,'public reflection','DISPATCHED',CURRENT_TIMESTAMP)",root,ordinal,"optimization-reflection-"+trace,trace,"public fingerprint");return trace;
    }
    String optimizationTrial(long root) {
        db.update("insert into agent_experiments(agent_id,instance_key,actor_uid,template,endpoint,image_ref,deployment_version,submission_key,request_fingerprint,profile_digest,profile_json,samples_json,max_tokens,requested_by,status,optimization_run_id,created_at,updated_at) values(?,'public-instance','public-actor','public-template','public-endpoint','public-image',1,?,'fingerprint','digest','{}','[]',4,'test','RUNNING',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",agent,UUID.randomUUID().toString(),root);
        long experiment=db.queryForObject("select max(id) from agent_experiments where agent_id=?",Long.class,agent);
        db.update("insert into agent_experiment_variants(experiment_id,variant_name,config_digest,system_prompt) values(?,'CANDIDATE','digest','public prompt')",experiment);
        long variant=db.queryForObject("select max(id) from agent_experiment_variants where experiment_id=?",Long.class,experiment);
        String trace=UUID.randomUUID().toString().replace("-","");
        db.update("insert into agent_experiment_trials(experiment_id,variant_id,sample_index,status,task_key,trace_id,usage_source) values(?,?,0,'RUNNING',?,?,'UNKNOWN')",experiment,variant,"optimization-trial-"+trace,trace);return trace;
    }
    BudgetLedger.Ticket optimizationReserve(String trace){return ledger.admitModel(request(),agent,null,null,trace,"ignored-caller-key");}

    @Test void reflectionAndTrialShareOneFrozenRootBudget() {
        long root=optimization("SEARCHING",2,20);String reflection=reflection(root,0),trial=optimizationTrial(root);
        var r=optimizationReserve(reflection);ledger.complete(r,output(2));
        var t=optimizationReserve(trial);ledger.complete(t,output(3));
        assertThat(account("TASK","optimization:"+root,"MODEL_CALLS").usedAmount()).isEqualByComparingTo("2");
        assertThat(account("TASK","optimization:"+root,"OUTPUT_TOKENS").usedAmount()).isEqualByComparingTo("5");
        assertThat(account("AGENT",agent.toString(),"MODEL_CALLS").usedAmount()).isEqualByComparingTo("2");
        assertThat(account("PLATFORM","default","MODEL_CALLS").usedAmount()).isEqualByComparingTo("2");
        long experiment=ledger.dashboard().reservations().stream().filter(x->trial.equals(x.traceId())).findFirst().orElseThrow().experimentId();
        assertThat(account("EXPERIMENT",Long.toString(experiment),"MODEL_CALLS").usedAmount()).isEqualByComparingTo("1");
        String next=reflection(root,1);assertThatThrownBy(()->optimizationReserve(next)).isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("budget_exhausted"));
        assertThat(ledger.dashboard().reservations()).hasSize(2);
    }
    @Test void callerCannotDeclareOptimizationRootWithoutServerIntent() {
        long root=optimization("SEARCHING",2,20);var req=new MockHttpServletRequest();req.addHeader("X-EAP-Task-Key","optimization:"+root);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        assertThatThrownBy(()->optimizationReserve("forged-trace")).isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("invalid_budget_task"));
        assertThat(ledger.dashboard().reservations()).isEmpty();assertThat(account("TASK","optimization:"+root,"MODEL_CALLS").reservedAmount()).isZero();
    }
    @Test void changingReflectionCallerKeyCannotAcquireNewRootAllowance() {
        long root=optimization("SEARCHING",2,20);String trace=reflection(root,0);
        var request=new MockHttpServletRequest();request.addHeader("X-EAP-Invocation-Key","caller-key-first");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var ticket=optimizationReserve(trace);ledger.complete(ticket,output(2));
        request.removeHeader("X-EAP-Invocation-Key");request.addHeader("X-EAP-Invocation-Key","caller-key-second");
        assertThatThrownBy(()->optimizationReserve(trace)).isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("budget_invocation_recorded"));
        assertThat(account("TASK","optimization:"+root,"MODEL_CALLS").usedAmount()).isEqualByComparingTo("1");assertThat(ledger.dashboard().reservations()).hasSize(1);
    }
    @Test void optimizationUnknownPersistsAcrossReplicaAndCannotBeResetByManager() {
        long root=optimization("SEARCHING",3,4);String trace=reflection(root,0);
        var ticket=optimizationReserve(trace);ledger.complete(ticket,json.createObjectNode());
        var replica=new BudgetLedger(db,json,agents,manager);replica.enabled=true;
        db.update("update budget_reservations set created_at=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(86400)),ticket.id());
        String next=reflection(root,1);
        assertThatThrownBy(()->replica.admitModel(request(),agent,null,null,next,"new-replica")).isInstanceOf(GatewayException.class);
        assertThat(account("TASK","optimization:"+root,"OUTPUT_TOKENS").reservedAmount()).isEqualByComparingTo("4");
        assertThatThrownBy(()->limit("TASK","optimization:"+root,"OUTPUT_TOKENS",100L,1)).isInstanceOf(ConflictException.class);
        assertThat(account("TASK","optimization:"+root,"OUTPUT_TOKENS").limitAmount()).isEqualTo(4L);
        assertThat(ledger.dashboard().reservations().getFirst().state()).isEqualTo("UNKNOWN");
    }
    @Test void holdoutAcceptsOnlyLinkedTrialsAndClosedOptimizationRejectsEverything() {
        long root=optimization("HOLDOUT",4,20);String reflection=reflection(root,0),trial=optimizationTrial(root);
        assertThatThrownBy(()->optimizationReserve(reflection)).isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("optimization_not_running"));
        var ticket=optimizationReserve(trial);ledger.complete(ticket,output(2));
        db.update("update agent_optimizations set status='COMPLETED' where id=?",root);
        String late=optimizationTrial(root);assertThatThrownBy(()->optimizationReserve(late)).isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("optimization_not_running"));
        assertThat(ledger.dashboard().reservations()).hasSize(1);
    }
    @Test void stoppedRootDoesNotReleaseAnAlreadyDispatchedReservation() {
        long root=optimization("SEARCHING",2,8);String trace=reflection(root,0);var ticket=optimizationReserve(trace);
        db.update("update agent_optimizations set status='UNKNOWN' where id=?",root);
        assertThat(account("TASK","optimization:"+root,"MODEL_CALLS").reservedAmount()).isEqualByComparingTo("1");
        assertThat(account("TASK","optimization:"+root,"OUTPUT_TOKENS").reservedAmount()).isEqualByComparingTo("4");
        ledger.unconfirmed(ticket,"RECEIPT_UNKNOWN");assertThat(account("TASK","optimization:"+root,"OUTPUT_TOKENS").reservedAmount()).isEqualByComparingTo("4");
        assertThatThrownBy(()->optimizationReserve(trace)).isInstanceOf(GatewayException.class);
    }

    @Test void publicSamplesAndReflectionPurposeAreIndependentServerChecks() {
        long root=optimization("SEARCHING",4,20);String reflection=reflection(root,0),trial=optimizationTrial(root);
        db.update("update agent_optimizations set reflection_use_confirmed=false where id=?",root);
        assertThatThrownBy(()->optimizationReserve(reflection)).isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("optimization_purpose_unapproved"));
        var ticket=optimizationReserve(trial);ledger.complete(ticket,output(2));
        db.update("update agent_optimizations set public_samples_confirmed=false where id=?",root);
        String next=optimizationTrial(root);assertThatThrownBy(()->optimizationReserve(next)).isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("optimization_purpose_unapproved"));
        assertThat(ledger.dashboard().reservations()).hasSize(1);
    }

    private long dispatchableWorkflow(String key) {
        long wf=db.queryForObject("select min(id) from workflow_definitions",Long.class);
        String steps="[{\"sequence\":0,\"taskKey\":\""+key+":0\",\"agentId\":"+agent+",\"runtimeInstanceKey\":\"synthetic-instance\",\"status\":\"DISPATCHING\"}]";
        db.update("insert into workflow_executions(workflow_id,run_key,status,objective,requester,snapshot_json,steps_json,created_at,started_at,recovery_version,owner_epoch,owner_id) values(?,?,'RUNNING','synthetic','test','{}',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,1,7,'original-owner')",wf,key,steps);
        long id=db.queryForObject("select id from workflow_executions where run_key=?",Long.class,key);
        db.update("insert into workflow_phase_observations values (?,7,'original-owner',0,'TASK_WAIT','RUNNING',?,null)",id,System.currentTimeMillis());
        var request=new MockHttpServletRequest();request.addHeader("X-EAP-Task-Key",key+":0");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return id;
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"stopped","expired","missing-start","owner","epoch","phase","step"})
    void lostWorkflowResponsibilityRejectsBeforeProviderAndBudget(String change) {
        long id=dispatchableWorkflow("fence-"+UUID.randomUUID());
        switch(change) {
            case "stopped" -> db.update("update workflow_executions set status='EXECUTION_UNKNOWN' where id=?",id);
            case "expired" -> db.update("update workflow_executions set started_at=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(901)),id);
            case "missing-start" -> db.update("update workflow_executions set started_at=null where id=?",id);
            case "owner" -> db.update("update workflow_executions set owner_id='different-owner' where id=?",id);
            case "epoch" -> db.update("update workflow_executions set owner_epoch=8 where id=?",id);
            case "phase" -> db.update("update workflow_phase_observations set state='UNKNOWN' where execution_id=?",id);
            case "step" -> db.update("update workflow_executions set steps_json=replace(steps_json,'DISPATCHING','SUCCEEDED') where id=?",id);
        }
        assertThatThrownBy(()->gateway.chatCompletions(request(),agent,null,null,"synthetic-trace",GatewayDtos.ClassificationHints.empty()))
                .isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("workflow_not_running"));
        verify(upstream,never()).chatCompletions(any());assertThat(ledger.dashboard().reservations()).isEmpty();
    }

    @Test void activeWorkflowAdmitsOnceAndLateCallKeepsOriginalReservation() {
        long id=dispatchableWorkflow("fence-"+UUID.randomUUID());
        var ticket=reserve("original");ledger.unconfirmed(ticket,"SYNTHETIC_DISCONNECT");
        db.update("update workflow_executions set status='EXECUTION_UNKNOWN',owner_epoch=8,owner_id=null where id=?",id);
        assertThatThrownBy(()->reserve("retry")).isInstanceOf(GatewayException.class);
        assertThat(ledger.dashboard().reservations()).hasSize(1);
        assertThat(ledger.dashboard().reservations().getFirst().state()).isEqualTo("UNKNOWN");
        assertThat(account("PLATFORM","default","MODEL_CALLS").usedAmount()).isEqualByComparingTo("1");
        assertThat(account("PLATFORM","default","OUTPUT_TOKENS").reservedAmount()).isEqualByComparingTo("4");
    }

    @Test void workflowFenceStillAppliesWhenBudgetFeatureIsDisabled() {
        long id=dispatchableWorkflow("fence-"+UUID.randomUUID());
        db.update("update workflow_executions set status='EXECUTION_UNKNOWN' where id=?",id);
        var replica=new BudgetLedger(db,json,agents,manager);replica.enabled=false;
        assertThatThrownBy(()->replica.admitModel(request(),agent,null,null,"trace","disabled"))
                .isInstanceOf(GatewayException.class).satisfies(e->assertThat(((GatewayException)e).getCode()).isEqualTo("workflow_not_running"));
        assertThat(ledger.dashboard().reservations()).isEmpty();
    }
}
