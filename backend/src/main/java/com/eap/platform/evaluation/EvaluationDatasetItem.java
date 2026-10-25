package com.eap.platform.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "eap_evaluation_dataset_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_eap_evaluation_dataset_items_key", columnNames = {"dataset_id", "item_key"})
})
public class EvaluationDatasetItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "dataset_id", nullable = false)
    Long datasetId;

    @Column(name = "item_key", nullable = false, length = 128)
    String itemKey;

    @Column(name = "evaluator_key", nullable = false, length = 128)
    String evaluatorKey;

    @Column(name = "evaluator_version", length = 64)
    String evaluatorVersion;

    @Column(name = "agent_id")
    Long agentId;

    @Column(name = "trace_id", length = 128)
    String traceId;

    @Column(name = "span_id", length = 128)
    String spanId;

    // Frozen on creation. No FK: retention/deletion of telemetry must not erase this snapshot.
    @Column(name = "source_observation_id", updatable = false)
    Long sourceObservationId;

    @Column(name = "source_endpoint_key", length = 128, updatable = false)
    String sourceEndpointKey;

    @Column(name = "source_observed_at", updatable = false)
    Instant sourceObservedAt;

    @Column(name = "source_checked_at", updatable = false)
    Instant sourceCheckedAt;

    @Column(name = "input_json", columnDefinition = "text")
    String inputJson;

    @Column(name = "expected_json", columnDefinition = "text")
    String expectedJson;

    @Column(name = "actual_json", columnDefinition = "text")
    String actualJson;

    @Column(name = "ground_truth_label", length = 16)
    String groundTruthLabel;

    @Column(name = "ground_truth_score", precision = 8, scale = 6)
    BigDecimal groundTruthScore;

    @Column(name = "annotation_rationale", length = 2048)
    String annotationRationale;

    @Column(name = "annotated_by", length = 128)
    String annotatedBy;

    @Column(name = "annotated_at")
    Instant annotatedAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
