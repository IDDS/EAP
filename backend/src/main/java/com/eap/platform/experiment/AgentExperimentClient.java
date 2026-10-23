package com.eap.platform.experiment;
import com.eap.platform.substrate.*;
import com.eap.platform.workflow.WorkflowExecutionProperties;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
@Component
public class AgentExperimentClient {
 private final SubstrateProperties substrate;
 private final SubstrateClient client;
 private final WorkflowExecutionProperties workflow;
 private final ObjectMapper mapper;
 private final com.eap.platform.registry.AgentRuntimeInstanceRepository runtimes;
 private final com.eap.platform.registry.RuntimeConnectionService connections;
 private final com.eap.platform.registry.RuntimeWorkService work;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
 public AgentExperimentClient(SubstrateProperties substrate,SubstrateClient client,WorkflowExecutionProperties workflow,ObjectMapper mapper,com.eap.platform.registry.AgentRuntimeInstanceRepository runtimes,com.eap.platform.registry.RuntimeConnectionService connections,com.eap.platform.registry.RuntimeWorkService work){this.work=work;this.runtimes=runtimes;this.connections=connections;this.substrate=substrate;this.client=client;this.workflow=workflow;this.mapper=mapper;}
 public record RuntimeProfile(JsonNode profile,String actorUid,String template) {}
 public record Receipt(String status,String reasonCode,JsonNode body) {}
 private static final java.util.Set<String> CONTROL_CODES=java.util.Set.of("CANCELLED","UNKNOWN","INVALID_ARGUMENT","DEADLINE_EXCEEDED","NOT_FOUND","ALREADY_EXISTS","PERMISSION_DENIED","RESOURCE_EXHAUSTED","FAILED_PRECONDITION","ABORTED","OUT_OF_RANGE","UNIMPLEMENTED","INTERNAL","UNAVAILABLE","DATA_LOSS","UNAUTHENTICATED");
 // Persist only a bounded stage/code. Never retain exception text, response bodies or credentials.
 private static final class ReadinessFailure extends IllegalArgumentException {
  final String reasonCode;
  ReadinessFailure(String reasonCode){super("实验运行准备未确认，请按诊断码核对原实例");this.reasonCode=reasonCode;}
 }
 private <T> T control(String stage,java.util.function.Supplier<T> action){
  try{return action.get();}
  catch(SubstrateClient.SubstrateException e){throw new ReadinessFailure("RUNTIME_"+stage+"_"+(e.code()!=null&&CONTROL_CODES.contains(e.code())?e.code():"UNCONFIRMED"));}
  catch(com.eap.platform.common.ConflictException e){throw new ReadinessFailure("RUNTIME_"+stage+"_CHANGED");}
  catch(RuntimeException e){throw new ReadinessFailure("RUNTIME_"+stage+"_UNCONFIRMED");}
 }
 public RuntimeProfile profile(String endpoint) {
  return work.perform(endpoint, "PROFILE", () -> profile(endpoint,false), result -> true);
 }
 /** Read-only preparation: never resumes an Actor or delivers/renews credentials. */
 public RuntimeProfile inspectProfile(String endpoint) {
  return profile(endpoint,true);
 }
 private RuntimeProfile profile(String endpoint,boolean inspection) {
  if(!substrate.isEnabled()||!workflow.ready())throw new ReadinessFailure("RUNTIME_PROFILE_DISABLED");
  var actor=SubstrateActorRef.parse(endpoint);var runtime=runtimes.findByInstanceKey(actor.routingValue()).orElse(null);
  if(inspection&&(runtime==null||!runtime.hasBoundIdentity()))throw new ReadinessFailure("RUNTIME_IDENTITY_UNCONFIRMED");
  if(runtime!=null&&runtime.hasBoundIdentity()) { if(!connections.executable(runtime))throw new ReadinessFailure("RUNTIME_CONNECTION_UNAVAILABLE");control("IDENTITY",()->{connections.checkIdentity(runtime);return true;}); }
  if(!inspection)control("RESUME",()->client.resume(actor));var info=control("INSPECT",()->client.inspect(actor));
  if(inspection&&!"RUNNING".equals(info.state()))throw new ReadinessFailure("RUNTIME_INSPECT_NOT_RUNNING");
  try {
   var response=request(actor,"/eap/experiments/profile",null,12,inspection);
   if(response.statusCode()!=200)throw new ReadinessFailure("RUNTIME_PROFILE_HTTP_"+response.statusCode());
   JsonNode p;
   try{p=mapper.readTree(response.body());}catch(Exception e){throw new ReadinessFailure("RUNTIME_PROFILE_INVALID");}
   if(p==null||!"EAP_STATELESS_PROMPT_V1".equals(p.path("protocol").asText())||!"EMPTY".equals(p.path("context").asText())||!"DISABLED".equals(p.path("tools").asText())||!p.path("profileDigest").asText().matches("[a-f0-9]{64}")||p.path("systemPrompt").asText().isBlank()||p.path("model").asText().isBlank()||info.uid()==null||info.uid().isBlank())throw new ReadinessFailure("RUNTIME_PROFILE_INVALID");
   if(inspection)control("IDENTITY",()->{connections.checkIdentity(runtime);return true;});
   return new RuntimeProfile(p,info.uid(),info.template());
  }catch(ReadinessFailure e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new ReadinessFailure("RUNTIME_PROFILE_UNCONFIRMED");}
 }
 public Receipt execute(AgentExperimentService.Dispatch d) {
  try { return work.perform(d.endpoint(), "EXPERIMENT", () -> executeOnce(d), receipt -> !"UNKNOWN".equals(receipt.status())); }
  catch(com.eap.platform.common.BusinessException e) { return new Receipt("FAILED","RUNTIME_RECLAIM_UNCONFIRMED",null); }
 }
 private Receipt executeOnce(AgentExperimentService.Dispatch d) {
  try {var p=profile(d.endpoint());
   if(!p.actorUid().equals(d.actorUid())||!p.template().equals(d.template())||!p.profile().path("profileDigest").asText().equals(d.profileDigest()))return new Receipt("FAILED","PROFILE_OR_IDENTITY_CHANGED",null);
  }catch(ReadinessFailure e){return new Receipt("FAILED",e.reasonCode,null);}
  catch(RuntimeException e){return new Receipt("FAILED","RUNTIME_NOT_READY",null);}
  try {
   var response=request(SubstrateActorRef.parse(d.endpoint()),"/eap/experiments/run",d.body(),130);
   if(response.statusCode()!=200)return new Receipt("UNKNOWN","RECEIPT_HTTP_"+response.statusCode(),null);
   var body=mapper.readTree(response.body());
   if(!d.body().get("traceId").equals(body.path("traceId").asText()))return new Receipt("UNKNOWN","TRACE_MISMATCH",null);
   if("FAILED".equals(body.path("status").asText())&&java.util.Set.of("BUDGET_EXHAUSTED","BUDGET_REQUEST_REJECTED").contains(body.path("reasonCode").asText()))return new Receipt("FAILED",body.path("reasonCode").asText(),null);
   if(!"SUCCEEDED".equals(body.path("status").asText()))return new Receipt("UNKNOWN","RUNNER_RESULT_UNKNOWN",null);
   if(!body.path("output").isTextual()||body.path("output").asText().length()>16000||!java.util.Set.of("stop","length").contains(body.path("finishReason").asText()))return new Receipt("UNKNOWN","INVALID_RECEIPT",null);
   return new Receipt("SUCCEEDED",null,body);
  }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return new Receipt("UNKNOWN","RECEIPT_UNKNOWN",null);}
 }
 /** Single send of an already persisted team intent. Never resumes, renews or retries. */
 public record StatelessDispatch(String endpoint,String actorUid,String template,String profileDigest,Map<String,Object> body,java.time.Instant deadline) {}
 public Receipt executeStateless(StatelessDispatch d) {
  try { return work.perform(d.endpoint(), "TEAM_EXPERIMENT", () -> executeStatelessOnce(d), receipt -> !"UNKNOWN".equals(receipt.status())); }
  catch(com.eap.platform.common.BusinessException e) { return new Receipt("FAILED","RUNTIME_RECLAIM_UNCONFIRMED",null); }
 }
 private Receipt executeStatelessOnce(StatelessDispatch d) {
  try {
   var profile=inspectProfile(d.endpoint());
   if(!d.actorUid().equals(profile.actorUid())||!d.template().equals(profile.template())||!d.profileDigest().equals(profile.profile().path("profileDigest").asText()))return new Receipt("FAILED","PROFILE_OR_IDENTITY_CHANGED",null);
   long remaining=java.time.Duration.between(java.time.Instant.now(),d.deadline()).toSeconds();
   if(remaining<1)return new Receipt("FAILED","APPROVAL_EXPIRED",null);
   if(mapper.writeValueAsBytes(d.body()).length>16384)return new Receipt("FAILED","RUNNER_BODY_LIMIT",null);
   var response=request(SubstrateActorRef.parse(d.endpoint()),"/eap/experiments/run",d.body(),(int)Math.min(130,remaining),true);
   if(response.statusCode()!=200)return new Receipt("UNKNOWN","RECEIPT_HTTP_UNKNOWN",null);
   var body=mapper.readTree(response.body());
   if(!d.body().get("traceId").equals(body.path("traceId").asText())||!"SUCCEEDED".equals(body.path("status").asText())
       ||!body.path("output").isTextual()||body.path("output").asText().length()>16000||!java.util.Set.of("stop","length").contains(body.path("finishReason").asText()))return new Receipt("UNKNOWN","INVALID_RECEIPT",null);
   return new Receipt("SUCCEEDED",null,body);
  }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return new Receipt("UNKNOWN","RECEIPT_UNKNOWN",null);}
 }
 private HttpResponse<String> request(SubstrateActorRef actor,String path,Map<String,Object> body,int timeout)throws Exception {
  return request(actor,path,body,timeout,false);
 }
 private HttpResponse<String> request(SubstrateActorRef actor,String path,Map<String,Object> body,int timeout,boolean inspection)throws Exception {
  var runtime=runtimes.findByInstanceKey(actor.routingValue()).orElse(null);
  if(inspection&&(runtime==null||!runtime.hasBoundIdentity()))throw new ReadinessFailure("RUNTIME_IDENTITY_UNCONFIRMED");
  String token=runtime!=null&&runtime.hasBoundIdentity()?(inspection?connections.inspectionToken(runtime):connections.dispatchToken(runtime)):workflow.getToken();
  var builder=HttpRequest.newBuilder(URI.create(substrate.getRouterUrl().replaceAll("/+$","")+path)).timeout(Duration.ofSeconds(timeout)).header("Authorization","Bearer "+token).header(SubstrateActorRef.ROUTING_HEADER,actor.routingValue());
  if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
  // Keep the deadline active while reading: a streaming handler alone does not bound a stalled body.
  var pending=http.sendAsync(builder.build(),boundedBody());
  try{return pending.get(timeout,java.util.concurrent.TimeUnit.SECONDS);}
  catch(Exception e){pending.cancel(true);throw e;}
 }
 private HttpResponse.BodyHandler<String> boundedBody(){return info->new java.net.http.HttpResponse.BodySubscriber<String>(){
  final java.util.concurrent.CompletableFuture<String> result=new java.util.concurrent.CompletableFuture<>();final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.util.concurrent.Flow.Subscription subscription;
  public java.util.concurrent.CompletionStage<String> getBody(){return result;}
  public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;s.request(1);}
  public void onNext(java.util.List<java.nio.ByteBuffer> parts){try{for(var p:parts){if(bytes.size()+p.remaining()>65536)throw new IllegalArgumentException("response limit");byte[] b=new byte[p.remaining()];p.get(b);bytes.write(b);}subscription.request(1);}catch(Exception e){subscription.cancel();result.completeExceptionally(e);}}
  public void onError(Throwable t){result.completeExceptionally(t);}public void onComplete(){result.complete(bytes.toString(java.nio.charset.StandardCharsets.UTF_8));}
 };}
}
