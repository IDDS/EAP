package com.eap.platform.experiment;
import com.eap.platform.agent.*;
import com.eap.platform.registry.*;
import com.eap.platform.deployment.*;
import com.eap.platform.common.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
@Service
public class AgentExperimentService {
 private final AgentExperimentRepository experiments;private final ExperimentVariantRepository variants;private final ExperimentTrialRepository trials;private final ExperimentAdmissionRepository admission;
 private final AgentRepository agents;private final AgentRuntimeInstanceRepository runtimes;private final SubstrateDeploymentRepository deployments;private final AgentExperimentClient client;private final ObjectMapper mapper;private final AgentService visibility;
 @Value("${eap.experiment.enabled:false}") boolean enabled;
 public AgentExperimentService(AgentExperimentRepository experiments,ExperimentVariantRepository variants,ExperimentTrialRepository trials,ExperimentAdmissionRepository admission,AgentRepository agents,AgentRuntimeInstanceRepository runtimes,SubstrateDeploymentRepository deployments,AgentExperimentClient client,ObjectMapper mapper,AgentService visibility){this.experiments=experiments;this.variants=variants;this.trials=trials;this.admission=admission;this.agents=agents;this.runtimes=runtimes;this.deployments=deployments;this.client=client;this.mapper=mapper;this.visibility=visibility;}
 public record TargetView(Long agentId,String instanceKey) {}
 public record Targets(boolean enabled,List<TargetView> instances) {}
 @Transactional(readOnly=true) public Targets targets(Long agentId){visibility.get(agentId);return new Targets(enabled,runtimes.findByAgentId(agentId).stream().filter(this::platformRuntime).map(r->new TargetView(r.getAgentId(),r.getInstanceKey())).toList());}
 private boolean platformRuntime(AgentRuntimeInstance r){return r.getImportedActorUid()==null&&"SUBSTRATE".equalsIgnoreCase(r.getProtocol())&&r.getStatus()!=RuntimeStatus.OFFLINE;}
 private AgentRuntimeInstance target(Long agentId,String key){
  visibility.get(agentId);
  if(agents.findById(agentId).filter(a->a.getLifecycleStatus()==AgentLifecycleStatus.ACTIVE).isEmpty())throw new BusinessException("实验需要已启用的智能体");
  return runtimes.findByInstanceKey(key).filter(r->agentId.equals(r.getAgentId())&&platformRuntime(r)).orElseThrow(()->new BusinessException("实验仅支持该智能体的平台托管实例"));
 }
 private SubstrateDeployment deployment(Long agentId,AgentExperimentClient.RuntimeProfile p){return deployments.findTop20ByAgentIdOrderByVersionDesc(agentId).stream().filter(d->"READY".equals(d.getStatus())&&(d.getAtespace()+"/"+d.getTemplateName()).equals(p.template())&&d.getImageRef()!=null&&d.getImageRef().contains("@sha256:")).findFirst().orElseThrow(()->new BusinessException("实例未对应已发布的固定镜像版本"));}
 @Transactional(readOnly=true) public AgentExperimentDtos.Profile profile(AgentExperimentDtos.Target r){checkEnabled();var runtime=target(r.agentId(),r.instanceKey());var p=readProfile(runtime.getEndpoint(),r.agentId());var d=deployment(r.agentId(),p);return new AgentExperimentDtos.Profile(p.profile(),d.getImageRef(),d.getVersion(),p.actorUid(),p.template());}
 private AgentExperimentClient.RuntimeProfile readProfile(String endpoint,Long agentId){try{var p=client.profile(endpoint);if(!agentId.toString().equals(p.profile().path("agentId").asText()))throw new BusinessException("实例智能体身份不一致");return p;}catch(IllegalArgumentException e){throw new BusinessException(e.getMessage());}}
 private void checkEnabled(){if(!enabled)throw new BusinessException("提示词对照实验尚未启用");}
 @Transactional public AgentExperimentDtos.Summary submit(AgentExperimentDtos.Submit r,String actor){return submitLinked(r,actor,null);}
 @org.springframework.beans.factory.annotation.Autowired private org.springframework.jdbc.core.JdbcTemplate optimizationDb;
 @org.springframework.beans.factory.annotation.Autowired private com.eap.platform.optimization.OptimizationUnknownReviewService reviews;
 @org.springframework.beans.factory.annotation.Autowired private com.eap.platform.observability.DiagnosticSamplingService diagnostics;
 @org.springframework.beans.factory.annotation.Autowired private com.eap.platform.observability.ObservationCoverageService coverage;
 @Transactional public AgentExperimentDtos.Summary submitOptimization(AgentExperimentDtos.Submit r,String actor,Long runId){
  if(runId==null)throw new BusinessException("优化运行标识缺失");
  var rows=optimizationDb.queryForList("select * from agent_optimizations where id=? and public_samples_confirmed=true for update",runId);
  if(rows.isEmpty())throw new BusinessException("优化运行不存在");var root=rows.getFirst();String state=(String)root.get("status");
  if(!Set.of("SEARCHING","HOLDOUT").contains(state)||!Objects.equals(root.get("agent_id"),r.agentId())||!Objects.equals(root.get("instance_key"),r.instanceKey())||!Objects.equals(root.get("requested_by"),actor)||!r.profileDigest().equals(tree((String)root.get("profile_json")).path("profileDigest").asText())||r.maxTokens()!=((Number)root.get("max_tokens")).intValue())throw new ConflictException("子实验与冻结优化运行不一致或已经停止");
  if("HOLDOUT".equals(state)&&!Objects.equals(root.get("manual_prompt"),r.candidatePrompt())&&!Objects.equals(root.get("chosen_digest"),digest(Map.of("system_prompt",r.candidatePrompt()))))throw new ConflictException("留出评测只允许已封存候选与人工候选");
  Set<String> splits=new HashSet<>();
  for(var sample:r.samples()) {
   String split=null;for(JsonNode fixed:tree((String)root.get("dataset_json")))if(sample.input().equals(fixed.path("input").asText())&&sample.expected().equals(fixed.path("expected").asText())){split=fixed.path("split").asText();break;}
   if(split==null||("HOLDOUT".equals(state)?!"HOLDOUT".equals(split):!Set.of("TRAIN","VALIDATION").contains(split)))throw new ConflictException("子实验包含未获准的切分或样本");splits.add(split);
  }
  if(splits.size()!=1)throw new ConflictException("子实验不能混合数据切分");
  return submitLinked(r,actor,runId);
 }
 private AgentExperimentDtos.Summary submitLinked(AgentExperimentDtos.Submit r,String actor,Long runId){
  checkEnabled();admission.lock();String fingerprint=digest(runId==null?List.of(r.agentId(),r.instanceKey(),r.profileDigest(),r.candidatePrompt(),r.maxTokens(),r.samples(),r.publicSamplesConfirmed(),actor):List.of(r.agentId(),r.instanceKey(),r.profileDigest(),r.candidatePrompt(),r.maxTokens(),r.samples(),r.publicSamplesConfirmed(),actor,runId));
  var prior=experiments.findBySubmissionKey(r.requestKey());if(prior.isPresent()){if(!fingerprint.equals(prior.get().requestFingerprint))throw new ConflictException("提交标识已用于不同实验");return summary(prior.get());}
  if(reviews.hasBlockingExperiments())throw new ConflictException("已有实验正在执行或原未知尚未完成审计核对");
  reviews.requireIndependentInstance(r.instanceKey());
  if(!r.publicSamplesConfirmed()||r.samples().isEmpty()||r.samples().size()>3||r.maxTokens()<1||r.maxTokens()>256||r.candidatePrompt().getBytes(StandardCharsets.UTF_8).length>8000)throw new BusinessException("实验仅支持确认公开的 1–3 条样本和不超过 256 的输出上限");
  if(r.samples().stream().anyMatch(s->s.input().getBytes(StandardCharsets.UTF_8).length>2000||s.input().isBlank()||s.expected().isBlank())||r.samples().stream().map(AgentExperimentDtos.Sample::input).distinct().count()!=r.samples().size())throw new BusinessException("样本输入过长、为空或重复");
  var runtime=target(r.agentId(),r.instanceKey());var p=readProfile(runtime.getEndpoint(),r.agentId());var dep=deployment(r.agentId(),p);
  if(!r.profileDigest().equals(p.profile().path("profileDigest").asText())||r.maxTokens()>p.profile().path("maxTokens").asInt())throw new ConflictException("运行配置已改变或输出上限超出实例配置，请重新读取基线");
  if(runId!=null){var root=optimizationDb.queryForMap("select profile_json from agent_optimizations where id=?",runId);var frozen=tree((String)root.get("profile_json"));if(frozen.has("_imageRef")&&(!dep.getImageRef().equals(frozen.path("_imageRef").asText())||dep.getVersion()!=frozen.path("_deploymentVersion").asInt()||!p.actorUid().equals(frozen.path("_actorUid").asText())||!p.template().equals(frozen.path("_template").asText())))throw new ConflictException("冻结的运行程序或实例身份已经改变");}
  String baseline=p.profile().path("systemPrompt").asText();if(baseline.getBytes(StandardCharsets.UTF_8).length>8000)throw new BusinessException("当前基线提示词超出实验长度限制");if(runId==null&&baseline.equals(r.candidatePrompt()))throw new BusinessException("候选提示词与基线相同");
  AgentExperiment e=new AgentExperiment();e.optimizationRunId=runId;e.agentId=r.agentId();e.instanceKey=r.instanceKey();e.endpoint=runtime.getEndpoint();e.imageRef=dep.getImageRef();e.deploymentVersion=dep.getVersion();e.actorUid=p.actorUid();e.template=p.template();e.submissionKey=r.requestKey();e.requestFingerprint=fingerprint;e.profileDigest=r.profileDigest();e.profileJson=json(p.profile());e.samplesJson=json(r.samples());e.maxTokens=r.maxTokens();e.requestedBy=actor;experiments.saveAndFlush(e);
  var b=variant(e,"BASELINE",baseline);var c=variant(e,"CANDIDATE",r.candidatePrompt());
  for(int i=0;i<r.samples().size();i++)for(var v:i%2==0?List.of(b,c):List.of(c,b)){var t=new ExperimentTrial();t.experimentId=e.id;t.variantId=v.id;t.sampleIndex=i;t.taskKey="experiment:"+e.id+":"+v.variantName.toLowerCase()+":"+i;t.traceId=UUID.randomUUID().toString().replace("-","");trials.save(t);}
  trials.flush();diagnostics.register(e.id,runtime,p.actorUid(),p.template());coverage.enrollExperiment(e.id);return summary(e);
 }
 private ExperimentVariant variant(AgentExperiment e,String name,String prompt){var v=new ExperimentVariant();v.experimentId=e.id;v.variantName=name;v.systemPrompt=prompt;v.configDigest=digest(List.of("EAP_STATELESS_PROMPT_V1",e.profileDigest,prompt,e.maxTokens,"temperature=0","context=EMPTY","tools=DISABLED"));return variants.saveAndFlush(v);}
 @Transactional(readOnly=true) public List<AgentExperimentDtos.Summary> list(){return experiments.findTop50ByOrderByIdDesc().stream().map(this::summary).toList();}
 @Transactional(readOnly=true) public AgentExperimentDtos.Summary get(Long id){return summary(find(id));}
 @Transactional(readOnly=true) public AgentExperimentDtos.Content content(Long id){var e=find(id);return new AgentExperimentDtos.Content(tree(e.profileJson),tree(e.samplesJson),variants.findByExperimentIdOrderByIdAsc(id).stream().map(v->new AgentExperimentDtos.VariantContent(v.variantName,v.systemPrompt)).toList(),trials.findByExperimentIdOrderByIdAsc(id).stream().map(t->new AgentExperimentDtos.TrialContent(t.id,t.outputText)).toList());}
 private AgentExperiment find(Long id){return experiments.findById(id).orElseThrow(()->new BusinessException("实验不存在"));}
 private AgentExperimentDtos.Summary summary(AgentExperiment e){var vs=variants.findByExperimentIdOrderByIdAsc(e.id);Map<Long,String> names=new HashMap<>();vs.forEach(v->names.put(v.id,v.variantName));return new AgentExperimentDtos.Summary(e.id,e.agentId,e.instanceKey,e.imageRef,e.deploymentVersion,e.status,e.profileDigest,digest(tree(e.samplesJson)),"EXACT_TEXT_V1",e.maxTokens,e.requestedBy,e.createdAt,vs.stream().map(v->new AgentExperimentDtos.Variant(v.id,v.variantName,v.configDigest)).toList(),trials.findByExperimentIdOrderByIdAsc(e.id).stream().map(t->new AgentExperimentDtos.Trial(t.id,names.get(t.variantId),t.sampleIndex,t.status,t.traceId,t.finishReason,t.inputTokens,t.outputTokens,t.usageSource,t.latencyMs,t.endToEndMs,t.queueMs,t.score,t.reasonCode)).toList());}
 public record Dispatch(Long trialId,Long agentId,String instanceKey,String endpoint,String actorUid,String template,String profileDigest,Map<String,Object> body) {}
 @Transactional public Dispatch claim(){
  if(!enabled)return null;admission.lock();var inFlight=trials.findFirstByStatusOrderByIdAsc("RUNNING");
  if(inFlight.isPresent()){var t=inFlight.get();if(t.startedAt.isBefore(Instant.now().minusSeconds(180))){t.status="UNKNOWN";t.reasonCode="WORKER_RECEIPT_LOST";t.finishedAt=Instant.now();stop(find(t.experimentId),"UNKNOWN");}return null;}
  var next=experiments.findFirstByStatusInOrderByIdAsc(List.of("QUEUED","RUNNING"));if(next.isEmpty())return null;var e=next.get();
  var t=trials.findByExperimentIdOrderByIdAsc(e.id).stream().filter(x->"QUEUED".equals(x.status)).findFirst().orElse(null);if(t==null)return null;
  try{var runtime=target(e.agentId,e.instanceKey);if(!runtime.getEndpoint().equals(e.endpoint))throw new BusinessException("实例端点改变");}catch(BusinessException ex){t.status="FAILED";t.reasonCode="AGENT_OR_RUNTIME_DISABLED";t.finishedAt=Instant.now();stop(e,"FAILED");return null;}
  e.status="RUNNING";t.status="RUNNING";t.startedAt=Instant.now();t.queueMs=Duration.between(e.createdAt,t.startedAt).toMillis();
  var v=variants.findById(t.variantId).orElseThrow();var sample=tree(e.samplesJson).get(t.sampleIndex);
  var body=new LinkedHashMap<String,Object>(Map.of("taskKey",t.taskKey,"traceId",t.traceId,"profileDigest",e.profileDigest,"systemPrompt",v.systemPrompt,"input",sample.path("input").asText(),"maxTokens",e.maxTokens));
  if("EAP_OPTIONAL_DIAGNOSTIC_V1".equals(tree(e.profileJson).path("diagnosticsProtocol").asText()))body.put("emitDiagnostic",true);
  coverage.experimentEvent(t.id,true);
  return new Dispatch(t.id,e.agentId,e.instanceKey,e.endpoint,e.actorUid,e.template,e.profileDigest,body);
 }
 @Transactional public void finish(Dispatch d,AgentExperimentClient.Receipt receipt){
  admission.lock();var t=trials.findById(d.trialId()).orElseThrow();if(!"RUNNING".equals(t.status))return;var e=find(t.experimentId);t.finishedAt=Instant.now();t.endToEndMs=Duration.between(t.startedAt,t.finishedAt).toMillis();t.status=receipt.status();t.reasonCode=receipt.reasonCode();coverage.experimentEvent(t.id,false);
  if(!"SUCCEEDED".equals(t.status)){stop(e,t.status);return;}
  var body=receipt.body();t.outputText=body.path("output").asText();t.finishReason=body.path("finishReason").asText();
  if(body.path("latencyMs").canConvertToLong()&&body.path("latencyMs").asLong()>=0)t.latencyMs=body.path("latencyMs").asLong();
  var usage=body.path("usage");var input=usage.has("prompt_tokens")?usage.path("prompt_tokens"):usage.path("input_tokens");var output=usage.has("completion_tokens")?usage.path("completion_tokens"):usage.path("output_tokens");
  if(input.isIntegralNumber()&&input.canConvertToLong()&&input.asLong()>=0&&output.isIntegralNumber()&&output.canConvertToLong()&&output.asLong()>=0){t.inputTokens=input.asLong();t.outputTokens=output.asLong();t.usageSource="PROVIDER_REPORTED";}
  String expected=tree(e.samplesJson).get(t.sampleIndex).path("expected").asText();t.score="stop".equals(t.finishReason)&&expected.trim().equals(t.outputText.trim())?1:0;
  if("length".equals(t.finishReason))t.reasonCode="OUTPUT_TRUNCATED";
  if(trials.findByExperimentIdOrderByIdAsc(e.id).stream().noneMatch(x->Set.of("QUEUED","RUNNING").contains(x.status)))e.status="COMPLETED";
 }
 private void stop(AgentExperiment e,String status){e.status=status;for(var t:trials.findByExperimentIdOrderByIdAsc(e.id))if("QUEUED".equals(t.status)){t.status="CANCELLED";t.reasonCode="EXPERIMENT_STOPPED";t.finishedAt=Instant.now();}}
 private String json(Object o){try{return mapper.writeValueAsString(o);}catch(Exception ex){throw new IllegalStateException("实验数据无法序列化");}}
 private JsonNode tree(String s){try{return mapper.readTree(s);}catch(Exception ex){throw new IllegalStateException("实验数据无法解析");}}
 private String digest(Object o){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(o).getBytes(StandardCharsets.UTF_8)));}catch(Exception ex){throw new IllegalStateException("实验摘要计算失败");}}
}
