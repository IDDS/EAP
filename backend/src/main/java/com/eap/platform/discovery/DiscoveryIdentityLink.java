package com.eap.platform.discovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "discovery_identity_links")
public class DiscoveryIdentityLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "identity_key", nullable = false, unique = true, length = 64)
    String identityKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "discovered_agent_id", nullable = false)
    DiscoveredAgent discoveredAgent;

    @Column(name = "relation_type", nullable = false, length = 32)
    String relationType;

    @Column(nullable = false)
    Integer confidence;

    @Column(name = "identity_basis", length = 1024)
    String identityBasis;

    @Column(name = "source_types", nullable = false, length = 256)
    String sourceTypes;

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
    void preUpdate() { updatedAt = Instant.now(); }
}
