package com.eap.platform.registry;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeLeasePropertiesTests {
    private final RuntimeLeaseProperties lease = new RuntimeLeaseProperties();

    @Test
    void silentHttpRuntimeExpiresButSuspendedSubstrateActorDoesNot() {
        Instant stale = Instant.now().minusSeconds(3600);

        assertThat(lease.effectiveStatus(runtime("EAP_HTTP_TASK_V1", RuntimeStatus.ONLINE, stale), lease.cutoff()))
                .isEqualTo(RuntimeStatus.OFFLINE);
        assertThat(lease.effectiveStatus(runtime("SUBSTRATE", RuntimeStatus.ONLINE, stale), lease.cutoff()))
                .isEqualTo(RuntimeStatus.ONLINE);
        assertThat(lease.effectiveStatus(runtime("SUBSTRATE", RuntimeStatus.OFFLINE, stale), lease.cutoff()))
                .isEqualTo(RuntimeStatus.OFFLINE);
    }

    private static AgentRuntimeInstance runtime(String protocol, RuntimeStatus status, Instant heartbeat) {
        AgentRuntimeInstance runtime = mock(AgentRuntimeInstance.class);
        when(runtime.getProtocol()).thenReturn(protocol);
        when(runtime.getStatus()).thenReturn(status);
        when(runtime.getLastHeartbeatAt()).thenReturn(heartbeat);
        return runtime;
    }
}
