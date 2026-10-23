package com.eap.platform.discovery;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProtocolDiscoveryScheduler {
    private final ProtocolDiscoveryProperties properties;
    private final ProtocolDiscoveryService service;

    public ProtocolDiscoveryScheduler(ProtocolDiscoveryProperties properties, ProtocolDiscoveryService service) {
        this.properties = properties;
        this.service = service;
    }

    @Scheduled(
            initialDelayString = "${eap.discovery.protocol.initial-delay-ms:60000}",
            fixedDelayString = "${eap.discovery.protocol.interval-ms:300000}"
    )
    public void probeConfiguredTargets() {
        if (properties.isEnabled() && properties.getTargets() != null && !properties.getTargets().isEmpty()) {
            service.probeConfiguredTargets();
        }
    }
}
