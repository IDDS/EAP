package com.eap.platform.ax;
import java.util.Map;
public interface AxClient {
 record State(String phase, boolean ready, String actorUid, String actorTemplate, boolean instanceIdentity) {
  public State(String phase,boolean ready){this(phase,ready,null,null,false);}
 }
 State ensure(String taskKey, Map<String,String> env);
 default State ensure(String taskKey,String runnerImage,Map<String,String> env){return ensure(taskKey,env);}
 State get(String taskKey);
 void resume(String taskKey);
 void suspend(String taskKey);
}
