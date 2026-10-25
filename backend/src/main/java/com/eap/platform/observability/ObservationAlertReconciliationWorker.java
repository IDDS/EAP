package com.eap.platform.observability;

import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name="eap.observability.alert-reconciliation.enabled",havingValue="true")
public class ObservationAlertReconciliationWorker {
    private final ObservationAlertReconciliationService service;
    private final ExecutorService executor=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("observation-alert-scan").factory());
    private final AtomicBoolean busy=new AtomicBoolean();
    public ObservationAlertReconciliationWorker(ObservationAlertReconciliationService service) { this.service=service; }
    @Scheduled(initialDelayString="${eap.observability.alert-reconciliation.initial-delay-ms:30000}",
               fixedDelayString="${eap.observability.alert-reconciliation.interval-ms:60000}")
    public void process() {
        if(!busy.compareAndSet(false,true)) return;
        try {
            executor.execute(() -> {
                try {
                    var result=service.runBatch();
                    LoggerFactory.getLogger(getClass()).info("observation_alert_scan sweep={} processed={} failures={} complete={}",
                            result.sweep(),result.processed(),result.failures(),result.sweepComplete());
                } catch(RuntimeException failure) {
                    LoggerFactory.getLogger(getClass()).error("Observation alert scan interrupted; persistent cursor retained");
                } finally { busy.set(false); }
            });
        } catch(RejectedExecutionException stopped) { busy.set(false); }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
