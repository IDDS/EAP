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
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
 public AgentExperimentClient(SubstrateProperties substrate,SubstrateClient client,WorkflowExecutionProperties workflow,ObjectMapper mapper,com.eap.platform.registry.AgentRuntimeInstanceRepository runtimes,com.eap.platform.registry.RuntimeConnectionService connections){this.runtimes=runtimes;this.connections=connections;this.substrate=substrate;this.client=client;this.workflow=workflow;this.mapper=mapper;}
 public record RuntimeProfile(JsonNode profile,String actorUid,String template) {}
 public record Receipt(String status,String reasonCode,JsonNode body) {}
 public RuntimeProfile profile(String endpoint) {
  if(!substrate.isEnabled()||!workflow.ready())throw new IllegalArgumentException("实验执行未配置");
  var actor=SubstrateActorRef.parse(endpoint);var runtime=runtimes.findByInstanceKey(actor.routingValue()).orElse(null);
  if(runtime!=null&&runtime.hasBoundIdentity()) { if(!connections.executable(runtime))throw new IllegalArgumentException("专属实例接入不可用");connections.checkIdentity(runtime); }
  client.resume(actor);var info=client.inspect(actor);
  try {
   var response=request(actor,"/eap/experiments/profile",null,12);
   if(response.statusCode()!=200)throw new IllegalArgumentException("实例不支持独立上下文实验，请使用已验证的实验运行程序");
   var p=mapper.readTree(response.body());
   if(!"EAP_STATELESS_PROMPT_V1".equals(p.path("protocol").asText())||!"EMPTY".equals(p.path("context").asText())||!"DISABLED".equals(p.path("tools").asText())||!p.path("profileDigest").asText().matches("[a-f0-9]{64}")||p.path("systemPrompt").asText().isBlank()||p.path("model").asText().isBlank()||info.uid()==null||info.uid().isBlank())throw new IllegalArgumentException("实例实验协议证据不完整");
   return new RuntimeProfile(p,info.uid(),info.template());
  }catch(IllegalArgumentException e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new IllegalArgumentException("实验配置读取未确认");}
 }
 public Receipt execute(AgentExperimentService.Dispatch d) {
  try {var p=profile(d.endpoint());
   if(!p.actorUid().equals(d.actorUid())||!p.template().equals(d.template())||!p.profile().path("profileDigest").asText().equals(d.profileDigest()))return new Receipt("FAILED","PROFILE_OR_IDENTITY_CHANGED",null);
  }catch(RuntimeException e){return new Receipt("FAILED","RUNTIME_NOT_READY",null);}
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
 private HttpResponse<String> request(SubstrateActorRef actor,String path,Map<String,Object> body,int timeout)throws Exception {
  var runtime=runtimes.findByInstanceKey(actor.routingValue()).orElse(null);
  String token=runtime!=null&&runtime.hasBoundIdentity()?connections.dispatchToken(runtime):workflow.getToken();
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
