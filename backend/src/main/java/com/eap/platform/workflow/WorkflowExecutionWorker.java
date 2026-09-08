package com.eap.platform.workflow;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "eap.workflow.execution.worker-enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowExecutionWorker {
    private final WorkflowExecutionService service;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("workflow-execution").factory());
    private final AtomicBoolean busy = new AtomicBoolean();
    public WorkflowExecutionWorker(WorkflowExecutionService service) { this.service = service; }
    @Scheduled(initialDelayString = "${eap.workflow.execution.worker-initial-delay-ms:30000}",
            fixedDelayString = "${eap.workflow.execution.worker-interval-ms:5000}")
    public void process() {
        if (!busy.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try { service.processNext(); }
            catch (RuntimeException exception) { org.slf4j.LoggerFactory.getLogger(getClass()).error("工作流队列处理失败", exception); }
            finally { busy.set(false); }
        });
    }
    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
