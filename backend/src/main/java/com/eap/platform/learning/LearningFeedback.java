package com.eap.platform.learning;

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
@Table(name = "learning_feedbacks")
public class LearningFeedback {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "agent_id", nullable = false)
    Long agentId;

    @Column(name = "feedback_type", nullable = false, length = 64)
    String feedbackType;

    @Column(nullable = false)
    Integer rating;

    @Column(nullable = false, length = 1024)
    String summary;

    @Column(name = "improvement_action", length = 1024)
    String improvementAction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    LearningFeedbackStatus status = LearningFeedbackStatus.OPEN;

    @Column(name = "task_id") Long taskId;
    @Column(name = "trace_id", length = 128) String traceId;
    @Column(name = "requested_by", length = 128) String requestedBy;
    @Column(name = "reviewed_by", length = 128) String reviewedBy;
    @Column(name = "submission_key", length = 64) String submissionKey;
    @Column(name = "request_fingerprint", length = 64) String requestFingerprint;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}

