package com.eap.platform.agentops;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AgentOpsSignalScheduler {
    private final WorkItemService service;

    public AgentOpsSignalScheduler(WorkItemService service) {
        this.service = service;
    }

    @Scheduled(initialDelayString = "${eap.agentops.signal-initial-delay-ms:10000}",
            fixedDelayString = "${eap.agentops.signal-interval-ms:60000}")
    public void synchronize() {
        service.synchronizeSignals();
    }
}
