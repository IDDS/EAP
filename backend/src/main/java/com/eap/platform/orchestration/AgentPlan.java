package com.eap.platform.orchestration;

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
@Table(name = "agent_plans")
public class AgentPlan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "team_id", nullable = false)
    Long teamId;

    @Column(nullable = false, length = 128)
    String goal;

    @Column(name = "context_summary", length = 1024)
    String contextSummary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    PlanStatus status = PlanStatus.DRAFT;

    @Column(name = "execution_workflow_id", unique = true) Long executionWorkflowId;
    @Column(name = "execution_run_key", length = 128, unique = true) String executionRunKey;
    @Column(name = "execution_request_key", length = 64) String executionRequestKey;
    @Column(name = "execution_fingerprint", length = 64) String executionFingerprint;

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

