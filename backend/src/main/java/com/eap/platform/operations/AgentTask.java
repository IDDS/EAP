package com.eap.platform.operations;

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

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "agent_tasks")
public class AgentTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "agent_id", nullable = false)
    Long agentId;

    @Column(name = "agent_version_id")
    Long agentVersionId;

    @Column(name = "task_type", nullable = false, length = 64)
    String taskType;

    @Column(name = "input_summary", length = 1024)
    String inputSummary;

    @Column(name = "requester", length = 128)
    String requester;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    AgentTaskStatus status = AgentTaskStatus.PENDING;

    @Column(name = "started_at")
    Instant startedAt;

    @Column(name = "finished_at")
    Instant finishedAt;

    @Column(name = "latency_ms")
    Long latencyMs;

    @Column(name = "token_cost")
    Long tokenCost;

    @Column(name = "money_cost")
    BigDecimal moneyCost;

    @Column(name = "result_summary", length = 1024)
    String resultSummary;

    @Column(name = "problem_resolved")
    Boolean problemResolved;

    @Column(name = "resolution_summary", length = 1024)
    String resolutionSummary;

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

    public Long getAgentId() { return agentId; }
    public AgentTaskStatus getStatus() { return status; }
    public Instant getUpdatedAt() { return updatedAt; }
}
