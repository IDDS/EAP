package com.eap.platform.workflow;

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
@Table(name = "workflow_edges")
public class WorkflowEdge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "workflow_id", nullable = false)
    Long workflowId;

    @Column(name = "source_node_key", nullable = false, length = 64)
    String sourceNodeKey;

    @Column(name = "target_node_key", nullable = false, length = 64)
    String targetNodeKey;

    @Column(length = 128)
    String conditionExpression;

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

    public Long getWorkflowId() {
        return workflowId;
    }

    public String getSourceNodeKey() {
        return sourceNodeKey;
    }

    public String getTargetNodeKey() {
        return targetNodeKey;
    }

    public String getConditionExpression() {
        return conditionExpression;
    }
}
