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
@Table(name = "agent_plan_steps")
public class AgentPlanStep {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "plan_id", nullable = false)
    Long planId;

    @Column(name = "step_order", nullable = false)
    Integer stepOrder;

    @Column(nullable = false, length = 128)
    String title;

    @Column(name = "required_capability", nullable = false, length = 128)
    String requiredCapability;

    @Column(name = "execution_backend", nullable = false, length = 16)
    String executionBackend = "RUNTIME";

    @Column(name = "output_contract_json", columnDefinition = "TEXT")
    String outputContractJson;

    @Column(name = "assigned_agent_id")
    Long assignedAgentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    StepStatus status = StepStatus.PENDING;

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

