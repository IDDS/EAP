package com.eap.platform.registry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "agent_runtime_instances")
public class AgentRuntimeInstance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "agent_id", nullable = false)
    Long agentId;

    @Column(name = "instance_key", nullable = false, length = 128)
    String instanceKey;

    @Column(nullable = false, length = 512)
    String endpoint;

    @Column(nullable = false, length = 32)
    String protocol;

    @Column(name = "dispatch_authenticated", nullable = false)
    Boolean dispatchAuthenticated = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    RuntimeStatus status = RuntimeStatus.ONLINE;

    @Column(nullable = false)
    Integer loadScore = 0;

    @Column(name = "last_heartbeat_at")
    Instant lastHeartbeatAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        lastHeartbeatAt = lastHeartbeatAt == null ? now : lastHeartbeatAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getAgentId() {
        return agentId;
    }

    public String getInstanceKey() {
        return instanceKey;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getProtocol() {
        return protocol;
    }

    public Boolean getDispatchAuthenticated() {
        return dispatchAuthenticated;
    }

    public RuntimeStatus getStatus() {
        return status;
    }

    public Integer getLoadScore() {
        return loadScore;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }
}
