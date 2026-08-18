package com.eap.platform.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoveryAgentAvailabilitySchedulerTests {
    @Mock private DiscoveryEndpointRepository endpointRepository;
    @Mock private AgentDiscoveryService discoveryService;

    @Test
    void usesLegacyLastSeenTimeWhenHeartbeatIsUnavailable() {
        DiscoveryEndpoint stale = endpoint("legacy-stale", "ACTIVE", Instant.now().minus(16, ChronoUnit.MINUTES), null);
        DiscoveryEndpoint current = endpoint("legacy-current", "ACTIVE", Instant.now(), null);
        when(endpointRepository.findAll()).thenReturn(List.of(stale, current));

        DiscoveryAgentV1Properties properties = new DiscoveryAgentV1Properties();
        properties.setScanIntervalSeconds(300);
        new DiscoveryAgentAvailabilityScheduler(endpointRepository, discoveryService, properties).markSilentEndpointsOffline();

        assertThat(stale.status).isEqualTo("OFFLINE");
        assertThat(current.status).isEqualTo("ACTIVE");
        verify(discoveryService).markEndpointOffline("legacy-stale");
        verify(discoveryService, never()).markEndpointOffline("legacy-current");
    }

    private DiscoveryEndpoint endpoint(String key, String status, Instant lastSeenAt, Instant lastHeartbeatAt) {
        DiscoveryEndpoint endpoint = new DiscoveryEndpoint();
        endpoint.endpointKey = key;
        endpoint.status = status;
        endpoint.lastSeenAt = lastSeenAt;
        endpoint.lastHeartbeatAt = lastHeartbeatAt;
        return endpoint;
    }
}
