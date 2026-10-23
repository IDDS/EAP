package com.eap.platform.experiment;
import com.eap.platform.agent.*;
import com.eap.platform.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.flyway.enabled=true","eap.security.mode=JWT","eap.security.issuer-uri=https://idp.invalid.example/realm/eap","eap.security.jwk-set-uri=https://idp.invalid.example/jwks","eap.experiment.enabled=true","eap.experiment.worker-initial-delay-ms=3600000","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test") @AutoConfigureMockMvc
class AgentExperimentIntegrationTests {
 @Autowired AgentExperimentService service;@Autowired AgentService agents;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper mapper;@Autowired MockMvc mvc;
 @MockitoBean AgentExperimentClient client;
 Long agentId;String instance,endpoint,template,digest="a".repeat(64);
 @BeforeEach void setup()throws Exception {
  agentId=agents.create(new AgentDtos.AgentRequest("EXP-"+UUID.randomUUID(),"Experiment test","","Test","Owner","Test",RiskLevel.LOW)).id();agents.updateStatus(agentId,AgentLifecycleStatus.ACTIVE);
  instance="eap-agents/a"+agentId+"-v1-test";endpoint="substrate://"+instance;template="eap-agents/experiment-"+agentId;
  jdbc.update("insert into agent_runtime_instances(agent_id,instance_key,endpoint,protocol,dispatch_authenticated,status,load_score,created_at,updated_at) values(?,?,?,'SUBSTRATE',false,'ONLINE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",agentId,instance,endpoint);
  jdbc.update("insert into substrate_agent_deployments(deployment_key,agent_id,version,source_type,status,config_json,image_ref,atespace,template_name,instance_key,steps_json,requested_by,created_at,updated_at) values(?,?,1,'IMAGE','READY','{}',?,'eap-agents',?,?,'[]','test',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)","exp-deploy-"+agentId,agentId,"registry.test/runner@sha256:"+"b".repeat(64),"experiment-"+agentId,instance);
  var p=mapper.createObjectNode().put("protocol","EAP_STATELESS_PROMPT_V1").put("agentId",agentId.toString()).put("model","test-model").put("systemPrompt","Baseline prompt").put("maxTokens",1024).put("profileDigest",digest).put("context","EMPTY").put("tools","DISABLED");
  when(client.profile(endpoint)).thenReturn(new AgentExperimentClient.RuntimeProfile(p,"actor-uid-"+agentId,template));
 }
 @AfterEach void clean(){var ids=jdbc.queryForList("select id from agent_experiments where agent_id=?",Long.class,agentId);for(var id:ids){jdbc.update("delete from experiment_diagnostic_receipts where trial_id in (select id from agent_experiment_trials where experiment_id=?)",id);jdbc.update("delete from experiment_diagnostic_decisions where trial_id in (select id from agent_experiment_trials where experiment_id=?)",id);jdbc.update("delete from agent_experiment_trials where experiment_id=?",id);jdbc.update("delete from agent_experiment_variants where experiment_id=?",id);jdbc.update("delete from agent_experiments where id=?",id);}}
 AgentExperimentDtos.Submit request(String key){return new AgentExperimentDtos.Submit(agentId,instance,digest,"Candidate prompt",128,List.of(new AgentExperimentDtos.Sample("test input","expected output")),true,key);}
 AgentExperimentClient.Receipt output(String value,boolean usage)throws Exception {var b=mapper.createObjectNode().put("output",value).put("finishReason","stop").put("latencyMs",24);if(usage)b.set("usage",mapper.createObjectNode().put("prompt_tokens",7).put("completion_tokens",3));return new AgentExperimentClient.Receipt("SUCCEEDED",null,b);}
 @Test void immutableVersionsExecuteNewOutputsAndScoreEachVariant()throws Exception {
  var e=service.submit(request("versions-test-1"),"author");assertThat(e.trials()).hasSize(2);assertThat(e.variants()).hasSize(2);assertThat(e.variants().get(0).configDigest()).isNotEqualTo(e.variants().get(1).configDigest());
  var baseline=service.claim();assertThat(baseline.body().get("systemPrompt")).isEqualTo("Baseline prompt");assertThat(baseline.body().get("input")).isEqualTo("test input");service.finish(baseline,output("wrong answer",true));
  var candidate=service.claim();assertThat(candidate.body().get("systemPrompt")).isEqualTo("Candidate prompt");assertThat(candidate.body().get("input")).isEqualTo(baseline.body().get("input"));assertThat(candidate.body().get("maxTokens")).isEqualTo(baseline.body().get("maxTokens"));service.finish(candidate,output("expected output",true));
  var result=service.get(e.id());assertThat(result.status()).isEqualTo("COMPLETED");assertThat(result.trials()).extracting(AgentExperimentDtos.Trial::score).containsExactly(0,1);assertThat(result.trials()).allMatch(t->"PROVIDER_REPORTED".equals(t.usageSource())&&t.inputTokens()==7&&t.outputTokens()==3);assertThat(service.content(e.id()).outputs()).extracting(AgentExperimentDtos.TrialContent::output).containsExactly("wrong answer","expected output");
  assertThat(service.claim()).isNull();
 }
 @Test void concurrentIdempotentSubmissionPersistsOneExperiment()throws Exception {
  var r=request("concurrency-test-1");var pool=Executors.newFixedThreadPool(6);var start=new CountDownLatch(1);
  try{List<Future<Long>> fs=new ArrayList<>();for(int i=0;i<6;i++)fs.add(pool.submit(()->{start.await();return service.submit(r,"author").id();}));start.countDown();Set<Long> ids=new HashSet<>();for(var f:fs)ids.add(f.get(20,TimeUnit.SECONDS));assertThat(ids).hasSize(1);verify(client,times(1)).profile(endpoint);}
  finally{pool.shutdownNow();}
  var changed=new AgentExperimentDtos.Submit(agentId,instance,digest,"Another candidate",128,r.samples(),true,r.requestKey());assertThatThrownBy(()->service.submit(changed,"author")).isInstanceOf(ConflictException.class);
 }
 @Test void unknownStopsRemainingTrialsAndNeverResubmits(){var e=service.submit(request("unknown-test-1"),"author");var d=service.claim();service.finish(d,new AgentExperimentClient.Receipt("UNKNOWN","RECEIPT_UNKNOWN",null));assertThat(service.get(e.id()).status()).isEqualTo("UNKNOWN");assertThat(service.get(e.id()).trials()).extracting(AgentExperimentDtos.Trial::status).containsExactly("UNKNOWN","CANCELLED");assertThat(service.claim()).isNull();assertThat(service.submit(request("unknown-test-1"),"author").id()).isEqualTo(e.id());assertThatThrownBy(()->service.submit(request("unknown-test-2"),"author")).isInstanceOf(ConflictException.class);}
 @Test void missingUsageIsUnknownAndNotZero()throws Exception {var e=service.submit(request("usage-test-1"),"author");service.finish(service.claim(),output("expected output",false));var t=service.get(e.id()).trials().get(0);assertThat(t.usageSource()).isEqualTo("UNKNOWN");assertThat(t.inputTokens()).isNull();assertThat(t.outputTokens()).isNull();}
 @Test void expiredInFlightRemainsUnknownAcrossWorkerRestarts(){var e=service.submit(request("expired-test-1"),"author");var d=service.claim();jdbc.update("update agent_experiment_trials set started_at=? where id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(200)),d.trialId());assertThat(service.claim()).isNull();assertThat(service.get(e.id()).status()).isEqualTo("UNKNOWN");assertThat(service.claim()).isNull();verify(client,never()).execute(any());}
 @Test void disabledAgentFailsBeforeDispatch(){var e=service.submit(request("disabled-test-1"),"author");agents.updateStatus(agentId,AgentLifecycleStatus.RETIRED);assertThat(service.claim()).isNull();assertThat(service.get(e.id()).status()).isEqualTo("FAILED");verify(client,never()).execute(any());}
 @Test void mutableOrUnownedDeploymentIsRejected(){jdbc.update("update substrate_agent_deployments set image_ref='registry.test/runner:mutable' where agent_id=?",agentId);assertThatThrownBy(()->service.submit(request("mutable-test-1"),"author")).isInstanceOf(BusinessException.class);assertThat(service.list()).isEmpty();}
 @ParameterizedTest @CsvSource({"VIEWER,false,false","OPERATOR,false,false","GOVERNANCE_ADMIN,false,true","PLATFORM_ADMIN,true,true"})
 void rolesProtectExecutionAndBodies(String role,boolean execute,boolean content)throws Exception {
  var identity=jwt().jwt(j->j.subject("actor-"+role)).authorities(new SimpleGrantedAuthority("ROLE_"+role));var e=service.submit(request("role-seed-1"),"seed");
  mvc.perform(get("/api/platform/me").with(identity)).andExpect(jsonPath("$.data.capabilities.runAgentExperiments").value(execute));
  var r=mvc.perform(post("/api/agent-experiments/profile").with(identity).contentType("application/json").content(mapper.writeValueAsBytes(new AgentExperimentDtos.Target(agentId,instance))));r.andExpect(execute?status().isOk():status().isForbidden());
  mvc.perform(get("/api/agent-experiments/"+e.id()+"/content").with(identity)).andExpect(content?status().isOk():status().isForbidden());
  var meta=mvc.perform(get("/api/agent-experiments/"+e.id()).with(identity)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertThat(meta).doesNotContain("Baseline prompt","Candidate prompt","test input","expected output");
 }
 @Test void invalidLimitsAndUnconfirmedDataAreRejectedByHttp()throws Exception {var admin=jwt().authorities(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));var bad=mapper.valueToTree(request("invalid-test-1"));((com.fasterxml.jackson.databind.node.ObjectNode)bad).put("maxTokens",257);mvc.perform(post("/api/agent-experiments").with(admin).contentType("application/json").content(mapper.writeValueAsBytes(bad))).andExpect(status().isBadRequest());((com.fasterxml.jackson.databind.node.ObjectNode)bad).put("maxTokens",128).put("publicSamplesConfirmed",false);mvc.perform(post("/api/agent-experiments").with(admin).contentType("application/json").content(mapper.writeValueAsBytes(bad))).andExpect(status().isBadRequest());assertThat(service.list()).isEmpty();}
 long root(String state)throws Exception {
  var dataset=mapper.createArrayNode();dataset.add(mapper.createObjectNode().put("id","train-1").put("groupId","g1").put("input","test input").put("expected","expected output").put("split","TRAIN"));dataset.add(mapper.createObjectNode().put("id","holdout-1").put("groupId","g2").put("input","sealed input").put("expected","sealed output").put("split","HOLDOUT"));
  jdbc.update("insert into agent_optimizations(agent_id,instance_key,submission_key,fingerprint,profile_json,dataset_json,dataset_digest,manual_prompt,engine,engine_version,max_tokens,max_metric_calls,max_calls,max_output_tokens,status,requested_by,public_samples_confirmed,reflection_use_confirmed,created_at,updated_at) values(?,?,?,'fingerprint',?,?,'digest','Candidate prompt','GEPA','gepa-0.1.4',128,8,32,8192,?,'author',true,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",agentId,instance,UUID.randomUUID().toString(),mapper.writeValueAsString(mapper.createObjectNode().put("profileDigest",digest)),mapper.writeValueAsString(dataset),state);
  return jdbc.queryForObject("select max(id) from agent_optimizations where agent_id=?",Long.class,agentId);
 }
 @Test void linkedOptimizationCannotInventItsParentOrChangeFrozenScope()throws Exception {
  var r=request("parent-scope-1");assertThatThrownBy(()->service.submitOptimization(r,"author",Long.MAX_VALUE)).isInstanceOf(BusinessException.class);
  long root=root("SEARCHING");assertThatThrownBy(()->service.submitOptimization(r,"another-author",root)).isInstanceOf(ConflictException.class);
  jdbc.update("update agent_optimizations set instance_key='foreign-instance' where id=?",root);assertThatThrownBy(()->service.submitOptimization(r,"author",root)).isInstanceOf(ConflictException.class);
  jdbc.update("update agent_optimizations set instance_key=?,max_tokens=64 where id=?",instance,root);assertThatThrownBy(()->service.submitOptimization(r,"author",root)).isInstanceOf(ConflictException.class);
  assertThat(service.list()).isEmpty();verify(client,never()).profile(any());
 }
 @Test void optimizationSeedStillRunsTwoFreshTrialsButPublicEqualCandidateIsRejected()throws Exception {
  long root=root("SEARCHING");var r=request("seed-repeat-1");var seed=new AgentExperimentDtos.Submit(agentId,instance,digest,"Baseline prompt",128,r.samples(),true,r.requestKey());
  assertThatThrownBy(()->service.submit(seed,"author")).isInstanceOf(BusinessException.class);
  var experiment=service.submitOptimization(seed,"author",root);assertThat(experiment.trials()).hasSize(2);assertThat(experiment.trials()).extracting(AgentExperimentDtos.Trial::traceId).doesNotHaveDuplicates();
  assertThat(jdbc.queryForObject("select optimization_run_id from agent_experiments where id=?",Long.class,experiment.id())).isEqualTo(root);
  service.finish(service.claim(),output("first new output",true));service.finish(service.claim(),output("second new output",true));
  assertThat(service.content(experiment.id()).outputs()).extracting(AgentExperimentDtos.TrialContent::output).containsExactly("first new output","second new output");
 }
 @Test void searchCannotConsumeHoldoutAndHoldoutCannotConsumeUnsealedCandidate()throws Exception {
  long root=root("SEARCHING");var r=request("sealed-holdout-1");var sealed=new AgentExperimentDtos.Submit(agentId,instance,digest,"Candidate prompt",128,List.of(new AgentExperimentDtos.Sample("sealed input","sealed output")),true,r.requestKey());
  assertThatThrownBy(()->service.submitOptimization(sealed,"author",root)).isInstanceOf(ConflictException.class);
  jdbc.update("update agent_optimizations set status='HOLDOUT' where id=?",root);
  assertThatThrownBy(()->service.submitOptimization(r,"author",root)).isInstanceOf(ConflictException.class);
  var changed=new AgentExperimentDtos.Submit(agentId,instance,digest,"unsealed prompt",128,sealed.samples(),true,sealed.requestKey());assertThatThrownBy(()->service.submitOptimization(changed,"author",root)).isInstanceOf(ConflictException.class);
  assertThat(service.list()).isEmpty();var experiment=service.submitOptimization(sealed,"author",root);assertThat(experiment.trials()).hasSize(2);
 }
 @Test void closedOptimizationCannotAcquireNewExperimentDespiteSameRequester()throws Exception {
  long root=root("COMPLETED");assertThatThrownBy(()->service.submitOptimization(request("closed-root-1"),"author",root)).isInstanceOf(ConflictException.class);assertThat(service.list()).isEmpty();verify(client,never()).profile(any());
 }

 @Test void optimizationSamplesRequireIndependentRootPurposeConfirmation()throws Exception {
  long root=root("SEARCHING");jdbc.update("update agent_optimizations set public_samples_confirmed=false where id=?",root);assertThatThrownBy(()->service.submitOptimization(request("purpose-root-1"),"author",root)).isInstanceOf(BusinessException.class);assertThat(service.list()).isEmpty();verify(client,never()).profile(any());
 }

 @Test void publicSubmissionPreservesOriginalT1FingerprintAcrossOptimizationMigration()throws Exception {
  var r=request("legacy-fingerprint-1");String actor="legacy-author";
  String legacy=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(List.of(r.agentId(),r.instanceKey(),r.profileDigest(),r.candidatePrompt(),r.maxTokens(),r.samples(),r.publicSamplesConfirmed(),actor))));
  var e=service.submit(r,actor);assertThat(jdbc.queryForObject("select request_fingerprint from agent_experiments where id=?",String.class,e.id())).isEqualTo(legacy);
  jdbc.update("update agent_experiments set request_fingerprint=? where id=?",legacy,e.id());assertThat(service.submit(r,actor).id()).isEqualTo(e.id());verify(client,times(1)).profile(endpoint);assertThat(e.trials()).hasSize(2);
 }

}
