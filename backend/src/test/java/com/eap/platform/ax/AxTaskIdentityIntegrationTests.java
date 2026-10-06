package com.eap.platform.ax;

import com.eap.platform.agent.*;
import com.eap.platform.registry.*;
import com.eap.platform.safety.*;
import com.eap.platform.substrate.*;
import com.eap.platform.common.*;
import com.eap.platform.gateway.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:task-identity-utc;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE;INIT=SET TIME ZONE '+08:00'","spring.flyway.enabled=true","eap.budget.enabled=true","eap.ax.enabled=true","eap.ax.worker-enabled=false","eap.ax.instance-identity-enabled=true",
 "eap.ax.runner-image=localhost:5001/task@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
 "eap.ax.legacy-runner-image=localhost:5001/task@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
 "eap.workflow.execution.worker-enabled=false","eap.substrate.deploy-worker-enabled=false"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("test")
class AxTaskIdentityIntegrationTests {
 @Autowired org.springframework.test.web.servlet.MockMvc mvc; @Autowired GatewayProperties gatewayConfig;
 @Autowired AxService service; @Autowired AxTaskRepository tasks; @Autowired AgentRepository agents;
 @Autowired TaskRuntimeIdentityService identities; @Autowired RuntimeConnectionService authentication;
 @Autowired RuntimeCredentialDeliveryService delivery; @Autowired AgentRuntimeInstanceRepository runtimes;
 @Autowired RuntimeConnectionProperties config; @Autowired SubstrateProperties props; @Autowired AxProperties ax;
 @Autowired com.eap.platform.budget.BudgetLedger budgets; @Autowired AgentRegistryService registry; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
 @MockitoBean OpenAiCompatibleGatewayService gateway;
 @MockitoBean AxClient client; @MockitoBean SubstrateClient nativeClient; @MockitoBean GenseeSafetyService safety;
 Long agent;HttpServer router;KeyPair signing,encryption;
 Map<String,String> installed=new ConcurrentHashMap<>();Map<String,byte[]> receipts=new ConcurrentHashMap<>();
 AtomicInteger posts=new AtomicInteger();AtomicBoolean loseInstall=new AtomicBoolean(),revokeOnInstall=new AtomicBoolean();
 AtomicReference<Throwable> serverFailure=new AtomicReference<>();
 String nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
 @BeforeEach void setup()throws Exception{
  tasks.deleteAll();signing=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var rsa=KeyPairGenerator.getInstance("RSA");rsa.initialize(2048);encryption=rsa.generateKeyPair();
  config.setSigningKey(UUID.randomUUID()+"-synthetic-test-only");config.setDeliveryPrivateKey(Base64.getEncoder().encodeToString(signing.getPrivate().getEncoded()));config.setDeliveryPublicKey(Base64.getEncoder().encodeToString(signing.getPublic().getEncoded()));config.setLeaseSeconds(900);
  props.setEnabled(true);props.setInventoryAtespaces("eap-ax");props.setGatewayUrl("http://127.0.0.1");props.setGatewayHost("127.0.0.1");
  ax.setInstanceIdentityEnabled(true);ax.setEnabled(true);ax.setRunnerImage("localhost:5001/task@sha256:"+"a".repeat(64));ax.setLegacyRunnerImage("localhost:5001/task@sha256:"+"b".repeat(64));
  var a=new Agent();a.setCode("TASK-IDENTITY-"+UUID.randomUUID());a.setName("Task identity test");a.setOwnerDepartment("test");a.setOwnerUser("test");a.setCategory("test");a.setLifecycleStatus(AgentLifecycleStatus.ACTIVE);a.setRiskLevel(RiskLevel.LOW);agent=agents.save(a).getId();
  when(safety.evaluate(any())).thenReturn(new SafetyDtos.SafetyEvaluationResponse(null,new SafetyDtos.DecisionResponse(1L,1L,"test",SafetyDecision.ALLOW,null,null,"test",null,null,null)));
  when(client.ensure(anyString(),anyString(),anyMap())).thenAnswer(i->{var t=task(i.getArgument(0));return state(t,true);});
  when(client.get(anyString())).thenAnswer(i->state(task(i.getArgument(0)),false));
  when(nativeClient.inspect(any())).thenAnswer(i->{SubstrateActorRef ref=i.getArgument(0);var t=task(ref.name());return new SubstrateClient.ActorInventory(ref.atespace(),ref.name(),uid(t),template(t),"RUNNING");});
  when(nativeClient.get(any())).thenReturn(new SubstrateClient.ActorState("SUSPENDED"));when(nativeClient.resume(any())).thenReturn(new SubstrateClient.ActorState("RUNNING"));when(nativeClient.suspend(any())).thenReturn(new SubstrateClient.ActorState("SUSPENDED"));
  router=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  router.createContext("/eap/",e->{try{
   String key=e.getRequestHeaders().getFirst(SubstrateActorRef.ROUTING_HEADER);String name=key.substring(key.indexOf('/')+1);var t=task(name);String path=e.getRequestURI().getPath();byte[] output;
   if(path.startsWith("/eap/identity/")){
    assertThat(e.getRequestHeaders().getFirst("Authorization")).isNull();var wire=json.readTree(e.getRequestBody());byte[] payload=Base64.getUrlDecoder().decode(wire.path("payload").asText());var verifier=Signature.getInstance("Ed25519");verifier.initVerify(signing.getPublic());verifier.update(payload);assertThat(verifier.verify(Base64.getUrlDecoder().decode(wire.path("signature").asText()))).isTrue();var in=json.readTree(payload);Map<String,?> result;
    if(path.endsWith("challenge"))result=Map.of("protocol","EAP_IDENTITY_V1","operation",in.path("operation").asText(),"nonce",nonce,"publicKey",Base64.getUrlEncoder().withoutPadding().encodeToString(encryption.getPublic().getEncoded()),"actorUid",uid(t),"instanceKey",key);
    else{
     var cipher=Cipher.getInstance("RSA/ECB/OAEPPadding");cipher.init(Cipher.DECRYPT_MODE,encryption.getPrivate(),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));String token=new String(cipher.doFinal(Base64.getUrlDecoder().decode(in.path("ciphertext").asText())),StandardCharsets.UTF_8);installed.put(key,token);
     if(revokeOnInstall.get())authentication.revoke(key,"test-revocation");
     if(loseInstall.getAndSet(false)){e.sendResponseHeaders(503,-1);e.close();return;}
     result=Map.of("operation",in.path("operation").asText(),"proof",proof(token,"eap-delivery-v1|"+in.path("operation").asText()+"|"+nonce+"|"+key+"|"+in.path("revision").asText()));
    }output=json.writeValueAsBytes(result);
   }else{
    String supplied=e.getRequestHeaders().getFirst("Authorization");assertThat(supplied).isEqualTo("Bearer "+installed.get(key));
    if(path.equals("/eap/task")){
     assertThat(t.status).isEqualTo("RUNNING");assertThat(authentication.status(key).connection()).isEqualTo("READY");assertThat(json.readTree(e.getRequestBody()).path("taskKey").asText()).isEqualTo(name);posts.incrementAndGet();
     receipts.put(key,json.writeValueAsBytes(Map.of("taskKey",name,"status","SUCCEEDED","output","synthetic-result","traceId","a123456789abcdefa123456789abcdef")));output="{}".getBytes(StandardCharsets.UTF_8);
    }else{output=receipts.get(key);if(output==null){e.sendResponseHeaders(404,-1);e.close();return;}}
   }
   e.sendResponseHeaders(200,output.length);e.getResponseBody().write(output);e.close();
  }catch(Throwable ex){serverFailure.compareAndSet(null,ex);e.sendResponseHeaders(500,-1);e.close();}});
  router.start();props.setRouterUrl("http://127.0.0.1:"+router.getAddress().getPort());
 }
 @AfterEach void close(){router.stop(0);if(serverFailure.get()!=null)throw new AssertionError("synthetic runner contract failed",serverFailure.get());}
 AxTask task(String name){return tasks.findByTaskKey(name).orElseThrow();}
 String uid(AxTask t){return UUID.nameUUIDFromBytes(t.taskKey.getBytes(StandardCharsets.UTF_8)).toString();}
 String template(AxTask t){return "eap-ax/t-"+t.taskKey;}
 AxClient.State state(AxTask t,boolean ready){return new AxClient.State("Running",ready,uid(t),template(t),true);}
 AxTask submit(String key){return task(service.submit(new AxService.Submit(agent,key,"public synthetic objective","ECHO"),"operator").taskKey());}
 AxTask dispatch(String key){var t=submit(key);service.processNext();service.processNext();assertThat(service.get(t.taskKey).status()).isEqualTo("RUNNING");return task(t.taskKey);}
 String proof(String key,String input)throws Exception{var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));}
 @Test void signedDeliveryAndCreateReceiptPrecedeExactlyOneDispatchAndTraceMatches(){
  var t=dispatch("ready");assertThat(posts).hasValue(1);assertThat(t.identityMode).isEqualTo("PER_INSTANCE_V1");assertThat(t.actorUid).isEqualTo(uid(t));assertThat(service.get(t.taskKey).identityStatus()).isEqualTo("READY");assertThat(authentication.status(t.instanceKey).identityExpiresAt()).isEqualTo(t.identityUntil);
  var access=authentication.authenticate(t.instanceKey,installed.get(t.instanceKey));authentication.validateTaskAttribution(access,t.taskKey,null);assertThatThrownBy(()->authentication.validateLegacyTaskAttribution(t.taskKey)).isInstanceOf(GatewayException.class);
  service.processNext();var result=service.content(t.taskKey).path("result");assertThat(result.path("traceId").asText()).isEqualTo(RuntimeScopedTelemetry.trace(t.instanceKey,result.path("runtimeTraceId").asText()));service.processNext();assertThat(service.get(t.taskKey).suspended()).isTrue();assertThat(posts).hasValue(1);
  verify(client).ensure(eq(t.taskKey),eq(t.runnerImage),argThat(env->env.keySet().equals(Set.of("EAP_AGENT_ID","EAP_AX_TASK_KEY","EAP_GATEWAY_URL","EAP_IDENTITY_MODE","EAP_IDENTITY_DIR","EAP_IDENTITY_PUBLIC_KEY"))));
 }
 @Test void sameAgentOtherTaskMissingHeaderTerminalAndChangedIntentAreRejected(){
  var a=dispatch("a");var b=dispatch("b");var own=authentication.authenticate(a.instanceKey,installed.get(a.instanceKey));
  assertThatThrownBy(()->authentication.authenticate(b.instanceKey,installed.get(a.instanceKey))).isInstanceOf(com.eap.platform.config.UnauthorizedException.class);
  for(String key:Arrays.asList(null,b.taskKey,"unknown"))assertThatThrownBy(()->authentication.validateTaskAttribution(own,key,null)).isInstanceOf(GatewayException.class);
  assertThatThrownBy(()->authentication.validateTaskAttribution(own,a.taskKey,"changed-intent")).isInstanceOf(GatewayException.class);
  // a may already have been polled by b's scheduling iteration; use b's still-running access for the allowed case.
  var ownB=authentication.authenticate(b.instanceKey,installed.get(b.instanceKey));authentication.validateTaskAttribution(ownB,b.taskKey,"task:"+b.taskKey);
  db.update("update ax_tasks set status='SUCCEEDED' where task_key=?",b.taskKey);assertThatThrownBy(()->authentication.validateTaskAttribution(ownB,b.taskKey,null)).isInstanceOf(GatewayException.class);
 }
 @Test void originalSubmissionFreezesImageRequestPublicKeyAndDeadlineEvenWhenDefaultsChange(){
  var t=submit("frozen");ax.setRunnerImage("localhost:5001/task@sha256:"+"c".repeat(64));props.setGatewayUrl("http://changed.invalid");props.setGatewayHost("changed.invalid");config.setDeliveryPublicKey("changed-public-config");ax.setEnabled(false);
  var replay=service.submit(new AxService.Submit(agent,"frozen","public synthetic objective","ECHO"),"operator");var after=task(t.taskKey);assertThat(replay.taskKey()).isEqualTo(t.taskKey);assertThat(after.runnerImage).isEqualTo(t.runnerImage);assertThat(after.deliveryRequest).isEqualTo(t.deliveryRequest);assertThat(after.identityPublicKey).isEqualTo(t.identityPublicKey);assertThat(after.identityUntil).isEqualTo(t.identityUntil);assertThat(after.identityGatewayUrl).isEqualTo(t.identityGatewayUrl);assertThat(after.identityGatewayHost).isEqualTo(t.identityGatewayHost);
  assertThatThrownBy(()->service.submit(new AxService.Submit(agent,"frozen","changed","ECHO"),"operator")).isInstanceOf(ConflictException.class);assertThat(posts).hasValue(0);
 }
 @Test void missingReceiptAndChangedUidNeverResumeOrDispatch(){
  var t=submit("missing");service.processNext();doReturn(new AxClient.State("Running",true,null,null,true)).when(client).ensure(anyString(),anyString(),anyMap());service.processNext();assertThat(posts).hasValue(0);verify(client,never()).resume(anyString());
  doReturn(state(t,true)).when(client).ensure(anyString(),anyString(),anyMap());doReturn(new SubstrateClient.ActorInventory("eap-ax",t.taskKey,UUID.randomUUID().toString(),template(t),"RUNNING")).when(nativeClient).inspect(any());service.processNext();assertThat(posts).hasValue(0);verify(nativeClient,never()).resume(any());assertThat(service.get(t.taskKey).actorUid()).isEqualTo(uid(t));
 }
 @Test void deliveryLostReplyKeepsOriginalOperationAndDefersDispatchUntilConfirmed(){
  var t=submit("lost");service.processNext();loseInstall.set(true);service.processNext();assertThat(service.get(t.taskKey).status()).isEqualTo("PROVISIONING");assertThat(authentication.status(t.instanceKey).deliveryOperation()).isEqualTo(t.deliveryRequest);assertThat(posts).hasValue(0);service.processNext();assertThat(posts).hasValue(1);assertThat(authentication.status(t.instanceKey).connection()).isEqualTo("READY");
 }
 @Test void revokedTaskCannotBeReapprovedByOriginalOrHumanRequest(){
  var t=dispatch("revoke");String old=installed.get(t.instanceKey);authentication.revoke(t.instanceKey,"operator");assertThat(task(t.taskKey).identityRevoked).isTrue();
  assertThatThrownBy(()->identities.connect(t.taskKey,"operator")).isInstanceOf(ConflictException.class);assertThatThrownBy(()->delivery.connect(t.instanceKey,UUID.randomUUID().toString(),"human")).isInstanceOf(BusinessException.class);assertThatThrownBy(()->authentication.authenticate(t.instanceKey,old)).isInstanceOf(com.eap.platform.config.UnauthorizedException.class);service.processNext();assertThat(posts).hasValue(1);
 }
 @Test void revokeDuringEncryptedInstallWinsAndPreventsFirstDispatch(){
  var t=submit("race");service.processNext();revokeOnInstall.set(true);service.processNext();assertThat(posts).hasValue(0);assertThat(authentication.status(t.instanceKey).connection()).isEqualTo("REVOKED");assertThat(task(t.taskKey).identityRevoked).isTrue();
 }
 @Test void shortLeaseRenewalAndMemoryReinstallKeepEpochTaskUidAndBudgetIntents(){
  var t=dispatch("lease");String old=installed.get(t.instanceKey);String epoch=authentication.authenticate(t.instanceKey,old).generation();var until=task(t.taskKey).identityUntil;
  installed.remove(t.instanceKey);assertThat(identities.token(t.taskKey)).isEqualTo(old);assertThat(installed.get(t.instanceKey)).isEqualTo(old);
  db.update("update runtime_connections set expires_at=? where instance_key=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),t.instanceKey);assertThatThrownBy(()->authentication.authenticate(t.instanceKey,old)).isInstanceOf(com.eap.platform.config.UnauthorizedException.class);
  String fresh=identities.token(t.taskKey);assertThat(fresh).isNotEqualTo(old);assertThat(authentication.authenticate(t.instanceKey,fresh).generation()).isEqualTo(epoch);assertThat(task(t.taskKey).identityUntil).isEqualTo(until);assertThat(task(t.taskKey).actorUid).isEqualTo(uid(t));assertThat(posts).hasValue(1);
 }
 @Test void expiredTaskAuthorityStopsDeliveryAndNewModelsWithoutResettingIntent(){
  var t=dispatch("expired");db.update("update ax_tasks set identity_until=? where task_key=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),t.taskKey);assertThatThrownBy(()->identities.token(t.taskKey)).isInstanceOf(ConflictException.class);assertThatThrownBy(()->authentication.authenticate(t.instanceKey,installed.get(t.instanceKey))).isInstanceOf(com.eap.platform.config.UnauthorizedException.class);assertThat(posts).hasValue(1);
 }
 @Test void unknownReceiptOnlyPollsOriginalAndOldUnknownStatusIsCanonicalized(){
  var t=dispatch("unknown");receipts.put(t.instanceKey,json.createObjectNode().put("taskKey",t.taskKey).put("status","UNKNOWN").toString().getBytes(StandardCharsets.UTF_8));service.processNext();assertThat(service.get(t.taskKey).status()).isEqualTo("EXECUTION_UNKNOWN");service.processNext();assertThat(posts).hasValue(1);assertThat(tasks.count()).isEqualTo(1);
 }
 @Test void realSharedBudgetUsesOriginalTaskAndPreventsChangedInvocationReplay(){
  var t=dispatch("budget");var servlet=new org.springframework.mock.web.MockHttpServletRequest();servlet.addHeader("X-EAP-Task-Key",t.taskKey);
  org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(servlet));
  try{
   var access=authentication.authenticate(t.instanceKey,installed.get(t.instanceKey));authentication.validateTaskAttribution(access,t.taskKey,null);
   var body=json.createObjectNode().put("model","synthetic").put("max_tokens",16).set("messages",json.createArrayNode().add(json.createObjectNode().put("role","user").put("content","public synthetic objective")));
   var ticket=budgets.admitModel(body,agent,access.endpointKey(),null,"b123456789abcdefb123456789abcdef","unused-new-fallback");
   assertThat(db.queryForObject("select task_key from budget_reservations where id=?",String.class,ticket.id())).isEqualTo(t.taskKey);
   assertThat(db.queryForObject("select count(*) from budget_accounts where scope_type='TASK' and scope_key=?",Long.class,t.taskKey)).isPositive();
   assertThatThrownBy(()->budgets.admitModel(body,agent,access.endpointKey(),null,"c123456789abcdefc123456789abcdef","changed-fallback")).isInstanceOf(GatewayException.class);
   assertThat(db.queryForObject("select count(*) from budget_reservations where task_key=?",Long.class,t.taskKey)).isEqualTo(1);
  }finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
 }
 @Test void httpAdmissionRejectsMissingOrForeignTaskAndLegacyGatewayBeforeModelSideEffect()throws Exception{
  var t=dispatch("http-gate");String body="{\"model\":\"synthetic\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"public sample\"}]}";
  for(String taskKey:Arrays.asList(null,"foreign-task")){
   var request=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/runtime-access/v1/chat/completions").contentType("application/json").content(body).header("X-EAP-Runtime-Key",t.instanceKey).header("X-EAP-Runtime-Token",installed.get(t.instanceKey));
   if(taskKey!=null)request.header("X-EAP-Task-Key",taskKey);
   mvc.perform(request).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-EAP-Admission-Outcome","REJECTED"));
  }
  gatewayConfig.setIngestionToken(UUID.randomUUID().toString());mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/gateway/v1/chat/completions").contentType("application/json").content(body).header("X-EAP-Gateway-Token",gatewayConfig.getIngestionToken()).header("X-EAP-Agent-Id",agent).header("X-EAP-Task-Key",t.taskKey)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/runtime-access/v1/tools/scoped/execute").contentType("application/json").content("{}").header("X-EAP-Runtime-Key",t.instanceKey).header("X-EAP-Runtime-Token",installed.get(t.instanceKey))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
  verifyNoInteractions(gateway);assertThat(posts).hasValue(1);
 }
 @Test void shortOriginalApprovalRemainsUsableWithNonUtcJdbcSession(){
  var t=submit("utc-short");t.identityUntil=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS).plusSeconds(3600);tasks.saveAndFlush(t);service.processNext();service.processNext();
  assertThat(posts).hasValue(1);assertThat(service.get(t.taskKey).identityStatus()).isEqualTo("READY");
  var actual=task(t.taskKey);assertThat(authentication.status(actual.instanceKey).identityExpiresAt()).isEqualTo(actual.identityUntil);
  assertThat(authentication.authenticate(actual.instanceKey,installed.get(actual.instanceKey)).agentId()).isEqualTo(agent);
  assertThat(actual.identityUntil).isEqualTo(t.identityUntil);
 }
 @Test void ordinaryRegistrationCannotForgeTaskProtocolOrUid(){
  assertThatThrownBy(()->registry.registerRuntime(agent,new AgentRegistryDtos.RuntimeInstanceRequest("eap-ax/forged","substrate://eap-ax/forged","SUBSTRATE_TASK",true))).isInstanceOf(BusinessException.class);verifyNoInteractions(client,nativeClient);assertThat(tasks.count()).isZero();
 }
}
