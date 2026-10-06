package com.eap.platform.ax;

import com.eap.platform.ax.proto.Task;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import java.util.*;

/** Public create receipt carried by the controller's existing Condition protocol. */
final class AxActorIdentity {
 static final String MODE="PER_INSTANCE_V1", CONDITION="EAPActorIdentity";
 private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
 private static boolean dns(String value){return value!=null&&value.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");}
 static boolean enabled(Task task){
  var modes=task.getSpec().getEnvList().stream().filter(e->e.getName().equals("EAP_IDENTITY_MODE")).toList();
  if(modes.isEmpty())return false;
  if(modes.size()!=1||!MODE.equals(modes.getFirst().getValue()))throw new IllegalStateException("任务实例身份模式无效");
  return true;
 }
 static AxClient.State state(Task task){
  String phase=task.getStatus().getPhase();
  boolean ready=task.getStatus().getConditionsList().stream().anyMatch(c->c.getType().equals("Ready")&&c.getStatus().equals("True"));
  if(!enabled(task))return new AxClient.State(phase,ready);
  var evidence=task.getStatus().getConditionsList().stream().filter(c->c.getType().equals(CONDITION)).toList();
  if(evidence.isEmpty())return new AxClient.State(phase,false,null,null,true);
  if(evidence.size()!=1)throw new IllegalStateException("任务原生身份回执重复");
  var condition=evidence.getFirst();
  if(!"True".equals(condition.getStatus())||!"CreateReceiptBound".equals(condition.getReason()))return new AxClient.State(phase,false,null,null,true);
  try{
   String space=task.getMetadata().getAtespace(),name=task.getMetadata().getName();
   var receipt=JSON.readTree(condition.getMessage());
   if(receipt==null||!receipt.isObject()||receipt.size()!=2||!receipt.path("actorUid").isTextual()||!receipt.path("actorTemplate").isTextual())throw new IllegalArgumentException();
   String uid=receipt.path("actorUid").asText(),template=receipt.path("actorTemplate").asText();
   String[] reference=template.split("/",-1);
   if(!dns(space)||!dns(name)||!task.getStatus().getActor().equals(name)||!UUID.fromString(uid).toString().equals(uid)||reference.length!=2||!space.equals(reference[0])||!dns(reference[1]))throw new IllegalArgumentException();
   return new AxClient.State(phase,ready,uid,template,true);
  }catch(Exception ignored){throw new IllegalStateException("任务原生身份回执无法确认");}
 }
 static void verifyFrozen(Task task,String space,String name,String image,Map<String,String> env){
  if(!MODE.equals(env.get("EAP_IDENTITY_MODE")))return;
  Map<String,String> actual=new HashMap<>();
  for(var entry:task.getSpec().getEnvList())if(actual.put(entry.getName(),entry.getValue())!=null)throw new IllegalStateException("任务冻结配置重复");
  if(!space.equals(task.getMetadata().getAtespace())||!name.equals(task.getMetadata().getName())||!image.equals(task.getSpec().getImage())||!actual.equals(env)||task.getSpec().getDebug()||task.getSpec().getCommandCount()!=0||task.getSpec().getWorkspacesCount()!=0)throw new IllegalStateException("任务已存在且冻结身份配置不同，不能接管");
 }
}
