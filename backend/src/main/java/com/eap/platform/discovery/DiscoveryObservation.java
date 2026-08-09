package com.eap.platform.discovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "discovery_observations")
public class DiscoveryObservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "run_id", nullable = false)
    Long runId;

    @Column(name = "discovered_agent_id", nullable = false)
    Long discoveredAgentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    DiscoverySourceType sourceType;

    @Column(name = "source_location", nullable = false, length = 1024)
    String sourceLocation;

    @Column(name = "evidence_type", nullable = false, length = 128)
    String evidenceType;

    @Column(name = "evidence_json", columnDefinition = "text")
    String evidenceJson;

    @Column(name = "observed_at", nullable = false)
    Instant observedAt;

    @PrePersist
    void prePersist() {
        observedAt = observedAt == null ? Instant.now() : observedAt;
    }
}
