package com.eap.platform.experiment;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
@Component @ConditionalOnProperty(name="eap.experiment.enabled",havingValue="true")
public class AgentExperimentWorker {
 private final AgentExperimentService service;private final AgentExperimentClient client;
 private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("agent-experiment").factory());private final AtomicBoolean busy=new AtomicBoolean();
 public AgentExperimentWorker(AgentExperimentService service,AgentExperimentClient client){this.service=service;this.client=client;}
 @Scheduled(initialDelayString="${eap.experiment.worker-initial-delay-ms:30000}",fixedDelay=3000)
 public void process(){if(!busy.compareAndSet(false,true))return;executor.execute(()->{try{var d=service.claim();if(d!=null)service.finish(d,client.execute(d));}catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("实验处理未确认；保留执行状态供后续核对");}finally{busy.set(false);}});}
 @PreDestroy public void close(){executor.shutdownNow();}
}
