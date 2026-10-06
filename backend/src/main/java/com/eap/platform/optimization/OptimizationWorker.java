package com.eap.platform.optimization;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name="eap.optimization.enabled",havingValue="true")
public class OptimizationWorker {
    private final OptimizationService service;
    private final OptimizationWorkerClient client;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("bounded-optimization").factory());
    private final AtomicBoolean busy=new AtomicBoolean();
    public OptimizationWorker(OptimizationService service,OptimizationWorkerClient client){this.service=service;this.client=client;}
    @Scheduled(initialDelayString="${eap.optimization.worker-initial-delay-ms:30000}",fixedDelay=2000)
    public void process(){
        if(!busy.compareAndSet(false,true))return;
        executor.execute(()->{
            OptimizationDtos.Claim claim=null;
            try{
                service.advanceHoldout();service.expireSearches();if(!client.available())return;claim=service.claim();if(claim==null)return;
                long manual=service.manualValidation(claim);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
                while(System.nanoTime()<deadline){
                    String state=(String)service.operation(manual).get("status");
                    if("COMPLETED".equals(state)){if(service.manualReady(claim,manual)&&!client.start(claim))service.dispatchUncertain(claim.id(),claim.token());return;}
                    if(!"QUEUED".equals(state)&&!"RUNNING".equals(state)){service.manualReady(claim,manual);return;}
                    TimeUnit.MILLISECONDS.sleep(500);
                }
                service.dispatchUncertain(claim.id(),claim.token());
            }catch(Exception e){if(claim!=null)service.dispatchUncertain(claim.id(),claim.token());}
            finally{busy.set(false);}
        });
    }
    @PreDestroy public void close(){executor.shutdownNow();}
}
