package com.eap.platform.registry;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Component
public class RuntimeLeaseScheduler {
    private final AgentRuntimeInstanceRepository repository;
    private final RuntimeLeaseProperties properties;

    public RuntimeLeaseScheduler(AgentRuntimeInstanceRepository repository, RuntimeLeaseProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${eap.registry.runtime.availability-check-ms:30000}",
            fixedDelayString = "${eap.registry.runtime.availability-check-ms:30000}")
    @Transactional
    public void expireSilentRuntimes() {
        repository.expireSilentRuntimes(List.of(RuntimeStatus.ONLINE, RuntimeStatus.DEGRADED),
                RuntimeStatus.OFFLINE, properties.cutoff(), Instant.now());
    }
}
