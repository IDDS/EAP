package com.eap.platform.optimization;

import com.eap.platform.agent.*;
import com.eap.platform.budget.BudgetLedger;
import com.eap.platform.common.*;
import com.eap.platform.experiment.*;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.RequestContextHolder;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.flyway.enabled=true","eap.optimization.enabled=true","eap.optimization.worker-initial-delay-ms=3600000","eap.experiment.enabled=true","eap.experiment.worker-initial-delay-ms=3600000","eap.budget.enabled=true","eap.gateway.enabled=true","eap.gateway.ingestion-token=optimization-test-token","eap.gateway.upstream-base-url=http://upstream.invalid","eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid/realm","eap.security.jwk-set-uri=https://idp.invalid/jwks","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class OptimizationIntegrationTests {
    @Autowired OptimizationService service;@Autowired AgentExperimentService experiments;
    @Autowired OptimizationEvidenceService evidence;
    @Autowired OptimizationUnknownReviewService reviews;
    @Autowired AgentService agents;@Autowired JdbcTemplate db;@Autowired ObjectMapper json;@Autowired MockMvc mvc;
    @Autowired OpenAiCompatibleGatewayService gateway;@Autowired BudgetLedger ledger;
    @MockitoBean AgentExperimentClient runtime;@MockitoBean OpenAiUpstreamClient upstream;
    @MockitoBean GovernancePreflightService governance;@MockitoBean ModelTelemetryService telemetry;
    @MockitoBean ContentObservationService content;@MockitoBean ToolInvocationService tools;
    @MockitoBean OptimizationWorkerClient workerClient;
    long agent;String instance,endpoint,profileDigest="a".repeat(64);

    @BeforeEach void setup() {
        RequestContextHolder.resetRequestAttributes();
        db.update("delete from agent_optimization_unknown_reviews");
        for(String t:List.of("budget_limit_changes","budget_settlements","budget_reservation_items","budget_reservations","budget_accounts","agent_optimization_holdout_batches","agent_optimization_protocols","agent_optimization_reflections","agent_optimization_evaluations"))db.update("delete from "+t);
        db.update("update agent_experiments set optimization_run_id=null");db.update("delete from agent_optimizations");
        for(String t:List.of("experiment_diagnostic_receipts","experiment_diagnostic_decisions","agent_experiment_trials","agent_experiment_variants","agent_experiments"))db.update("delete from "+t);
        agent=agents.create(new AgentDtos.AgentRequest("OPT-"+UUID.randomUUID(),"Optimization test","","Test","Owner","Test",RiskLevel.LOW)).id();agents.updateStatus(agent,AgentLifecycleStatus.ACTIVE);
        instance="eap-agents/a"+agent+"-v1-opt";endpoint="substrate://"+instance;
        db.update("insert into agent_runtime_instances(agent_id,instance_key,endpoint,protocol,dispatch_authenticated,status,load_score,created_at,updated_at) values(?,?,?,'SUBSTRATE',false,'ONLINE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",agent,instance,endpoint);
        db.update("insert into substrate_agent_deployments(deployment_key,agent_id,version,source_type,status,config_json,image_ref,atespace,template_name,instance_key,steps_json,requested_by,created_at,updated_at) values(?,?,1,'IMAGE','READY','{}',?,'eap-agents',?,?,'[]','test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","opt-deploy-"+agent,agent,"registry.test/runner@sha256:"+"b".repeat(64),"opt-template-"+agent,instance);
        var p=json.createObjectNode().put("protocol","EAP_STATELESS_PROMPT_V1").put("agentId",Long.toString(agent)).put("model","test-model").put("systemPrompt","BASELINE prompt").put("maxTokens",1024).put("profileDigest",profileDigest).put("context","EMPTY").put("tools","DISABLED");
        when(runtime.profile(endpoint)).thenReturn(new AgentExperimentClient.RuntimeProfile(p,"actor-opt-"+agent,"eap-agents/opt-template-"+agent));
        when(governance.evaluate(any())).thenReturn(new GovernanceDataDtos.PreflightResponse(1L,"optimization-pass",agent,GovernanceDataAction.ALLOW,GovernanceGatewayDirective.PROCEED,true,0,List.of(),List.of(),"test","test","test",GovernanceApprovalStatus.NOT_REQUIRED,null,null,null,null,Instant.now(),"NOT_APPLICABLE"));
        when(upstream.chatCompletions(any())).thenAnswer(call->{JsonNode body=call.getArgument(0);String value;
            if(body.has("response_format"))value="{\"system_prompt\":\"SELECTED prompt\"}";
            else value=body.path("messages").path(0).path("content").asText().contains("BASELINE")?"wrong":expected(body.path("messages").path(1).path("content").asText());
            return ResponseEntity.ok(provider(value));
        });
    }
    @AfterEach void done(){RequestContextHolder.resetRequestAttributes();}
    String expected(String input){return switch(input){case "train input"->"TRAIN";case "validation input"->"VAL";case "sealed input"->"HOLD";default->"EXPECTED:"+input;};}
    JsonNode provider(String output){var node=json.createObjectNode();node.set("usage",json.createObjectNode().put("prompt_tokens",7).put("completion_tokens",3));node.set("choices",json.createArrayNode().add(json.createObjectNode().put("finish_reason","stop").set("message",json.createObjectNode().put("content",output))));return node;}
    OptimizationDtos.Submit request(String key){return new OptimizationDtos.Submit(agent,instance,profileDigest,"MANUAL prompt","GEPA",128,8,32,8192,List.of(new OptimizationDtos.Sample("train-1","g1","TRAIN","train input","TRAIN"),new OptimizationDtos.Sample("validation-1","g2","VALIDATION","validation input","VAL"),new OptimizationDtos.Sample("holdout-1","g3","HOLDOUT","sealed input","HOLD")),true,true,key);}
    OptimizationDtos.Submit with(OptimizationDtos.Submit r,List<OptimizationDtos.Sample> samples,String engine,boolean publicUse,boolean reflectUse,int calls){return new OptimizationDtos.Submit(r.agentId(),r.instanceKey(),r.profileDigest(),r.manualPrompt(),engine,r.maxTokens(),r.maxMetricCalls(),calls,r.maxOutputTokens(),samples,publicUse,reflectUse,r.requestKey());}
    String hash(Object value)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));}
    JsonNode candidate(String prompt){return json.createObjectNode().put("system_prompt",prompt);}
    OptimizationDtos.Evaluate evaluation(long id,String prompt,String split,String sample)throws Exception {var p=candidate(prompt);return new OptimizationDtos.Evaluate(p,split,List.of(sample),hash(List.of(id,hash(p),split,List.of(sample))));}
    void finishExperiment()throws Exception {finishExperiment(1);}
    void finishExperiment(int samples)throws Exception {
        for(int i=0;i<2*samples;i++){var dispatch=experiments.claim();assertThat(dispatch).isNotNull();
            var request=json.createObjectNode().put("model","test-model").put("max_tokens",((Number)dispatch.body().get("maxTokens")).intValue());
            request.set("messages",json.createArrayNode().add(json.createObjectNode().put("role","system").put("content",(String)dispatch.body().get("systemPrompt"))).add(json.createObjectNode().put("role","user").put("content",(String)dispatch.body().get("input"))));
            JsonNode response=gateway.chatCompletions(request,agent,null,null,(String)dispatch.body().get("traceId"),GatewayDtos.ClassificationHints.empty()).getBody();
            var receipt=json.createObjectNode().put("output",response.path("choices").path(0).path("message").path("content").asText()).put("finishReason","stop").put("latencyMs",4);receipt.set("usage",response.path("usage"));
            experiments.finish(dispatch,new AgentExperimentClient.Receipt("SUCCEEDED",null,receipt));
        }
    }
    JsonNode feedback(Map<String,Object> operation){JsonNode row=json.valueToTree(((List<?>)operation.get("results")).getFirst());var r=(com.fasterxml.jackson.databind.node.ObjectNode)row;r.put("input","train input").put("expected","TRAIN").put("usageKnown",true);return r;}

    @Test void completePipelineSealsBeforeHoldoutUsesFreshOutputsAndSharesAllCosts()throws Exception {
        var root=service.submit(request("complete-root-1"),"test-author");var claim=service.claim();assertThat(claim.id()).isEqualTo(root.id());
        long manual=service.manualValidation(claim);finishExperiment();assertThat(service.manualReady(claim,manual)).isTrue();
        var seedVal=evaluation(root.id(),"BASELINE prompt","VALIDATION","validation-1");service.evaluate(root.id(),claim.token(),seedVal);finishExperiment();
        var train=evaluation(root.id(),"BASELINE prompt","TRAIN","train-1");service.evaluate(root.id(),claim.token(),train);finishExperiment();
        var reflection=service.reflect(root.id(),claim.token(),new OptimizationDtos.Reflect(0,candidate("BASELINE prompt"),List.of(feedback(service.evaluation(root.id(),claim.token(),train.requestKey())))));assertThat(reflection.get("status")).isEqualTo("COMPLETED");
        var selected=evaluation(root.id(),"SELECTED prompt","VALIDATION","validation-1");service.evaluate(root.id(),claim.token(),selected);finishExperiment();
        service.searchResult(root.id(),claim.token(),new OptimizationDtos.SearchReceipt("COMPLETED",selected.candidate(),hash(selected.candidate()),OptimizationService.ENGINE_VERSION,null));
        assertThat(service.get(root.id()).status()).isEqualTo("HOLDOUT");assertThatThrownBy(()->service.context(root.id(),claim.token())).isInstanceOf(ResponseStatusException.class);
        service.advanceHoldout();finishExperiment();service.advanceHoldout();finishExperiment();service.advanceHoldout();
        var result=service.get(root.id());assertThat(result.status()).isEqualTo("COMPLETED");assertThat(result.recommendation()).isEqualTo("KEEP_BASELINE");assertThat(result.reasonCode()).isEqualTo("INSUFFICIENT_INDEPENDENT_HOLDOUT");
        assertThat(result.chargedCalls()).isEqualTo(13);assertThat(result.pendingReservations()).isZero();assertThat(result.evaluations()).filteredOn(e->"HOLDOUT".equals(e.split())).hasSize(2);
        assertThat(result.evaluations()).filteredOn(e->"HOLDOUT".equals(e.split())).allMatch(e->e.baseline().passed()==0&&e.candidate().passed()==1&&e.candidate().usageKnown()==1);
        verify(upstream,times(13)).chatCompletions(any());assertThat(json.writeValueAsString(result)).doesNotContain("BASELINE prompt","MANUAL prompt","SELECTED prompt","sealed input",claim.token());
        assertThat(db.queryForObject("select count(*) from substrate_agent_deployments where agent_id=?",Long.class,agent)).isEqualTo(1);
    }
    @Test void onlyScopedClaimGetsSearchDataAndHoldoutNeverEntersContext()throws Exception {
        var root=service.submit(request("scoped-root-1"),"test-author");var claim=service.claim();
        mvc.perform(get("/internal/agent-optimizations/"+root.id()+"/context")).andExpect(status().isForbidden());
        mvc.perform(get("/internal/agent-optimizations/"+root.id()+"/context").header("X-EAP-Optimization-Claim","0".repeat(64))).andExpect(status().isForbidden());
        var body=mvc.perform(get("/internal/agent-optimizations/"+root.id()+"/context").header("X-EAP-Optimization-Claim",claim.token())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("train input","validation input").doesNotContain("sealed input","HOLDOUT","HOLD",claim.token(),"MANUAL prompt","_actorUid");
        assertThat(db.queryForObject("select claim_hash from agent_optimizations where id=?",String.class,root.id())).isNotEqualTo(claim.token());
        db.update("update agent_optimizations set status='FAILED' where id=?",root.id());var other=service.submit(request("scoped-root-2"),"test-author");service.claim();
        mvc.perform(get("/internal/agent-optimizations/"+other.id()+"/context").header("X-EAP-Optimization-Claim",claim.token())).andExpect(status().isForbidden());
    }
    @Test void expiredClaimDoesNotCreateEvaluationAndCannotResetBudget()throws Exception {
        var root=service.submit(request("expiry-root-1"),"test-author");var claim=service.claim();db.update("update agent_optimizations set claim_deadline=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),root.id());
        var evaluate=evaluation(root.id(),"SELECTED prompt","TRAIN","train-1");assertThatThrownBy(()->service.evaluate(root.id(),claim.token(),evaluate)).isInstanceOf(ResponseStatusException.class);
        service.expireSearches();assertThat(service.get(root.id()).status()).isEqualTo("UNKNOWN");assertThat(service.get(root.id()).chargedCalls()).isZero();assertThat(service.claim()).isNull();verify(upstream,never()).chatCompletions(any());
    }
    @Test void queuedTrialCannotDispatchAfterExecutionIdentityExpires()throws Exception {
        var root=service.submit(request("expired-dispatch-1"),"test-author");var claim=service.claim();service.evaluate(root.id(),claim.token(),evaluation(root.id(),"SELECTED prompt","TRAIN","train-1"));
        db.update("update agent_optimizations set claim_deadline=? where id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),root.id());var dispatch=experiments.claim();
        assertThatThrownBy(()->gateway.chatCompletions(json.createObjectNode().put("model","test-model").put("max_tokens",128),agent,null,null,(String)dispatch.body().get("traceId"),GatewayDtos.ClassificationHints.empty())).isInstanceOf(GatewayException.class);
        verify(upstream,never()).chatCompletions(any());assertThat(service.get(root.id()).chargedCalls()).isZero();
    }
    @Test void concurrentDuplicateSubmissionCreatesOneRootAndOneFrozenBudget()throws Exception {
        var request=request("concurrent-root-1");var pool=Executors.newFixedThreadPool(4);var latch=new CountDownLatch(1);
        try{List<Future<Long>> futures=new ArrayList<>();for(int i=0;i<4;i++)futures.add(pool.submit(()->{latch.await();return service.submit(request,"test-author").id();}));latch.countDown();Set<Long> ids=new HashSet<>();for(var f:futures)ids.add(f.get(20,TimeUnit.SECONDS));assertThat(ids).hasSize(1);assertThat(service.list()).hasSize(1);assertThat(db.queryForObject("select count(*) from budget_accounts where scope_type='TASK'",Long.class)).isEqualTo(2);verify(runtime,times(1)).profile(endpoint);}finally{pool.shutdownNow();}
        assertThatThrownBy(()->service.submit(with(request,request.samples(),"SIMPLE",true,false,32),"test-author")).isInstanceOf(ConflictException.class);
    }
    @Test void duplicateEvaluationHasOneIntentAndOnePairAndModifiedKeyCannotResetIt()throws Exception {
        var root=service.submit(request("evaluation-root-1"),"test-author");var claim=service.claim();var request=evaluation(root.id(),"SELECTED prompt","TRAIN","train-1");
        var one=service.evaluate(root.id(),claim.token(),request);var two=service.evaluate(root.id(),claim.token(),request);assertThat(one.get("experimentId")).isEqualTo(two.get("experimentId"));finishExperiment();
        assertThat(service.evaluation(root.id(),claim.token(),request.requestKey()).get("status")).isEqualTo("COMPLETED");assertThat(service.evaluate(root.id(),claim.token(),request).get("status")).isEqualTo("COMPLETED");verify(upstream,times(2)).chatCompletions(any());
        var wrong=new OptimizationDtos.Evaluate(candidate("OTHER prompt"),"TRAIN",List.of("train-1"),request.requestKey());assertThatThrownBy(()->service.evaluate(root.id(),claim.token(),wrong)).isInstanceOf(ConflictException.class);assertThat(service.get(root.id()).chargedCalls()).isEqualTo(2);
    }
    @Test void holdoutCannotBeRequestedDuringSearchOrBeforeCompleteValidation()throws Exception {
        var root=service.submit(request("early-holdout-1"),"test-author");var claim=service.claim();var wrong=new OptimizationDtos.Evaluate(candidate("SELECTED prompt"),"TRAIN",List.of("holdout-1"),"0".repeat(64));assertThatThrownBy(()->service.evaluate(root.id(),claim.token(),wrong)).isInstanceOf(BusinessException.class);
        var receipt=new OptimizationDtos.SearchReceipt("COMPLETED",candidate("SELECTED prompt"),hash(candidate("SELECTED prompt")),OptimizationService.ENGINE_VERSION,null);assertThatThrownBy(()->service.searchResult(root.id(),claim.token(),receipt)).isInstanceOf(BusinessException.class);
        var eval=evaluation(root.id(),"SELECTED prompt","VALIDATION","validation-1");service.evaluate(root.id(),claim.token(),eval);assertThatThrownBy(()->service.searchResult(root.id(),claim.token(),receipt)).isInstanceOf(ConflictException.class);verify(upstream,never()).chatCompletions(any());
    }
    @Test void providerUnknownStopsRemainingTrialsHoldsRootReservationAndPreventsNewRoot()throws Exception {
        var root=service.submit(request("unknown-root-1"),"test-author");var claim=service.claim();service.evaluate(root.id(),claim.token(),evaluation(root.id(),"SELECTED prompt","TRAIN","train-1"));var dispatch=experiments.claim();
        var ticket=ledger.admitModel(json.createObjectNode().put("model","test-model").put("max_tokens",128),agent,null,null,(String)dispatch.body().get("traceId"),"ignored");ledger.unconfirmed(ticket,"RECEIPT_UNKNOWN");experiments.finish(dispatch,new AgentExperimentClient.Receipt("UNKNOWN","RECEIPT_UNKNOWN",null));
        service.searchResult(root.id(),claim.token(),new OptimizationDtos.SearchReceipt("UNKNOWN",null,null,OptimizationService.ENGINE_VERSION,"unknown"));var result=service.get(root.id());assertThat(result.status()).isEqualTo("UNKNOWN");assertThat(result.reservedOutputTokens()).isEqualTo(128);assertThat(result.chargedCalls()).isEqualTo(1);assertThat(result.pendingReservations()).isEqualTo(1);
        assertThatThrownBy(()->service.submit(request("unknown-root-2"),"test-author")).isInstanceOf(ConflictException.class);assertThat(service.claim()).isNull();assertThat(experiments.claim()).isNull();
    }
    @Test void forgedReflectionEvidenceDoesNotReachProvider()throws Exception {
        var root=service.submit(request("forged-reflection-1"),"test-author");var claim=service.claim();var feedback=json.createObjectNode().put("sampleId","train-1").put("input","train input").put("expected","TRAIN").put("output","forged").put("status","SUCCEEDED").put("score",1).put("usageKnown",true).put("traceId","f".repeat(32));
        assertThatThrownBy(()->service.reflect(root.id(),claim.token(),new OptimizationDtos.Reflect(0,candidate("BASELINE prompt"),List.of(feedback)))).isInstanceOf(BusinessException.class);verify(upstream,never()).chatCompletions(any());assertThat(db.queryForObject("select count(*) from agent_optimization_reflections",Long.class)).isZero();
    }
    @Test void reflectionDuplicateReturnsOriginalResultWithoutNewGatewayCall()throws Exception {
        var root=service.submit(request("reflection-once-1"),"test-author");var claim=service.claim();var eval=evaluation(root.id(),"BASELINE prompt","TRAIN","train-1");service.evaluate(root.id(),claim.token(),eval);finishExperiment();var request=new OptimizationDtos.Reflect(0,candidate("BASELINE prompt"),List.of(feedback(service.evaluation(root.id(),claim.token(),eval.requestKey()))));
        var first=service.reflect(root.id(),claim.token(),request);var second=service.reflect(root.id(),claim.token(),request);assertThat(second).isEqualTo(first);verify(upstream,times(3)).chatCompletions(any());assertThat(service.get(root.id()).chargedCalls()).isEqualTo(3);
    }
    @Test void deepSeekReflectionModeIsFrozenAndDoesNotChangeEvaluationOptions()throws Exception {
        var prior=runtime.profile(endpoint);var p=(com.fasterxml.jackson.databind.node.ObjectNode)prior.profile().deepCopy();p.put("model","deepseek-flash");when(runtime.profile(endpoint)).thenReturn(new AgentExperimentClient.RuntimeProfile(p,prior.actorUid(),prior.template()));
        var root=service.submit(request("deepseek-mode-1"),"test-author");var claim=service.claim();var eval=evaluation(root.id(),"BASELINE prompt","TRAIN","train-1");service.evaluate(root.id(),claim.token(),eval);finishExperiment();
        service.reflect(root.id(),claim.token(),new OptimizationDtos.Reflect(0,candidate("BASELINE prompt"),List.of(feedback(service.evaluation(root.id(),claim.token(),eval.requestKey())))));
        var captor=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(upstream,times(3)).chatCompletions(captor.capture());var sent=captor.getAllValues();
        assertThat(sent.get(0).has("thinking")).isFalse();assertThat(sent.get(1).has("thinking")).isFalse();assertThat(sent.get(2).path("thinking").path("type").asText()).isEqualTo("disabled");assertThat(sent.get(2).path("max_tokens").asInt()).isEqualTo(512);
        JsonNode frozen=json.valueToTree(service.content(root.id()).get("profile"));assertThat(frozen.path("_reflectionThinking").asText()).isEqualTo("DISABLED");assertThat(frozen.path("model").asText()).isEqualTo("deepseek-flash");
    }
    @Test void truncatedReflectionIsPaidFailureAndCannotAdvanceToHoldout()throws Exception {
        var root=service.submit(request("truncated-reflection-1"),"test-author");var claim=service.claim();var eval=evaluation(root.id(),"BASELINE prompt","TRAIN","train-1");service.evaluate(root.id(),claim.token(),eval);finishExperiment();
        var truncated=provider("{\"system_prompt\":\"partial");((com.fasterxml.jackson.databind.node.ObjectNode)truncated.path("choices").path(0)).put("finish_reason","length");doReturn(ResponseEntity.ok(truncated)).when(upstream).chatCompletions(any());
        var result=service.reflect(root.id(),claim.token(),new OptimizationDtos.Reflect(0,candidate("BASELINE prompt"),List.of(feedback(service.evaluation(root.id(),claim.token(),eval.requestKey())))));assertThat(result.get("status")).isEqualTo("FAILED");assertThat(result.get("reasonCode")).isEqualTo("REFLECTION_TRUNCATED");var saved=service.get(root.id());assertThat(saved.status()).isEqualTo("FAILED");assertThat(saved.chargedCalls()).isEqualTo(3);assertThat(saved.pendingReservations()).isZero();service.advanceHoldout();assertThat(service.get(root.id()).evaluations()).hasSize(1);verify(upstream,times(3)).chatCompletions(any());
    }
    @Test void reflectedModelAndToolsFieldsAreRejectedAndRecordedAsPaidFailure()throws Exception {
        var root=service.submit(request("reflection-schema-1"),"test-author");var claim=service.claim();var eval=evaluation(root.id(),"BASELINE prompt","TRAIN","train-1");service.evaluate(root.id(),claim.token(),eval);finishExperiment();
        doReturn(ResponseEntity.ok(provider("{\"system_prompt\":\"ok\",\"model\":\"other\"}"))).when(upstream).chatCompletions(any());
        var result=service.reflect(root.id(),claim.token(),new OptimizationDtos.Reflect(0,candidate("BASELINE prompt"),List.of(feedback(service.evaluation(root.id(),claim.token(),eval.requestKey())))));assertThat(result.get("status")).isEqualTo("FAILED");assertThat(service.get(root.id()).status()).isEqualTo("FAILED");assertThat(service.get(root.id()).chargedCalls()).isEqualTo(3);assertThat(service.get(root.id()).reservedOutputTokens()).isZero();
    }
    @Test void datasetOverlapAndUnapprovedPurposeFailBeforeReadingRuntime(){
        var r=request("bad-purpose-1");assertThatThrownBy(()->service.submit(with(r,r.samples(),"GEPA",true,false,32),"test-author")).isInstanceOf(BusinessException.class);
        var duplicate=new ArrayList<>(r.samples());duplicate.set(2,new OptimizationDtos.Sample("holdout-1","g3","HOLDOUT","　train  input　","HOLD"));assertThatThrownBy(()->service.submit(with(r,duplicate,"GEPA",true,true,32),"test-author")).isInstanceOf(BusinessException.class);
        var crossed=new ArrayList<>(r.samples());crossed.set(2,new OptimizationDtos.Sample("holdout-1","g1","HOLDOUT","sealed input","HOLD"));assertThatThrownBy(()->service.submit(with(r,crossed,"GEPA",true,true,32),"test-author")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.submit(with(r,r.samples(),"GEPA",true,true,10),"test-author")).isInstanceOf(BusinessException.class);verify(runtime,never()).profile(any());assertThat(service.list()).isEmpty();
    }
    @Test void simpleSearchDoesNotRequireReflectionPurposeAndCannotRequestReflection(){
        var r=request("simple-purpose-1");var root=service.submit(with(r,r.samples(),"SIMPLE",true,false,32),"test-author");var claim=service.claim();assertThat(service.context(root.id(),claim.token()).get("maxReflections")).isEqualTo(0);
        assertThatThrownBy(()->service.reflect(root.id(),claim.token(),new OptimizationDtos.Reflect(0,candidate("BASELINE prompt"),List.of(json.createObjectNode())))).isInstanceOf(BusinessException.class);verify(upstream,never()).chatCompletions(any());
    }
    @Test void frozenRuntimeUidAndImageCannotBeReboundDuringSearch()throws Exception {
        var root=service.submit(request("frozen-runtime-1"),"test-author");var claim=service.claim();var prior=runtime.profile(endpoint);when(runtime.profile(endpoint)).thenReturn(new AgentExperimentClient.RuntimeProfile(prior.profile(),"different-actor",prior.template()));
        assertThatThrownBy(()->service.evaluate(root.id(),claim.token(),evaluation(root.id(),"SELECTED prompt","TRAIN","train-1"))).isInstanceOf(ConflictException.class);assertThat(service.get(root.id()).evaluations()).isEmpty();verify(upstream,never()).chatCompletions(any());
    }
    @Test void unavailableWorkerKeepsQueuedTaskWithoutStartingModelWork()throws Exception {
        var root=service.submit(request("unavailable-worker-1"),"test-author");when(workerClient.available()).thenReturn(false);var coordinator=new OptimizationWorker(service,workerClient);
        try{coordinator.process();verify(workerClient,timeout(1000)).available();Thread.sleep(50);assertThat(service.get(root.id()).status()).isEqualTo("QUEUED");assertThat(service.get(root.id()).chargedCalls()).isZero();assertThat(service.get(root.id()).evaluations()).isEmpty();verify(workerClient,never()).start(any());}finally{coordinator.close();}
    }
    @ParameterizedTest @CsvSource({"VIEWER,false,false","OPERATOR,false,false","GOVERNANCE_ADMIN,false,true","PLATFORM_ADMIN,true,true"})
    void capabilitiesProtectCreationAndPrivateContent(String role,boolean create,boolean viewContent)throws Exception {
        var r=request("capability-root-1");var root=service.submit(r,"test-author");var identity=jwt().jwt(j->j.subject("test-author")).authorities(new SimpleGrantedAuthority("ROLE_"+role));
        mvc.perform(get("/api/platform/me").with(identity)).andExpect(jsonPath("$.data.capabilities.runAgentExperiments").value(create));
        mvc.perform(post("/api/agent-optimizations").with(identity).contentType("application/json").content(json.writeValueAsBytes(r))).andExpect(status().is(create?200:403));
        var metadata=mvc.perform(get("/api/agent-optimizations/"+root.id()).with(identity)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(metadata).doesNotContain("BASELINE prompt","MANUAL prompt","train input","sealed input");
        mvc.perform(get("/api/agent-optimizations/"+root.id()+"/content").with(identity)).andExpect(status().is(viewContent?200:403));
    }
    OptimizationDtos.Submit largeRequest(String key,String engine,boolean oneGroup) {
        List<OptimizationDtos.Sample> data=new ArrayList<>();
        for(String split:List.of("TRAIN","VALIDATION","HOLDOUT"))for(int i=0;i<(split.equals("HOLDOUT")?10:3);i++) {
            String sid=split.toLowerCase()+"-"+String.format("%02d",i),input="public-"+sid;
            data.add(new OptimizationDtos.Sample(sid,oneGroup&&split.equals("HOLDOUT")?"same-holdout-group":"g-"+sid,split,input,"EXPECTED:"+input));
        }
        return new OptimizationDtos.Submit(agent,instance,profileDigest,"MANUAL prompt",engine,128,8,64,16384,data,true,"GEPA".equals(engine),key);
    }
    OptimizationDtos.Evaluate largeValidation(long id,String prompt)throws Exception {
        var p=candidate(prompt);var ids=List.of("validation-00","validation-01","validation-02");
        return new OptimizationDtos.Evaluate(p,"VALIDATION",ids,hash(List.of(id,hash(p),"VALIDATION",ids)));
    }
    OptimizationDtos.Claim sealLarge(OptimizationDtos.Summary root,String prompt)throws Exception {
        var claim=service.claim();long manual=service.manualValidation(claim);finishExperiment(3);assertThat(service.manualReady(claim,manual)).isTrue();
        service.evaluate(root.id(),claim.token(),largeValidation(root.id(),prompt));
        if(!prompt.equals("MANUAL prompt"))finishExperiment(3);
        service.searchResult(root.id(),claim.token(),new OptimizationDtos.SearchReceipt("COMPLETED",candidate(prompt),hash(candidate(prompt)),OptimizationService.ENGINE_VERSION,null));
        return claim;
    }
    void finishAllBatches(long id)throws Exception {
        for(int pass=0;pass<9;pass++) {
            service.advanceHoldout();
            var pending=service.get(id).evaluations().stream().filter(e->"QUEUED".equals(e.status())).findFirst();
            if(pending.isPresent())finishExperiment(pending.get().sampleCount());
        }
    }
    @Test void tenHoldoutsPersistEightBatchesRetainAllDenominatorsAndAggregateActualCosts()throws Exception {
        var root=service.submit(largeRequest("large-batches-1","SIMPLE",false),"test-author");
        assertThat(root.evidence().holdoutSamples()).isEqualTo(10);assertThat(root.evidence().independentHoldoutGroups()).isEqualTo(10);
        assertThat(root.evidence().cost().plannedUpperCalls()).isEqualTo(62);assertThat(root.evidence().cost().plannedUpperOutputTokens()).isEqualTo(7936);
        assertThat(root.evidence().holdout()).allMatch(a->a.registeredSamples()==10&&a.candidateNotRun()==10&&a.candidate().inputTokens()==null);
        var claim=sealLarge(root,"SELECTED prompt");
        var persisted=new OptimizationEvidenceService(db,json,experiments);
        assertThat(persisted.batches(root.id())).hasSize(8).allMatch(b->json.valueToTree(b).path("sample_ids_json").asText().length()>0);
        for(var batch:persisted.batches(root.id()))assertThat(json.readTree((String)batch.get("sample_ids_json")).size()).isBetween(1,3);
        assertThatThrownBy(()->service.context(root.id(),claim.token())).isInstanceOf(ResponseStatusException.class);
        service.advanceHoldout();
        var pool=Executors.newFixedThreadPool(4);try{List<Future<?>> calls=new ArrayList<>();for(int i=0;i<4;i++)calls.add(pool.submit(service::advanceHoldout));for(var f:calls)f.get(20,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertThat(db.queryForObject("select count(*) from agent_optimization_holdout_batches where run_id=? and evaluation_id is not null",Long.class,root.id())).isEqualTo(1);
        assertThat(service.get(root.id()).evidence().holdout()).allMatch(a->a.candidate().samples()==10&&a.candidate().inputTokens()==null);
        finishAllBatches(root.id());var result=service.get(root.id());
        assertThat(result.status()).isEqualTo("COMPLETED");assertThat(result.recommendation()).isEqualTo("REVIEW_MANUAL");assertThat(result.reasonCode()).isEqualTo("INDEPENDENT_COHORT_REVIEW_REQUIRED");
        assertThat(result.evidence().holdout()).allMatch(a->a.completedBatches()==4&&a.candidate().samples()==10&&a.candidate().passed()==10&&a.baseline().passed()==0&&a.candidateNotRun()==0);
        assertThat(result.chargedCalls()).isEqualTo(52);assertThat(result.evidence().cost().admittedCalls()).isEqualTo(52);assertThat(result.evidence().cost().usageKnownCalls()).isEqualTo(52);
        assertThat(result.evidence().cost().inputTokens()).isEqualTo(364);assertThat(result.evidence().cost().outputTokens()).isEqualTo(156);
        assertThat(result.evidence().cost().phases()).filteredOn(p->p.phase().equals("HOLDOUT")).singleElement().satisfies(p->{assertThat(p.admittedCalls()).isEqualTo(40);assertThat(p.latencySamples()).isEqualTo(40);});
        assertThat(result.evidence().cost().monetaryStatus()).isEqualTo("UNPRICED");verify(upstream,times(52)).chatCompletions(any());
        assertThat(json.writeValueAsString(result)).doesNotContain("public-holdout","EXPECTED:",claim.token(),"SELECTED prompt");
        long calls=result.chargedCalls();service.advanceHoldout();assertThat(service.get(root.id()).chargedCalls()).isEqualTo(calls);
    }
    @Test void unknownFirstHoldoutBatchKeepsAllRegisteredSamplesAndNeverCreatesReplacement()throws Exception {
        var root=service.submit(largeRequest("unknown-batch-1","SIMPLE",false),"test-author");sealLarge(root,"SELECTED prompt");service.advanceHoldout();
        var dispatch=experiments.claim();var ticket=ledger.admitModel(json.createObjectNode().put("model","test-model").put("max_tokens",128),agent,null,null,(String)dispatch.body().get("traceId"),"ignored");ledger.unconfirmed(ticket,"RECEIPT_UNKNOWN");experiments.finish(dispatch,new AgentExperimentClient.Receipt("UNKNOWN","RECEIPT_UNKNOWN",null));
        service.advanceHoldout();var result=service.get(root.id());assertThat(result.status()).isEqualTo("UNKNOWN");assertThat(result.recommendation()).isEqualTo("KEEP_BASELINE");
        var manual=result.evidence().holdout().getFirst();assertThat(manual.baseline().samples()).isEqualTo(10);assertThat(manual.baseline().unknown()).isEqualTo(1);assertThat(manual.baselineNotRun()).isEqualTo(9);assertThat(manual.candidateNotRun()).isEqualTo(10);
        assertThat(result.evidence().cost().inputTokens()).isNull();assertThat(result.pendingReservations()).isEqualTo(1);
        service.advanceHoldout();assertThat(db.queryForObject("select count(*) from agent_optimization_holdout_batches where run_id=? and evaluation_id is not null",Long.class,root.id())).isEqualTo(1);assertThat(experiments.claim()).isNull();
        assertThatThrownBy(()->service.submit(largeRequest("unknown-batch-2","SIMPLE",false),"test-author")).isInstanceOf(ConflictException.class);
    }
    @Test void identicalPromptsShareOriginalHoldoutAndNeverDoubleCountPhysicalCosts()throws Exception {
        var root=service.submit(largeRequest("same-prompt-batch-1","SIMPLE",false),"test-author");sealLarge(root,"MANUAL prompt");finishAllBatches(root.id());
        var result=service.get(root.id());assertThat(result.status()).isEqualTo("COMPLETED");assertThat(result.evidence().holdout()).allMatch(a->a.completedBatches()==4&&a.candidate().samples()==10);
        assertThat(result.evaluations()).filteredOn(e->"HOLDOUT".equals(e.split())).hasSize(4);assertThat(result.chargedCalls()).isEqualTo(26);assertThat(result.evidence().cost().admittedCalls()).isEqualTo(26);verify(upstream,times(26)).chatCompletions(any());
        assertThat(db.queryForObject("select count(distinct evaluation_id) from agent_optimization_holdout_batches where run_id=?",Long.class,root.id())).isEqualTo(4);
    }
    @Test void completePlanChecksExistingSharedCapacityAndOutputBoundBeforeWakingRuntime() {
        var r=largeRequest("preflight-large-1","GEPA",false);ledger.setLimit(new BudgetLedger.Limit("AGENT",Long.toString(agent),"MODEL_CALLS",63L,0,0),"test-author");
        assertThatThrownBy(()->service.submit(r,"test-author")).isInstanceOf(BusinessException.class);verify(runtime,never()).profile(any());assertThat(service.list()).isEmpty();
        var tooSmall=new OptimizationDtos.Submit(r.agentId(),r.instanceKey(),r.profileDigest(),r.manualPrompt(),r.engine(),r.maxTokens(),r.maxMetricCalls(),r.maxCalls(),8959,r.samples(),true,true,"preflight-output-1");
        assertThatThrownBy(()->service.submit(tooSmall,"test-author")).isInstanceOf(BusinessException.class);assertThat(db.queryForObject("select count(*) from budget_reservations",Long.class)).isZero();
        assertThat(db.queryForObject("select used_amount from budget_accounts where scope_type='AGENT' and unit='MODEL_CALLS'",java.math.BigDecimal.class)).isZero();
    }
    @Test void fullPrincipalFingerprintSurvivesTruncatedRequesterAndCannotReuseAnotherIdentity()throws Exception {
        String a="principal-"+"x".repeat(150)+"A",b="principal-"+"x".repeat(150)+"B";var request=request("long-principal-1");var root=service.submit(request,a);
        assertThat(db.queryForObject("select requested_by from agent_optimizations where id=?",String.class,root.id())).hasSize(128);
        String full=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(a.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(db.queryForObject("select principal_digest from agent_optimization_protocols where run_id=?",String.class,root.id())).isEqualTo(full);
        assertThat(service.submit(request,a).id()).isEqualTo(root.id());assertThatThrownBy(()->service.submit(request,b)).isInstanceOf(ConflictException.class);
    }
    @Test void tenInputsWithinOneTaskGroupDoNotQualifyAsTenIndependentGroups()throws Exception {
        var root=service.submit(largeRequest("one-group-batch-1","SIMPLE",true),"test-author");sealLarge(root,"SELECTED prompt");finishAllBatches(root.id());var result=service.get(root.id());
        assertThat(result.status()).isEqualTo("COMPLETED");assertThat(result.evidence().holdoutSamples()).isEqualTo(10);assertThat(result.evidence().independentHoldoutGroups()).isEqualTo(1);
        assertThat(result.recommendation()).isEqualTo("KEEP_BASELINE");assertThat(result.reasonCode()).isEqualTo("INSUFFICIENT_INDEPENDENT_HOLDOUT");
    }
    @Test void missingHoldoutUsageStopsWithUnknownCostAndAllRegisteredDenominators()throws Exception {
        var root=service.submit(largeRequest("missing-usage-batch-1","SIMPLE",false),"test-author");sealLarge(root,"SELECTED prompt");service.advanceHoldout();
        // Complete the original physical calls; provider receipts lack usage.
        var original=provider("EXPECTED:public-holdout-00");((com.fasterxml.jackson.databind.node.ObjectNode)original).remove("usage");
        doReturn(ResponseEntity.ok(original)).when(upstream).chatCompletions(any());
        finishExperiment(3);service.advanceHoldout();var result=service.get(root.id());assertThat(result.status()).isEqualTo("UNKNOWN");assertThat(result.evidence().cost().inputTokens()).isNull();
        assertThat(result.evidence().holdout()).allMatch(a->a.registeredSamples()==10&&a.candidate().samples()==10);assertThat(db.queryForObject("select count(*) from agent_optimization_holdout_batches where run_id=? and evaluation_id is not null",Long.class,root.id())).isEqualTo(1);
    }
    @Test void legacyRootWithoutProtocolKeepsOriginalSmallSampleContractAndNoBackfill()throws Exception {
        var root=service.submit(request("legacy-protocol-1"),"test-author");db.update("delete from agent_optimization_protocols where run_id=?",root.id());var claim=service.claim();var selected=evaluation(root.id(),"SELECTED prompt","VALIDATION","validation-1");service.evaluate(root.id(),claim.token(),selected);finishExperiment();
        service.searchResult(root.id(),claim.token(),new OptimizationDtos.SearchReceipt("COMPLETED",selected.candidate(),hash(selected.candidate()),OptimizationService.ENGINE_VERSION,null));service.advanceHoldout();finishExperiment();service.advanceHoldout();finishExperiment();service.advanceHoldout();
        var result=service.get(root.id());assertThat(result.status()).isEqualTo("COMPLETED");assertThat(result.recommendation()).isEqualTo("REVIEW_MANUAL");assertThat(result.evidence().protocolVersion()).isZero();assertThat(result.evidence().holdout()).isEmpty();assertThat(evidence.batches(root.id())).isEmpty();
    }

    long paidUnknown()throws Exception {
        var root=service.submit(request("paid-unknown-"+UUID.randomUUID()),"test-author");var claim=service.claim();long eval=service.manualValidation(claim);var dispatch=experiments.claim();
        var body=json.createObjectNode().put("model","test-model").put("max_tokens",128);body.set("messages",json.createArrayNode().add(json.createObjectNode().put("role","system").put("content","BASELINE")).add(json.createObjectNode().put("role","user").put("content","validation input")));
        gateway.chatCompletions(body,agent,null,null,(String)dispatch.body().get("traceId"),GatewayDtos.ClassificationHints.empty());
        experiments.finish(dispatch,new AgentExperimentClient.Receipt("UNKNOWN","INVALID_RECEIPT",null));assertThat(service.manualReady(claim,eval)).isFalse();
        db.update("update agent_runtime_instances set platform_actor_uid=?,platform_actor_template=? where instance_key=?","actor-opt-"+agent,"eap-agents/opt-template-"+agent,instance);
        db.update("insert into runtime_connections(instance_key,revision,generation) values(?,0,null)",instance);
        return root.id();
    }
    OptimizationUnknownReviewService.Decide decision(long id,String key){return new OptimizationUnknownReviewService.Decide(reviews.inspect(id).evidenceDigest(),key,true);}
    @Test void paidUnknownInspectionIsReadOnlyAndDoesNotExposeContent()throws Exception {
        long id=paidUnknown();String original=json.writeValueAsString(service.get(id)),budget=json.writeValueAsString(ledger.dashboard());
        var view=reviews.inspect(id);assertThat(view.blockers()).isEmpty();assertThat(view.unknownTrials()).isEqualTo(1);assertThat(view.notRunTrials()).isEqualTo(1);assertThat(view.modelCalls()).isEqualTo(1);assertThat(view.settledCalls()).isEqualTo(1);assertThat(view.inputTokens()).isEqualTo(7);assertThat(view.outputTokens()).isEqualTo(3);
        assertThat(json.writeValueAsString(view)).doesNotContain("BASELINE prompt","MANUAL prompt","validation input","test-author");
        assertThat(json.writeValueAsString(service.get(id))).isEqualTo(original);assertThat(json.writeValueAsString(ledger.dashboard())).isEqualTo(budget);assertThat(db.queryForObject("select count(*) from agent_optimization_unknown_reviews",Long.class)).isZero();verify(upstream,times(1)).chatCompletions(any());
    }
    @Test void auditedStopPreservesUnknownAndCostAndOnlyOpensIndependentAdmission()throws Exception {
        long id=paidUnknown();var original=service.get(id);var child=experiments.get(original.evaluations().getFirst().experimentId());String budget=json.writeValueAsString(ledger.dashboard());
        assertThat(reviews.hasBlockingRoots()).isTrue();assertThat(reviews.hasBlockingExperiments()).isTrue();
        var result=reviews.decide(id,decision(id,"stop-original-1"),"governance-full-subject");assertThat(result.decisionCurrent()).isTrue();assertThat(result.decision().decision()).isEqualTo("STOP_NO_REPLAY");
        assertThat(reviews.hasBlockingRoots()).isFalse();assertThat(reviews.hasBlockingExperiments()).isFalse();assertThat(service.get(id)).isEqualTo(original);assertThat(experiments.get(child.id())).isEqualTo(child);assertThat(json.writeValueAsString(ledger.dashboard())).isEqualTo(budget);
        assertThatThrownBy(()->reviews.requireIndependentInstance(instance)).isInstanceOf(ConflictException.class);reviews.requireIndependentInstance("eap-agents/new-independent");
        assertThat(experiments.claim()).isNull();assertThat(service.claim()).isNull();service.advanceHoldout();assertThat(service.get(id)).isEqualTo(original);verify(upstream,times(1)).chatCompletions(any());
    }
    @Test void originalModelTraceCannotBeReplayedAfterAuditedStop()throws Exception {
        long id=paidUnknown();reviews.decide(id,decision(id,"trace-stop-1"),"governance");String originalTrace=db.queryForObject("select trace_id from agent_experiment_trials where experiment_id=? and status='UNKNOWN'",String.class,service.get(id).evaluations().getFirst().experimentId());
        assertThatThrownBy(()->ledger.admitModel(json.createObjectNode().put("max_tokens",128),agent,null,null,originalTrace,"new-key-cannot-replay")).isInstanceOf(GatewayException.class);verify(upstream,times(1)).chatCompletions(any());
    }
    @Test void immutableDecisionReplaysExactPrincipalAndRejectsOtherPayloadOrKey()throws Exception {
        long id=paidUnknown();var request=decision(id,"same-decision-1");String a="subject-"+"x".repeat(150)+"A",b="subject-"+"x".repeat(150)+"B";
        var first=reviews.decide(id,request,a);assertThat(reviews.decide(id,request,a)).isEqualTo(first);
        assertThatThrownBy(()->reviews.decide(id,request,b)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->reviews.decide(id,new OptimizationUnknownReviewService.Decide("b".repeat(64),request.requestKey(),true),a)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(()->reviews.decide(id,decision(id,"different-decision-1"),a)).isInstanceOf(ConflictException.class);assertThat(db.queryForObject("select count(*) from agent_optimization_unknown_reviews",Long.class)).isEqualTo(1);
        assertThat(db.queryForObject("select evidence_json from agent_optimization_unknown_reviews",String.class)).doesNotContain("BASELINE prompt","MANUAL prompt","validation input",a);
    }
    @Test void concurrentOriginalDecisionCreatesOneAppendOnlyRecord()throws Exception {
        long id=paidUnknown();var request=decision(id,"concurrent-stop-1");var pool=Executors.newFixedThreadPool(4);
        try{List<Future<Boolean>> calls=new ArrayList<>();for(int i=0;i<4;i++)calls.add(pool.submit(()->reviews.decide(id,request,"governance").decisionCurrent()));for(var call:calls)assertThat(call.get(20,TimeUnit.SECONDS)).isTrue();}finally{pool.shutdownNow();}
        assertThat(db.queryForObject("select count(*) from agent_optimization_unknown_reviews",Long.class)).isEqualTo(1);assertThat(service.get(id).status()).isEqualTo("UNKNOWN");
    }
    @Test void staleEvidenceRevokedIdentityAndIncompleteLedgerCannotBeAccepted()throws Exception {
        long id=paidUnknown();var stale=decision(id,"stale-stop-1");db.update("update agent_experiment_trials set reason_code='OTHER_UNKNOWN' where experiment_id=? and status='UNKNOWN'",service.get(id).evaluations().getFirst().experimentId());
        assertThatThrownBy(()->reviews.decide(id,stale,"governance")).isInstanceOf(ConflictException.class);
        db.update("update runtime_connections set generation=? where instance_key=?",UUID.randomUUID().toString(),instance);assertThat(reviews.inspect(id).blockers()).contains("ORIGINAL_IDENTITY_NOT_REVOKED");assertThatThrownBy(()->reviews.decide(id,decision(id,"active-identity-1"),"governance")).isInstanceOf(ConflictException.class);
        db.update("update runtime_connections set generation=null where instance_key=?",instance);db.update("update budget_settlements set output_tokens=null");assertThat(reviews.inspect(id).blockers()).contains("BUDGET_EVIDENCE_INCOMPLETE");assertThatThrownBy(()->reviews.decide(id,decision(id,"missing-paid-1"),"governance")).isInstanceOf(ConflictException.class);
        assertThat(db.queryForObject("select count(*) from agent_optimization_unknown_reviews",Long.class)).isZero();
    }
    @Test void auditedEvidenceDriftRestoresBothAdmissionBlocksAndCannotRewriteDecision()throws Exception {
        long id=paidUnknown();reviews.decide(id,decision(id,"drift-stop-1"),"governance");db.update("update agent_experiment_trials set score=1 where experiment_id=? and status='UNKNOWN'",service.get(id).evaluations().getFirst().experimentId());
        assertThat(reviews.inspect(id).decisionCurrent()).isFalse();assertThat(reviews.hasBlockingRoots()).isTrue();assertThat(reviews.hasBlockingExperiments()).isTrue();assertThatThrownBy(()->reviews.decide(id,decision(id,"drift-stop-2"),"governance")).isInstanceOf(ConflictException.class);
    }
    @Test void legacyUnknownAndStandaloneUnknownStillBlockGlobalAdmission()throws Exception {
        long id=paidUnknown();db.update("delete from agent_optimization_protocols where run_id=?",id);assertThat(reviews.inspect(id).blockers()).contains("REGISTERED_PROTOCOL_REQUIRED");assertThatThrownBy(()->reviews.decide(id,decision(id,"legacy-stop-1"),"governance")).isInstanceOf(ConflictException.class);
        db.update("update agent_experiments set optimization_run_id=null where optimization_run_id=?",id);assertThat(reviews.hasBlockingExperiments()).isTrue();
    }
    @ParameterizedTest @CsvSource({"VIEWER,false","OPERATOR,false","GOVERNANCE_ADMIN,true","PLATFORM_ADMIN,true"})
    void unknownReviewUsesCentralCapabilityAndExplicitAcknowledgment(String role,boolean allowed)throws Exception {
        long id=paidUnknown();var identity=jwt().jwt(j->j.subject("reviewer")).authorities(new SimpleGrantedAuthority("ROLE_"+role));
        mvc.perform(get("/api/platform/me").with(identity)).andExpect(jsonPath("$.data.capabilities.reviewExperimentUnknowns").value(allowed));mvc.perform(get("/api/agent-optimizations/"+id+"/unknown-review").with(identity)).andExpect(status().isOk());
        var request=decision(id,"role-stop-1");mvc.perform(post("/api/agent-optimizations/"+id+"/unknown-review").with(identity).contentType("application/json").content(json.writeValueAsBytes(request))).andExpect(status().is(allowed?200:403));
    }
    @Test void falseAcknowledgmentAndAnonymousReviewAreRejected()throws Exception {
        long id=paidUnknown();var request=new OptimizationUnknownReviewService.Decide(reviews.inspect(id).evidenceDigest(),"no-consent-stop-1",false);
        mvc.perform(post("/api/agent-optimizations/"+id+"/unknown-review").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_GOVERNANCE_ADMIN"))).contentType("application/json").content(json.writeValueAsBytes(request))).andExpect(status().isBadRequest());mvc.perform(get("/api/agent-optimizations/"+id+"/unknown-review")).andExpect(status().isUnauthorized());assertThat(reviews.hasBlockingRoots()).isTrue();
    }
}
