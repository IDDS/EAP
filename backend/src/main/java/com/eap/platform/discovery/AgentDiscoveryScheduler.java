package com.eap.platform.discovery;

import com.eap.platform.common.BusinessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AgentDiscoveryScheduler {
    private final DiscoveryProperties properties;
    private final AgentDiscoveryService discoveryService;

    public AgentDiscoveryScheduler(DiscoveryProperties properties, AgentDiscoveryService discoveryService) {
        this.properties = properties;
        this.discoveryService = discoveryService;
    }

    @Scheduled(
            initialDelayString = "${eap.discovery.initial-delay-ms:5000}",
            fixedDelayString = "${eap.discovery.interval-ms:300000}"
    )
    public void discoverAutomatically() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            discoveryService.runScan(DiscoveryTriggerType.SCHEDULED);
        } catch (BusinessException ignored) {
            // A manually triggered scan is already running.
        }
    }
}
