package com.eap.platform.ax;
import com.eap.platform.agent.*;
import com.eap.platform.safety.*;
import com.eap.platform.substrate.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
@SpringBootTest(properties={"spring.flyway.enabled=true","eap.ax.enabled=true","eap.ax.worker-enabled=false",
 "eap.ax.runner-image=localhost:5001/test@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
 "eap.workflow.execution.enabled=true","eap.workflow.execution.worker-enabled=false","eap.workflow.execution.token=test-only",
 "eap.workflow.execution.allowed-hosts=localhost","eap.substrate.deploy-worker-enabled=false"})
@ActiveProfiles("test")
class AxIntegrationTests {
 @Autowired AxService service; @Autowired AxTaskRepository tasks; @Autowired AgentRepository agents;
 @MockitoBean AxClient client; @MockitoBean SubstrateClient substrate; @MockitoBean GenseeSafetyService safety;
 @Autowired com.eap.platform.workflow.WorkflowService workflows;
 @Autowired com.eap.platform.workflow.WorkflowExecutionService executions;
 @Autowired com.eap.platform.workflow.WorkflowExecutionProperties executionProperties;
 @Autowired com.eap.platform.registry.AgentRegistryService registry;
 @MockitoBean com.eap.platform.workflow.WorkflowTaskClient runtime;
 Long agent;
 @BeforeEach void setup(){tasks.deleteAll();Agent a=new Agent();a.setCode("AX-"+UUID.randomUUID());a.setName("AX test");a.setOwnerDepartment("test");a.setOwnerUser("test");a.setCategory("test");a.setLifecycleStatus(AgentLifecycleStatus.ACTIVE);a.setRiskLevel(RiskLevel.LOW);agent=agents.save(a).getId();
  when(safety.evaluate(any())).thenReturn(new SafetyDtos.SafetyEvaluationResponse(null,new SafetyDtos.DecisionResponse(1L,1L,"test",SafetyDecision.ALLOW,null,null,"test",null,null,null)));
 }
 AxService.Submit request(String key,String text){return new AxService.Submit(agent,key,text,"ECHO");}
 @Test void replayReusesDurableTaskButChangedInputConflicts(){var a=service.submit(request("key","one"),"operator");var b=service.submit(request("key","one"),"operator");assertThat(b.taskKey()).isEqualTo(a.taskKey());assertThat(tasks.count()).isEqualTo(1);assertThatThrownBy(()->service.submit(request("key","two"),"operator")).isInstanceOf(com.eap.platform.common.ConflictException.class);}
 @Test void concurrentReplayCreatesOnlyOneTask()throws Exception{
  try(var pool=java.util.concurrent.Executors.newFixedThreadPool(4)){var futures=new ArrayList<java.util.concurrent.Future<String>>();for(int i=0;i<4;i++)futures.add(pool.submit(()->service.submit(request("concurrent","same"),"operator").taskKey()));var keys=new HashSet<String>();for(var f:futures)keys.add(f.get());assertThat(keys).hasSize(1);assertThat(tasks.count()).isEqualTo(1);}
 }
 @Test void axRunningIsNotBusinessSuccess(){var v=service.submit(request("ready","test"),"op");service.processNext();when(client.ensure(anyString(),anyString(),anyMap())).thenReturn(new AxClient.State("Running",false));service.processNext();assertThat(service.get(v.taskKey()).status()).isEqualTo("PROVISIONING");assertThat(service.get(v.taskKey()).sandboxPhase()).isEqualTo("Running");}
 @Test void rejectsWrongAndNonterminalReceipts(){var v=service.submit(request("receipt","test"),"op");var t=tasks.findByTaskKey(v.taskKey()).orElseThrow();t.status="RUNNING";tasks.save(t);service.acceptReceipt(t.id,"{\"taskKey\":\"wrong\",\"status\":\"SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8));assertThat(service.get(v.taskKey()).status()).isEqualTo("RUNNING");receipt(t,"Ready");assertThat(service.get(v.taskKey()).status()).isEqualTo("RUNNING");receipt(t,"SUCCEEDED");assertThat(service.get(v.taskKey()).status()).isEqualTo("SUCCEEDED");assertThat(service.content(t.taskKey).path("result").path("output").asText()).isEqualTo("result");assertThat(service.list().toString()).doesNotContain("objective");}
 @Test void timeoutIsUnknownAndLateReceiptCanResolveIt(){var v=service.submit(request("expired","test"),"op");var t=tasks.findByTaskKey(v.taskKey()).orElseThrow();t.status="RUNNING";t.deadlineAt=Instant.now().minusSeconds(1);tasks.save(t);service.processNext();assertThat(service.get(t.taskKey).status()).isEqualTo("EXECUTION_UNKNOWN");verifyNoInteractions(client);receipt(t,"SUCCEEDED");assertThat(service.get(t.taskKey).status()).isEqualTo("SUCCEEDED");}
 @Test void governorBlocksBeforeExternalCall(){when(safety.evaluate(any())).thenReturn(new SafetyDtos.SafetyEvaluationResponse(null,new SafetyDtos.DecisionResponse(1L,1L,"test",SafetyDecision.DENY,null,null,"deny",null,null,null)));var v=service.submit(request("blocked","test"),"op");service.processNext();assertThat(v.status()).isEqualTo("BLOCKED");verifyNoInteractions(client);}
 @Test void suspendAckAloneDoesNotProveSandboxStopped(){var v=service.submit(request("suspend","test"),"op");var t=tasks.findByTaskKey(v.taskKey()).orElseThrow();t.status="RUNNING";tasks.save(t);receipt(t,"SUCCEEDED");when(client.get(anyString())).thenReturn(new AxClient.State("Suspended",false));when(substrate.get(any())).thenReturn(new SubstrateClient.ActorState("RUNNING"));service.processNext();assertThat(service.get(t.taskKey).suspended()).isFalse();when(substrate.get(any())).thenReturn(new SubstrateClient.ActorState("SUSPENDED"));service.processNext();assertThat(service.get(t.taskKey).suspended()).isTrue();}
 @Test void lifecycleOperatorDoesNotReplaceOriginalSubmitter(){var v=service.submit(request("owner","test"),"creator");var t=tasks.findByTaskKey(v.taskKey()).orElseThrow();t.status="RUNNING";tasks.save(t);receipt(t,"SUCCEEDED");when(client.get(anyString())).thenReturn(new AxClient.State("Suspended",false));service.control(t.taskKey,"suspend","another-operator");assertThat(service.get(t.taskKey).requestedBy()).isEqualTo("creator");}

 @Test void workflowSubmissionPersistsLinkAndDoesNotAliasStandaloneTask(){
  String run="wf-exec-test",node="step-1";
  var task=service.submitWorkflow(17L,run,node,agent,"objective",Map.of("eapPreviousResults",Map.of("upstream","PRIVATE-OUTPUT")),"ECHO","owner",Instant.now().plusSeconds(60));
  assertThat(task.workflowRunKey()).isEqualTo(run);assertThat(task.workflowId()).isEqualTo(17L);
  assertThat(task.toString()).doesNotContain("PRIVATE-OUTPUT");
  assertThat(service.content(task.taskKey()).path("input").path("objective").asText()).contains("PRIVATE-OUTPUT");
  assertThat(service.submitWorkflow(17L,run,node,agent,"objective",Map.of("eapPreviousResults",Map.of("upstream","PRIVATE-OUTPUT")),"ECHO","owner",Instant.now().plusSeconds(60)).taskKey()).isEqualTo(task.taskKey());
  assertThatThrownBy(()->service.submitWorkflow(17L,run,node,agent,"changed",Map.of(),"ECHO","owner",Instant.now().plusSeconds(60))).isInstanceOf(com.eap.platform.common.ConflictException.class);
 }
 @Test void workflowRejectsDisabledAgentAndOversizedContextBeforeQueueing(){
  assertThatThrownBy(()->service.submitWorkflow(1L,"run","node",agent,"test",Map.of("large","x".repeat(8000)),"LLM","owner",Instant.now().plusSeconds(60))).isInstanceOf(com.eap.platform.common.BusinessException.class);
  var a=agents.findById(agent).orElseThrow();a.setLifecycleStatus(AgentLifecycleStatus.RETIRED);agents.save(a);
  assertThatThrownBy(()->service.validateWorkflowAgent(agent)).isInstanceOf(com.eap.platform.common.BusinessException.class);
  assertThat(tasks.count()).isZero();
 }
 @Test void mixedWorkflowHandsOffAxOutputOnlyAfterConfirmedSuspend()throws Exception{
  Long id=mixed();var queued=executions.submit(id,new com.eap.platform.workflow.WorkflowDtos.RunRequest("mixed",Map.of(),"mixed"),"owner");
  when(runtime.invoke(any(),anyMap(),any(),any())).thenAnswer(invocation->{
   invocation.getArgument(3,com.eap.platform.workflow.WorkflowTaskClient.Prepared.class).beforeDispatch();
   return new com.eap.platform.workflow.WorkflowTaskClient.Receipt("SUCCEEDED","ok",200,new com.fasterxml.jackson.databind.node.TextNode("native-result"));
  });
  try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
   var future=pool.submit(()->executions.processNext());var t=awaitTask(queued.runKey());
   t.status="RUNNING";tasks.save(t);receipt(t,"SUCCEEDED");
   Thread.sleep(650);verifyNoInteractions(runtime);
   t=tasks.findByTaskKey(t.taskKey).orElseThrow();t.suspended=true;tasks.save(t);
   future.get(10,java.util.concurrent.TimeUnit.SECONDS);
  }
  var done=executions.get(id,queued.runKey());assertThat(done.status()).isEqualTo("SUCCEEDED");
  assertThat(done.executionMode()).isEqualTo("EAP_MIXED_TASK_V1");
  assertThat(done.steps().get(1).runtimeInstanceKey()).startsWith("ax://");
  assertThat(done.steps().get(1).output()).isNull();assertThat(executions.outputs(id,queued.runKey()).get("ax").asText()).isEqualTo("result");
  verify(runtime).invoke(any(),argThat(payload->payload.get("context").toString().contains("result")),any(),any());
  assertThat(executions.submit(id,new com.eap.platform.workflow.WorkflowDtos.RunRequest("mixed",Map.of(),"mixed"),"owner").runKey()).isEqualTo(queued.runKey());assertThat(tasks.count()).isEqualTo(1);
 }
 @Test void axFailureStopsDownstreamWorkflow()throws Exception{
  Long id=mixed();var queued=executions.submit(id,new com.eap.platform.workflow.WorkflowDtos.RunRequest("fail"),"owner");
  try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
   var future=pool.submit(()->executions.processNext());var t=awaitTask(queued.runKey());t.status="RUNNING";tasks.save(t);receipt(t,"FAILED");future.get(10,java.util.concurrent.TimeUnit.SECONDS);
  }
  assertThat(executions.get(id,queued.runKey()).status()).isEqualTo("FAILED");verifyNoInteractions(runtime);
 }
 @Test void unknownAxDoesNotDispatchDownstreamOrCreateReplacement()throws Exception{
  Long id=mixed();var queued=executions.submit(id,new com.eap.platform.workflow.WorkflowDtos.RunRequest("unknown"),"owner");
  try(var pool=java.util.concurrent.Executors.newSingleThreadExecutor()){
   var future=pool.submit(()->executions.processNext());var t=awaitTask(queued.runKey());t.status="RUNNING";tasks.save(t);receipt(t,"EXECUTION_UNKNOWN");future.get(10,java.util.concurrent.TimeUnit.SECONDS);
  }
  assertThat(executions.get(id,queued.runKey()).status()).isEqualTo("EXECUTION_UNKNOWN");executions.processNext();assertThat(tasks.count()).isEqualTo(1);verifyNoInteractions(runtime);
 }
 @Test void invalidBackendRejectedBeforeSubmission(){
  Long id=mixed();workflows.upsertNode(id,new com.eap.platform.workflow.WorkflowDtos.NodeRequest("ax","ax","AGENT",agent,null,0,0,"{\"executionBackend\":\"SHELL\"}"));
  assertThatThrownBy(()->executions.submit(id,new com.eap.platform.workflow.WorkflowDtos.RunRequest("invalid"),"owner")).isInstanceOf(com.eap.platform.common.BusinessException.class);assertThat(tasks.count()).isZero();
 }
 private Long mixed(){
  registry.registerRuntime(agent,new com.eap.platform.registry.AgentRegistryDtos.RuntimeInstanceRequest("mix-"+UUID.randomUUID(),"http://localhost/task","EAP_HTTP_TASK_V1",false));
  Long id=workflows.create(new com.eap.platform.workflow.WorkflowDtos.WorkflowRequest("mix-"+UUID.randomUUID(),"mixed","test")).id();
  for(String key:List.of("start","ax","native","end")){
   String config=key.equals("ax")?"{\"executionBackend\":\"AX\",\"axMode\":\"ECHO\"}":key.equals("native")?"{\"inputFrom\":[\"ax\"]}":"{}";
   workflows.upsertNode(id,new com.eap.platform.workflow.WorkflowDtos.NodeRequest(key,key,key.equals("start")?"START":key.equals("end")?"END":"AGENT",Set.of("ax","native").contains(key)?agent:null,null,0,0,config));
  }
  workflows.addEdge(id,new com.eap.platform.workflow.WorkflowDtos.EdgeRequest("start","ax","always"));workflows.addEdge(id,new com.eap.platform.workflow.WorkflowDtos.EdgeRequest("ax","native","always"));workflows.addEdge(id,new com.eap.platform.workflow.WorkflowDtos.EdgeRequest("native","end","always"));return id;
 }
 private AxTask awaitTask(String run)throws Exception{
  for(int i=0;i<100;i++){var found=tasks.findAll().stream().filter(t->run.equals(t.workflowRunKey)).findFirst();if(found.isPresent())return found.get();Thread.sleep(50);}throw new AssertionError("AX task not queued");
 }
 void receipt(AxTask t,String status){service.acceptReceipt(t.id,("{\"taskKey\":\""+t.taskKey+"\",\"status\":\""+status+"\",\"output\":\"result\"}").getBytes(StandardCharsets.UTF_8));}
}
