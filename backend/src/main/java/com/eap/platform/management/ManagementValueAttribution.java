package com.eap.platform.management;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "management_value_attributions")
public class ManagementValueAttribution {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "value_event_id", nullable = false)
    Long valueEventId;

    @Column(name = "agent_id", nullable = false)
    Long agentId;

    @Column(name = "contribution_weight", nullable = false, precision = 9, scale = 6)
    BigDecimal contributionWeight;

    @Column(name = "attribution_method", nullable = false, length = 32)
    String attributionMethod = "BUSINESS_CONFIRMED";

    @Column(name = "evidence_reference", length = 256)
    String evidenceReference;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
