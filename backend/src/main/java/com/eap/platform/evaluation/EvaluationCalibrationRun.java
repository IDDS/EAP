package com.eap.platform.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/** Completed calibration evidence is append-only and independent of mutable source records. */
@Entity
@Immutable
@Table(name = "eap_evaluation_calibration_runs")
public class EvaluationCalibrationRun {
    @Id
    @Column(name = "run_id", length = 64, updatable = false)
    String runId;
    @Column(name = "dataset_key", nullable = false, length = 128, updatable = false)
    String datasetKey;
    @Column(name = "dataset_version", nullable = false, length = 64, updatable = false)
    String datasetVersion;
    @Column(name = "requested_limit", nullable = false, updatable = false)
    int requestedLimit;
    @Column(name = "total_items", nullable = false, updatable = false)
    int totalItems;
    @Column(name = "evaluated_items", nullable = false, updatable = false)
    int evaluatedItems;
    @Column(name = "failed_items", nullable = false, updatable = false)
    int failedItems;
    @Column(name = "calculated_at", nullable = false, updatable = false)
    Instant calculatedAt;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "longtext", updatable = false)
    String snapshotJson;
}
