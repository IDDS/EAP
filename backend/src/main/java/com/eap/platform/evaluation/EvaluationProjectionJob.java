package com.eap.platform.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "eap_evaluation_jobs")
public class EvaluationProjectionJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "job_key", nullable = false, unique = true, length = 128)
    String jobKey;

    @Column(name = "requested_limit", nullable = false)
    Integer requestedLimit = 200;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    EvaluationJobStatus status = EvaluationJobStatus.QUEUED;

    @Column(nullable = false)
    Integer scanned = 0;

    @Column(nullable = false)
    Integer evaluated = 0;

    @Column(nullable = false)
    Integer skipped = 0;

    @Column(nullable = false)
    Integer failed = 0;

    @Column(nullable = false, length = 32)
    String phase = "QUEUED";
    @Column(nullable = false) int processed = 0;
    @Column(nullable = false) int total = 0;

    @Column(name = "error_message", length = 2048)
    String errorMessage;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "started_at")
    Instant startedAt;

    @Column(name = "completed_at")
    Instant completedAt;

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

    public Long getId() { return id; }
    public String getJobKey() { return jobKey; }
    public Integer getRequestedLimit() { return requestedLimit; }
    public EvaluationJobStatus getStatus() { return status; }
    public Integer getScanned() { return scanned; }
    public Integer getEvaluated() { return evaluated; }
    public Integer getSkipped() { return skipped; }
    public Integer getFailed() { return failed; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
