package com.eap.platform.discovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "discovery_enrollment_tokens")
public class DiscoveryEnrollmentToken {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    String tokenHash;

    @Column(name = "token_hint", nullable = false, length = 32)
    String tokenHint;

    @Column(name = "tenant_id", nullable = false, length = 64)
    String tenantId;

    @Column(name = "site_name", length = 128)
    String siteName;

    @Column(name = "environment_name", length = 64)
    String environmentName;

    @Column(length = 128)
    String department;

    @Column(name = "labels_json", columnDefinition = "text")
    String labelsJson;

    @Column(name = "max_uses", nullable = false)
    Integer maxUses = 1;

    @Column(name = "used_count", nullable = false)
    Integer usedCount = 0;

    @Column(nullable = false, length = 32)
    String status = "ACTIVE";

    @Column(name = "expires_at", nullable = false)
    Instant expiresAt;

    @Column(name = "created_by", nullable = false, length = 128)
    String createdBy;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "last_used_at")
    Instant lastUsedAt;

    @PrePersist
    void prePersist() {
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
