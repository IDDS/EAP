package com.eap.platform.discovery;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;

@Component
public class DiscoveryAgentAvailabilityScheduler {
    private static final Set<String> ACTIVE_STATUSES = Set.of("ACTIVE", "ONLINE", "PENDING_REVIEW");
    private final DiscoveryEndpointRepository endpointRepository;
    private final AgentDiscoveryService discoveryService;
    private final DiscoveryAgentV1Properties properties;

    public DiscoveryAgentAvailabilityScheduler(
            DiscoveryEndpointRepository endpointRepository,
            AgentDiscoveryService discoveryService,
            DiscoveryAgentV1Properties properties
    ) {
        this.endpointRepository = endpointRepository;
        this.discoveryService = discoveryService;
        this.properties = properties;
    }

    @Scheduled(
            initialDelayString = "${eap.discovery.agent-v1.availability-check-ms:60000}",
            fixedDelayString = "${eap.discovery.agent-v1.availability-check-ms:60000}"
    )
    @Transactional
    public void markSilentEndpointsOffline() {
        long silenceSeconds = Math.max(180L, properties.getScanIntervalSeconds() * 3L);
        Instant cutoff = Instant.now().minusSeconds(silenceSeconds);
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
