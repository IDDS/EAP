package com.eap.platform.ax;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="eap.ax.worker-enabled",havingValue="true",matchIfMissing=true)
public class AxWorker {
 private final AxService service; private final AtomicBoolean busy=new AtomicBoolean();
 private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("ax-reconcile").factory());
 public AxWorker(AxService service){this.service=service;}
 @Scheduled(initialDelay=15000,fixedDelay=2000) public void tick(){if(busy.compareAndSet(false,true))executor.execute(()->{try{service.processNext();}finally{busy.set(false);}});}
 @PreDestroy public void close(){executor.shutdownNow();}
}
