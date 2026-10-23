package com.eap.platform.evaluation;

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
@Table(name = "eap_evaluation_datasets")
public class EvaluationDataset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "dataset_key", nullable = false, unique = true, length = 128)
    String datasetKey;

    @Column(nullable = false, length = 256)
    String name;

    @Column(length = 2048)
    String description;

    @Column(name = "source_type", nullable = false, length = 32)
    String sourceType = "MANUAL";

    @Column(nullable = false, length = 64)
    String version = "v1";

    @Column(name = "item_count", nullable = false)
    int itemCount = 0;

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
