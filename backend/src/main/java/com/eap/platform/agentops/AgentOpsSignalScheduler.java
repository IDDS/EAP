package com.eap.platform.agentops;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "eap.agentops.signal-sync.enabled", havingValue = "true", matchIfMissing = true)
public class AgentOpsSignalScheduler {
    private final WorkItemService service;
    public AgentOpsSignalScheduler(WorkItemService service) { this.service = service; }

    @Scheduled(initialDelayString = "${eap.agentops.signal-sync.initial-delay-ms:${eap.agentops.signal-initial-delay-ms:10000}}",
            fixedDelayString = "${eap.agentops.signal-sync.interval-ms:${eap.agentops.signal-interval-ms:60000}}")
    public void synchronize() { service.synchronizeSignals(); }
}
