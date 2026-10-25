package com.eap.platform.ax;
import com.eap.platform.agent.*;
import com.eap.platform.audit.*;
import com.eap.platform.common.*;
import com.eap.platform.substrate.*;
import com.eap.platform.workflow.WorkflowExecutionProperties;
import com.eap.platform.observability.ObservationProperties;
import com.eap.platform.safety.*;
import com.eap.platform.registry.TaskRuntimeIdentityService;
import com.eap.platform.registry.RuntimeConnectionProperties;
import com.eap.platform.registry.RuntimeScopedTelemetry;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.validation.constraints.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Durable EAP business state is deliberately separate from AX sandbox phase. */
@Service
public class AxService {
 public record Submit(@NotNull Long agentId, @NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String requestKey,
   @NotBlank @Size(max=8000) String objective, @NotBlank @Pattern(regexp="LLM|ECHO|FAIL") String mode) {}
 public record View(String taskKey, Long agentId, String status, String sandboxPhase, String reason,
   String actor, String requestedBy, Instant createdAt, Instant updatedAt, boolean suspended, Long workflowId, String workflowRunKey, String workflowNodeKey, String identityMode, String identityStatus, String instanceKey, String actorUid, Instant identityExpiresAt) {}
 @org.springframework.beans.factory.annotation.Autowired private com.eap.platform.observability.ObservationCoverageService coverage;
 private final AxTaskRepository tasks; private final AgentRepository agents; private final AxProperties properties;
 private final SubstrateClient substrateClient; private final AxClient client; private final SubstrateProperties substrate; private final WorkflowExecutionProperties workflow;
 private final ObservationProperties observation; private final ObjectMapper mapper; private final AuditLogRepository audits;
 private final TaskRuntimeIdentityService identities; private final RuntimeConnectionProperties identityConfig;
 private final GenseeSafetyService safety; private final TransactionTemplate tx;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
 public AxService(AxTaskRepository tasks, AgentRepository agents, AxProperties properties, AxClient client, SubstrateClient substrateClient,
   SubstrateProperties substrate, WorkflowExecutionProperties workflow, ObservationProperties observation,
   ObjectMapper mapper, AuditLogRepository audits, GenseeSafetyService safety, PlatformTransactionManager manager, TaskRuntimeIdentityService identities, RuntimeConnectionProperties identityConfig){
  this.substrateClient=substrateClient;this.tasks=tasks;this.agents=agents;this.properties=properties;this.client=client;this.substrate=substrate;
  this.identities=identities;this.identityConfig=identityConfig;this.workflow=workflow;this.observation=observation;this.mapper=mapper;this.audits=audits;this.safety=safety;tx=new TransactionTemplate(manager);
 }
 public Map<String,Object> capabilities(){return Map.of("enabled",ready(),"instanceIdentity",properties.isInstanceIdentityEnabled(),"atespace",properties.getAtespace(),
   "modes",List.of("LLM","ECHO","FAIL"),"protocol","EAP_AX_TASK_V1","upstreamRevision","d0bc38bcf90bb2ad9c012ff1be9d68ff05347ba9");}
 public boolean ready(){return properties.ready() && (properties.isInstanceIdentityEnabled()
  ? properties.legacyFrozen() && identityConfig.deliveryReady() && substrate.isEnabled() && substrate.inventoryScopes().contains(properties.getAtespace())
  : workflow.ready());}
 public void validateWorkflowAgent(Long id){
  if(!ready())throw new BusinessException("AX 执行未启用或缺少认证配置");
  if(id==null || agents.findById(id).filter(a->a.getLifecycleStatus()==AgentLifecycleStatus.ACTIVE).isEmpty())
   throw new BusinessException("AX 节点需要启用中的 Agent");
 }
 public View submitWorkflow(Long workflowId,String runKey,String nodeKey,Long agentId,String objective,
   Map<String,Object> context,String mode,String requester,Instant deadline){
  String input=objective+(context.isEmpty()?"":"\n执行上下文（含上游真实结果）：\n"+json(context));
  if(input.length()>8000 || input.getBytes(StandardCharsets.UTF_8).length>24000)
   throw new BusinessException("AX 目标与上下文超过 8000 字符或 24KB，未派发此节点");
  return submit(new Submit(agentId,hash(runKey+":"+nodeKey),input,mode),requester,workflowId,runKey,nodeKey,deadline);
 }
 public View submit(Submit request,String requester){return submit(request,requester,null,null,null,null);}
 private View submit(Submit request,String requester,Long workflowId,String runKey,String nodeKey,Instant deadline){
  String owner=requester==null?"console":requester;
  String key=hash((runKey==null?"":"workflow:")+request.agentId()+":"+owner+":"+request.requestKey());
  String fingerprint=hash(json(Map.of("objective",request.objective(),"mode",request.mode())));
  return tx.execute(s->{
   var agent=agents.lockForAx(request.agentId()).orElseThrow(()->new BusinessException("智能体不存在"));
   var previous=tasks.findBySubmissionKey(key);
   if(previous.isPresent()){
    if(!previous.get().fingerprint.equals(fingerprint))throw new ConflictException("同一 requestKey 的参数已改变");
    return view(previous.get());
   }
   if(!ready())throw new BusinessException("任务执行未启用或专属身份配送配置不完整");
   if((runKey!=null || properties.isInstanceIdentityEnabled()) && agent.getLifecycleStatus()!=AgentLifecycleStatus.ACTIVE)throw new BusinessException("AX 节点 Agent 已不再启用");
   if(agent.getLifecycleStatus()==AgentLifecycleStatus.RETIRED)throw new BusinessException("已退役智能体不能执行");
   AxTask t=new AxTask(); t.taskKey="ax-"+UUID.randomUUID();t.agentId=request.agentId();t.submissionKey=key;
   t.fingerprint=fingerprint;t.requestedBy=owner.substring(0,Math.min(128,owner.length()));
   t.status="QUEUED";t.phase="Pending";t.reason="已持久化，等待创建隔离任务";
   String rawTrace=UUID.randomUUID().toString().replace("-", "");
   t.payloadJson=json(Map.of("taskKey",t.taskKey,"objective",request.objective(),"mode",request.mode(),"traceId",rawTrace));
   t.createdAt=t.updatedAt=Instant.now();t.deadlineAt=deadline==null?t.createdAt.plusSeconds(1800):deadline;
   t.identityScope=properties.getAtespace();
   t.runnerImage=properties.isInstanceIdentityEnabled()?properties.getRunnerImage():properties.getLegacyRunnerImage();
   if(properties.isInstanceIdentityEnabled()){
    t.identityGatewayUrl=substrate.getGatewayUrl();t.identityGatewayHost=substrate.getGatewayHost();
    var gatewayUri=URI.create(t.identityGatewayUrl);if(!Set.of("http","https").contains(gatewayUri.getScheme())||gatewayUri.getUserInfo()!=null||gatewayUri.getHost()==null||!gatewayUri.getHost().equals(t.identityGatewayHost))throw new BusinessException("任务治理网关地址与出站域名不匹配");
    t.identityMode="PER_INSTANCE_V1";t.identityPublicKey=identityConfig.getDeliveryPublicKey();
    t.deliveryRequest=UUID.randomUUID().toString();t.instanceKey=t.identityScope+"/"+t.taskKey;t.identityUntil=t.createdAt.plusSeconds(86400);
   }
   if(!t.deadlineAt.isAfter(t.createdAt))throw new BusinessException("工作流期限已到，未创建 AX 任务");
   t.workflowId=workflowId;t.workflowRunKey=runKey;t.workflowNodeKey=nodeKey;
   safety.createSession(new SafetyDtos.SessionRequest(t.taskKey,t.agentId,"AX 任务执行"));
   var decision=safety.evaluate(new SafetyDtos.SafetyEventRequest(t.taskKey,t.agentId,null,SafetyEventType.AGENT_MESSAGE,
     "invoke_agent","ax://"+scope(t)+"/"+t.taskKey,"AX 执行预检",t.payloadJson)).decision().decision();
   if(decision!=SafetyDecision.ALLOW){t.status=decision==SafetyDecision.DENY?"BLOCKED":"WAITING_APPROVAL";t.reason="治理策略未允许派发";t.suspended=true;}
   tasks.saveAndFlush(t);coverage.enrollAx(t.id,rawTrace,request.mode());audit(t,"SUBMIT_AX_TASK",t.status);return view(t);
  });
 }
 public List<View> list(){return tasks.findTop100ByOrderByIdDesc().stream().map(this::view).toList();}
 public View get(String key){return view(require(key));}
 public JsonNode content(String key){var t=require(key);return mapper.createObjectNode().setAll(Map.of("input",tree(t.payloadJson),"result",t.resultJson==null?mapper.nullNode():tree(t.resultJson)));}
 private AxTask require(String key){return tasks.findByTaskKey(key).orElseThrow(()->new BusinessException("AX 任务不存在"));}
 private View view(AxTask t){return new View(t.taskKey,t.agentId,t.status,t.phase,t.reason,scope(t)+"/"+t.taskKey,t.requestedBy,t.createdAt,t.updatedAt,t.suspended,t.workflowId,t.workflowRunKey,t.workflowNodeKey,t.identityMode,t.scoped()?identities.summary(t.taskKey):"LEGACY",t.instanceKey,t.actorUid,t.identityUntil);}
 public void processNext(){
  if(!properties.ready())return;
  for(var candidate:tasks.findTop20BySuspendedFalseOrderByUpdatedAtAsc()){
   AxTask t=tx.execute(s->{var row=tasks.lockById(candidate.id).orElseThrow();
    if(row.leaseUntil!=null&&row.leaseUntil.isAfter(Instant.now()))return null;
    row.leaseUntil=Instant.now().plusSeconds(60);return tasks.save(row);});
   if(t==null)continue;
   try{advance(t);}catch(Exception e){
    // Never expose upstream errors: they may include environment or task content.
    change(t.id,row->{row.reason="AX 或 Runner 暂时不可达；保留原任务标识，未重新执行";
      if(row.deadlineAt.isBefore(Instant.now())&&!terminal(row.status)){row.status="EXECUTION_UNKNOWN";row.reason="执行期限已到，结果未知；需核对原任务";}});
   }finally{change(t.id,row->row.leaseUntil=null);}
  }
 }
 private void advance(AxTask t)throws Exception{
  if(t.scoped() && !terminal(t.status))identities.approve(t.taskKey,false);
  if(terminal(t.status)){
   if(t.resultJson==null){
    if("EXECUTION_UNKNOWN".equals(t.status)){
      var response=call(t,"GET","/eap/result",null);
      if(response.statusCode()==200)acceptReceipt(t.id,response.body());
    }
    return;
   }
   if(t.scoped())identities.check(t.taskKey);
   if(t.autoSuspend)client.suspend(t.taskKey);
   var state=client.get(t.taskKey);
   boolean physicallySuspended="SUSPENDED".equals(substrateClient.get(new SubstrateActorRef(scope(t),t.taskKey)).state());
   // AX ACK may precede reconciliation. Only expose the phase actually reported.
   change(t.id,r->{r.phase=state.phase();r.suspended=physicallySuspended;});return;
  }
  if(t.deadlineAt.isBefore(Instant.now())){change(t.id,r->{r.status="EXECUTION_UNKNOWN";r.reason="执行期限已到，结果未知；未自动重试";});return;}
  if("QUEUED".equals(t.status)){
   change(t.id,r->{r.status="PROVISIONING";r.reason="AX 正在准备沙箱";});return;
  }
  if("PROVISIONING".equals(t.status)){
   Map<String,String> env=new LinkedHashMap<>();
   env.put("EAP_AX_TASK_KEY",t.taskKey);env.put("EAP_AGENT_ID",t.agentId.toString());
   env.put("EAP_GATEWAY_URL",t.scoped()?t.identityGatewayUrl:substrate.getGatewayUrl());
   if(t.scoped()){
    env.put("EAP_IDENTITY_MODE","PER_INSTANCE_V1");env.put("EAP_IDENTITY_DIR","/run/eap-identity");env.put("EAP_IDENTITY_PUBLIC_KEY",t.identityPublicKey);
   }else{
    env.put("EAP_GATEWAY_TOKEN",substrate.getGatewayToken());env.put("EAP_TASK_TOKEN",workflow.getToken());env.put("EAP_OBSERVABILITY_TOKEN",observation.getIngestionToken());
   }
   if(!scope(t).equals(properties.getAtespace()))throw new ConflictException("任务原运行空间与当前入口不一致，未重新创建");
   var state=t.scoped()?client.ensure(t.taskKey,t.runnerImage,env):client.ensure(t.taskKey,legacyImage(t),env);
   if(t.scoped()){
    if(!state.instanceIdentity())throw new ConflictException("任务入口未确认专属实例身份协议");
    if(state.actorUid()!=null){
     change(t.id,r->{if(r.actorUid!=null&&(!r.actorUid.equals(state.actorUid())||!Objects.equals(r.actorTemplate,state.actorTemplate())))throw new ConflictException("原任务创建 UID 或模板已变化，未接管实例");r.actorUid=state.actorUid();r.actorTemplate=state.actorTemplate();});
     identities.check(t.taskKey);
    }else{
     change(t.id,r->{r.phase=state.phase();r.reason="等待原生创建回执确认实例 UID，未派发请求";});return;
    }
   }
   if(!state.ready()){
    if(Set.of("Suspended","Failed","Running").contains(state.phase()))client.resume(t.taskKey);
    change(t.id,r->{r.phase=state.phase();r.reason="等待 AX 沙箱与工作区就绪";});return;
   }
   substrateClient.setEgressHostnames(new SubstrateActorRef(scope(t),t.taskKey),List.of(t.scoped()?t.identityGatewayHost:substrate.getGatewayHost()));
   if(t.scoped())identities.connect(t.taskKey,t.requestedBy);
   // Commit dispatch intent before HTTP. Restart or lost response only polls receipts, never reposts.
   change(t.id,r->{r.status="RUNNING";r.phase=state.phase();r.reason="派发意图已持久化，等待匹配任务回执";coverage.axDispatch(r.id);});
   call(t,"POST","/eap/task",t.payloadJson);return;
  }
  if("RUNNING".equals(t.status)){
   var response=call(t,"GET","/eap/result",null);
   if(response.statusCode()!=200)return;
   acceptReceipt(t.id,response.body());
  }
 }
 void acceptReceipt(Long id,byte[] raw){
  if(raw.length>20000)return;
  JsonNode receipt;try{receipt=mapper.readTree(raw);}catch(Exception e){return;}
  if(receipt==null)return;
  change(id,r->{
   if(!r.taskKey.equals(receipt.path("taskKey").asText()))return;
   String status="UNKNOWN".equals(receipt.path("status").asText())?"EXECUTION_UNKNOWN":receipt.path("status").asText();
   if(!Set.of("SUCCEEDED","FAILED","EXECUTION_UNKNOWN").contains(status))return;
   if(!Set.of("RUNNING","EXECUTION_UNKNOWN").contains(r.status))return;
   if(r.scoped() && receipt.hasNonNull("traceId")){
    if(!(receipt instanceof com.fasterxml.jackson.databind.node.ObjectNode object)||!receipt.path("traceId").asText().matches("[a-fA-F0-9]{32}"))return;
    String rawTrace=receipt.path("traceId").asText();object.put("runtimeTraceId",rawTrace);object.put("traceId",RuntimeScopedTelemetry.trace(r.instanceKey,rawTrace));
   }
   if(receipt instanceof com.fasterxml.jackson.databind.node.ObjectNode object)object.put("status",status);
   r.status=status;r.resultJson=json(receipt);r.reason="SUCCEEDED".equals(status)?"Runner 已确认业务完成":"FAILED".equals(status)?"Runner 已确认执行失败":"Runner 检测到中断；未重复执行";
   coverage.axReceipt(r.id,receipt);audit(r,"AX_TASK_RECEIPT",status);
  });
 }
 private record Response(int statusCode,byte[] body) {}
 private Response call(AxTask t,String method,String path,String body)throws Exception{
  var request=HttpRequest.newBuilder(URI.create(substrate.getRouterUrl().replaceAll("/+$","")+path))
   .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+(t.scoped()?identities.token(t.taskKey):workflow.getToken()))
   .header(SubstrateActorRef.ROUTING_HEADER,scope(t)+"/"+t.taskKey)
   .header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
  var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
  try(var input=response.body()){return new Response(response.statusCode(),input.readNBytes(20001));}
 }
 public View control(String key,String action,String requester){
  AxTask t=require(key);
  if(!Set.of("SUCCEEDED","FAILED").contains(t.status))throw new ConflictException("仅允许对已确认终态的任务操作沙箱，避免中断在途副作用");
  if(!Set.of("suspend","resume").contains(action))throw new BusinessException("不支持的操作");
  if(t.scoped()){if("resume".equals(action))identities.approve(key,true);else identities.check(key);}
  if("suspend".equals(action))client.suspend(key);else client.resume(key);
  var state=client.get(key);change(t.id,r->{r.phase=state.phase();r.autoSuspend="suspend".equals(action);r.suspended=false;audit(r,"AX_SANDBOX_"+action.toUpperCase(Locale.ROOT),"REQUESTED",requester);});
  if(t.scoped() && "resume".equals(action))identities.token(key);
  return get(key);
 }
 private String scope(AxTask t){return t.identityScope==null?properties.getAtespace():t.identityScope;}
 private String legacyImage(AxTask t){return t.runnerImage==null?properties.getLegacyRunnerImage():t.runnerImage;}
 private void change(Long id,java.util.function.Consumer<AxTask> change){tx.executeWithoutResult(s->{var t=tasks.lockById(id).orElseThrow();change.accept(t);t.updatedAt=Instant.now();tasks.save(t);});}
 private static boolean terminal(String status){return Set.of("SUCCEEDED","FAILED","EXECUTION_UNKNOWN","BLOCKED","WAITING_APPROVAL").contains(status);}
 private void audit(AxTask t,String action,String result){audit(t,action,result,t.requestedBy);}
 private void audit(AxTask t,String action,String result,String actor){AuditLog a=new AuditLog();a.setActor(actor.substring(0,Math.min(128,actor.length())));a.setAction(action);a.setResourceType("AX_TASK");a.setResourceId(t.taskKey);a.setResult(result);a.setTraceId(t.taskKey);a.setCreatedAt(Instant.now());audits.save(a);}
 private String json(Object value){try{return mapper.writer(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);}catch(Exception e){throw new BusinessException("AX 请求序列化失败");}}
 private JsonNode tree(String value){try{return mapper.readTree(value);}catch(Exception e){throw new BusinessException("AX 内容不可读取");}}
 private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
