package com.eap.platform.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name="eap.workflow.execution.worker-enabled",havingValue="true",matchIfMissing=true)
public class WorkflowToolRecoveryWorker {
    private final WorkflowToolRecoveryService service;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("workflow-tool-query").factory());
    private final AtomicBoolean busy=new AtomicBoolean();
    public WorkflowToolRecoveryWorker(WorkflowToolRecoveryService service) { this.service=service; }
    @Scheduled(initialDelayString="${eap.workflow.execution.worker-initial-delay-ms:30000}",fixedDelay=5000)
    public void process() {
        if(!busy.compareAndSet(false,true))return;
        executor.execute(()->{try{service.processPending();}
            catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).error("工具查询责任处理失败");}
            finally{busy.set(false);}});
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
