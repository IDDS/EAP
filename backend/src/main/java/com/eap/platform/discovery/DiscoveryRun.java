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
@Table(name = "discovery_runs")
public class DiscoveryRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 32)
    DiscoveryTriggerType triggerType;

    @Column(name = "endpoint_key", length = 128)
    String endpointKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    DiscoveryRunStatus status = DiscoveryRunStatus.RUNNING;

    @Column(name = "observations_count", nullable = false)
    Integer observationsCount = 0;

    @Column(name = "discovered_count", nullable = false)
    Integer discoveredCount = 0;

    @Column(name = "error_message", length = 1024)
    String errorMessage;

    @Column(name = "started_at", nullable = false)
    Instant startedAt;

    @Column(name = "finished_at")
    Instant finishedAt;

    @PrePersist
    void prePersist() {
        startedAt = startedAt == null ? Instant.now() : startedAt;
    }
}
