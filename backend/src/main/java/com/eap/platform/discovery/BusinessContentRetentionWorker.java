package com.eap.platform.discovery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "eap.discovery.content-retention.enabled", havingValue = "true", matchIfMissing = true)
public class BusinessContentRetentionWorker {
    private final BusinessContentRetentionService service;
    private final int batchSize;

    public BusinessContentRetentionWorker(BusinessContentRetentionService service,
            @Value("${eap.discovery.content-retention.batch-size:100}") int batchSize) {
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(initialDelayString = "${eap.discovery.content-retention.initial-delay-ms:30000}",
            fixedDelayString = "${eap.discovery.content-retention.interval-ms:60000}")
    public void process() { service.eraseExpired(batchSize); }
}
