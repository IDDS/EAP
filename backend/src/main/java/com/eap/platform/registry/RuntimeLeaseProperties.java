package com.eap.platform.registry;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import java.time.Instant;

@Component
@Validated
@ConfigurationProperties(prefix = "eap.registry.runtime")
public class RuntimeLeaseProperties {
    @Min(1)
    private long heartbeatTtlSeconds = 180;

    public long getHeartbeatTtlSeconds() { return heartbeatTtlSeconds; }
    public void setHeartbeatTtlSeconds(long value) { heartbeatTtlSeconds = value; }
    public Instant cutoff() { return Instant.now().minusSeconds(heartbeatTtlSeconds); }

    public RuntimeStatus effectiveStatus(AgentRuntimeInstance instance, Instant cutoff) {
        if (instance.getStatus() != RuntimeStatus.OFFLINE && !"SUBSTRATE_TASK".equals(instance.getProtocol()) && !com.eap.platform.substrate.SubstrateActorRef.isSubstrate(instance.getProtocol())
                && (instance.getLastHeartbeatAt() == null || !instance.getLastHeartbeatAt().isAfter(cutoff))) {
            return RuntimeStatus.OFFLINE;
        }
        return instance.getStatus();
    }
}
