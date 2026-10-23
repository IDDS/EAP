package com.eap.platform.ax;
import com.eap.platform.ax.proto.*;
import io.grpc.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.TimeUnit;
@Component
public class GrpcAxClient implements AxClient {
 private final AxProperties properties;
 private ManagedChannel channel;
 public GrpcAxClient(AxProperties p){properties=p;}
 private synchronized AXGrpc.AXBlockingStub stub(){
  if(!properties.ready()) throw new IllegalStateException("AX 未启用或缺少固定镜像");
  if(channel==null) channel=ManagedChannelBuilder.forTarget(properties.getTarget()).usePlaintext().build();
  return AXGrpc.newBlockingStub(channel).withDeadlineAfter(10,TimeUnit.SECONDS);
 }
 private State state(Task task){return AxActorIdentity.state(task);}
 public State get(String key){return state(stub().getTask(GetTaskRequest.newBuilder().setAtespace(properties.getAtespace()).setName(key).build()));}
 public State ensure(String key, Map<String,String> env){
  return ensure(key,properties.getRunnerImage(),env);
 }
 public State ensure(String key,String image,Map<String,String> env){
  try{var existing=stub().getTask(GetTaskRequest.newBuilder().setAtespace(properties.getAtespace()).setName(key).build());AxActorIdentity.verifyFrozen(existing,properties.getAtespace(),key,image,env);return state(existing);}catch(StatusRuntimeException e){if(e.getStatus().getCode()!=Status.Code.NOT_FOUND) throw e;}
  if(!image.matches("[a-z0-9.:/_-]+@sha256:[a-f0-9]{64}"))throw new IllegalStateException("任务运行镜像未固定");
  var spec=TaskSpec.newBuilder().setImage(image).setDebug(false);
  env.forEach((k,v)->spec.addEnv(EnvVar.newBuilder().setName(k).setValue(v)));
  var task=Task.newBuilder().setApiVersion("ax.io/v1alpha1").setKind("Task")
    .setMetadata(ObjectMeta.newBuilder().setName(key).setAtespace(properties.getAtespace())).setSpec(spec).build();
  try{var created=stub().createTask(CreateTaskRequest.newBuilder().setTask(task).build());AxActorIdentity.verifyFrozen(created,properties.getAtespace(),key,image,env);return state(created);}
  catch(StatusRuntimeException e){if(e.getStatus().getCode()==Status.Code.ALREADY_EXISTS||e.getStatus().getCode()==Status.Code.FAILED_PRECONDITION){var existing=stub().getTask(GetTaskRequest.newBuilder().setAtespace(properties.getAtespace()).setName(key).build());AxActorIdentity.verifyFrozen(existing,properties.getAtespace(),key,image,env);return state(existing);}throw e;}
 }
 public void resume(String key){stub().resumeTask(ResumeTaskRequest.newBuilder().setAtespace(properties.getAtespace()).setName(key).build());}
 public void suspend(String key){stub().suspendTask(SuspendTaskRequest.newBuilder().setAtespace(properties.getAtespace()).setName(key).build());}
 @PreDestroy public synchronized void close(){if(channel!=null)channel.shutdownNow();}
}
