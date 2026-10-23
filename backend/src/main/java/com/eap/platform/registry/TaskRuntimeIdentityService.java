package com.eap.platform.registry;

import com.eap.platform.agent.*;
import com.eap.platform.common.*;
import com.eap.platform.substrate.*;
import com.eap.platform.ax.AxTaskRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

/** Trusted task factory only. A console heartbeat/registration cannot claim an AX create receipt. */
@Service
public class TaskRuntimeIdentityService {
 private final AxTaskRepository tasks;private final RuntimeConnectionRepository connectionsRepository;private final AgentRuntimeInstanceRepository runtimes;private final AgentRepository agents;
 private final SubstrateClient nativeClient;private final SubstrateProperties substrate;private final RuntimeConnectionProperties config;
 private final RuntimeCredentialDeliveryService delivery;private final RuntimeConnectionService connections;private final TransactionTemplate tx;
 private record Binding(Long agent,String scope,String name,String uid,String template,String request,String publicKey,Instant until,boolean revoked) {String key(){return scope+"/"+name;}}
 public TaskRuntimeIdentityService(AxTaskRepository tasks,RuntimeConnectionRepository connectionsRepository,AgentRuntimeInstanceRepository runtimes,AgentRepository agents,SubstrateClient nativeClient,
  SubstrateProperties substrate,RuntimeConnectionProperties config,RuntimeCredentialDeliveryService delivery,RuntimeConnectionService connections,PlatformTransactionManager manager){
  this.tasks=tasks;this.connectionsRepository=connectionsRepository;this.runtimes=runtimes;this.agents=agents;this.nativeClient=nativeClient;this.substrate=substrate;this.config=config;this.delivery=delivery;this.connections=connections;tx=new TransactionTemplate(manager);
 }
 private Binding binding(String task){
  var identity=tasks.identityByTaskKey(task).orElseThrow(()->new BusinessException("任务创建身份尚未绑定"));
  return new Binding(identity.agentId(),identity.identityScope(),identity.taskKey(),identity.actorUid(),identity.actorTemplate(),identity.deliveryRequest(),identity.identityPublicKey(),identity.identityUntil(),identity.identityRevoked());
 }
 public void approve(String task,boolean requireReceipt){var b=binding(task);approved(b,requireReceipt);if(requireReceipt)current(b);}
 private void approved(Binding b){approved(b,true);}
 private void approved(Binding b,boolean requireReceipt){
  if(b.revoked()||b.until()==null||!b.until().isAfter(Instant.now()))throw new ConflictException("原任务接入授权已撤销或到期");
  if(requireReceipt && (b.uid()==null||b.template()==null)||!config.deliveryReady()||!config.getDeliveryPublicKey().equals(b.publicKey()))throw new ConflictException("任务创建身份或冻结配送配置无法确认");
  if(agents.findById(b.agent()).filter(a->a.getLifecycleStatus()==AgentLifecycleStatus.ACTIVE).isEmpty())throw new BusinessException("任务归属智能体已停用");
 }
 private void current(Binding b){
  if(b.uid()==null||b.template()==null)throw new ConflictException("任务原生创建身份尚未确认");
  var ref=SubstrateActorRef.parse("substrate://"+b.key());
  if(!substrate.isEnabled()||!substrate.inventoryScopes().contains(b.scope()))throw new BusinessException("任务运行空间未获授权");
  var actual=nativeClient.inspect(ref);
  if(!b.scope().equals(actual.atespace())||!b.name().equals(actual.name())||!b.uid().equals(actual.uid())||!b.template().equals(actual.template()))throw new ConflictException("任务实例 UID 或模板已变化，未派发请求");
 }
 public void connect(String task,String requester){
  var b=binding(task);approved(b);current(b);
  tx.executeWithoutResult(s->{
   var row=runtimes.lockForScopedTools(b.key()).orElseGet(AgentRuntimeInstance::new);
   if(row.agentId!=null&&(!b.agent().equals(row.agentId)||row.status==RuntimeStatus.OFFLINE||!"SUBSTRATE_TASK".equals(row.protocol)||row.importedActorUid!=null||row.importedActorTemplate!=null||!b.uid().equals(row.platformActorUid)||!b.template().equals(row.platformActorTemplate)))throw new ConflictException("任务实例已有另一归属，不能覆盖或恢复");
   row.agentId=b.agent();row.instanceKey=b.key();row.endpoint="substrate://"+b.key();row.protocol="SUBSTRATE_TASK";row.platformActorUid=b.uid();row.platformActorTemplate=b.template();row.status=RuntimeStatus.ONLINE;row.dispatchAuthenticated=false;runtimes.saveAndFlush(row);
  });
  delivery.connectTaskCreated(b.key(),b.request(),requester);
  if(!"READY".equals(connections.status(b.key()).connection()))throw new BusinessException("任务专属身份配送尚未确认，原请求保留");
 }
 public String token(String task){var b=binding(task);approved(b);current(b);var runtime=runtimes.findByInstanceKey(b.key()).orElseThrow(()->new BusinessException("任务实例未纳管"));return connections.dispatchToken(runtime);}
 public void check(String task){var b=binding(task);current(b);}
 public String summary(String task){
  var b=binding(task);if(b.revoked())return "REVOKED";if(b.uid()==null)return "PENDING";
  var row=connectionsRepository.findByInstanceKey(b.key()).orElse(null);
  if(row==null)return "BOUND";if(row.generation==null)return "REVOKED";if(row.deliveryOperation!=null)return "DELIVERY_PENDING";
  if(b.until()==null||!b.until().isAfter(Instant.now())||row.expiresAt==null||!row.expiresAt.isAfter(Instant.now()))return "EXPIRED";
  return row.verifiedAt==null?"PENDING":"READY";
 }
}
