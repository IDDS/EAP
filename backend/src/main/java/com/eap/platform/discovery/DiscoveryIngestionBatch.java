package com.eap.platform.discovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "discovery_ingestion_batches")
public class DiscoveryIngestionBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "endpoint_key", nullable = false, length = 128)
    String endpointKey;

    @Column(name = "batch_id", nullable = false, length = 128)
    String batchId;

    @Column(name = "scan_id", length = 128)
    String scanId;

    @Column(name = "snapshot_type", nullable = false, length = 32)
    String snapshotType;

    @Column(name = "observations_count", nullable = false)
    Integer observationsCount = 0;

    @Column(name = "discovered_count", nullable = false)
    Integer discoveredCount = 0;

    @Column(nullable = false, length = 32)
    String status = "RECEIVED";

    @Column(name = "received_at", nullable = false)
    Instant receivedAt;

    @Column(name = "completed_at")
    Instant completedAt;
}
