package com.eap.platform.management;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ManagementConnectorScheduler {
    private final ManagementConnectorProperties properties;
    private final ManagementConnectorService service;

    public ManagementConnectorScheduler(ManagementConnectorProperties properties, ManagementConnectorService service) {
        this.properties = properties;
        this.service = service;
    }

    @Scheduled(
            initialDelayString = "${eap.management.connectors.scheduler-initial-delay-ms:60000}",
            fixedDelayString = "${eap.management.connectors.scheduler-check-ms:60000}"
    )
    public void syncDueConnectors() {
        if (properties.isSchedulerEnabled()) service.syncDueConnectors();
    }
}
