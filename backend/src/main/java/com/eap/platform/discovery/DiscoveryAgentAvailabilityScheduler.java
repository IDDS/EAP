package com.eap.platform.discovery;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

@Component
public class DiscoveryAgentAvailabilityScheduler {
    private static final Set<String> ACTIVE_STATUSES = Set.of("ACTIVE", "ONLINE", "PENDING_REVIEW");
    private final DiscoveryEndpointRepository endpointRepository;
    private final AgentDiscoveryService discoveryService;

    public DiscoveryAgentAvailabilityScheduler(
            DiscoveryEndpointRepository endpointRepository,
            AgentDiscoveryService discoveryService
    ) {
        this.endpointRepository = endpointRepository;
        this.discoveryService = discoveryService;
    }

    @Scheduled(
            initialDelayString = "${eap.discovery.agent-v1.availability-check-ms:3600000}",
            fixedDelayString = "${eap.discovery.agent-v1.availability-check-ms:3600000}"
    )
    @Transactional
    public void markSilentEndpointsOffline() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        endpointRepository.findAll().stream()
                .filter(endpoint -> ACTIVE_STATUSES.contains(endpoint.status))
                .filter(endpoint -> {
                    Instant lastSignalAt = endpoint.lastHeartbeatAt == null ? endpoint.lastSeenAt : endpoint.lastHeartbeatAt;
                    return lastSignalAt == null || lastSignalAt.isBefore(cutoff);
                })
                .forEach(endpoint -> {
                    endpoint.status = "OFFLINE";
                    discoveryService.markEndpointOffline(endpoint.endpointKey);
                });
    }
}
