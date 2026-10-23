package com.eap.platform.deployment;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "eap.substrate.deploy-worker-enabled", havingValue = "true", matchIfMissing = true)
public class SubstrateDeploymentWorker {
    private final SubstrateDeploymentService service;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("substrate-deploy").factory());
    private final AtomicBoolean busy = new AtomicBoolean();

    public SubstrateDeploymentWorker(SubstrateDeploymentService service) { this.service = service; }

    @Scheduled(initialDelayString = "${eap.substrate.deploy-worker-initial-delay-ms:15000}",
            fixedDelayString = "${eap.substrate.deploy-worker-interval-ms:3000}")
    public void process() {
        if (!busy.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try { service.processNext(); }
            catch (RuntimeException exception) { org.slf4j.LoggerFactory.getLogger(getClass()).error("Substrate 部署队列处理失败", exception); }
            finally { busy.set(false); }
        });
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }
}
