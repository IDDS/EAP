package com.eap.platform.discovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "discovery_endpoints")
public class DiscoveryEndpoint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "endpoint_key", nullable = false, unique = true, length = 128)
    String endpointKey;

    @Column(name = "host_name", nullable = false, length = 256)
    String hostName;

    @Column(name = "account_name", length = 256)
    String accountName;

    @Column(name = "operating_system", length = 128)
    String operatingSystem;

    @Column(name = "collector_version", length = 64)
    String collectorVersion;

    @Column(nullable = false, length = 32)
    String status = "ACTIVE";

    @Column(name = "first_seen_at", nullable = false)
    Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        firstSeenAt = firstSeenAt == null ? now : firstSeenAt;
        lastSeenAt = lastSeenAt == null ? now : lastSeenAt;
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
