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
@Table(name = "workflow_nodes")
public class WorkflowNode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @jakarta.persistence.Version
    @Column(nullable = false)
    Long version;

    @Column(name = "workflow_id", nullable = false)
    Long workflowId;

    @Column(name = "node_key", nullable = false, length = 64)
    String nodeKey;

    @Column(nullable = false, length = 128)
    String label;

    @Column(name = "node_type", nullable = false, length = 32)
    String nodeType;

    @Column(name = "agent_id")
    Long agentId;

    @Column(length = 128)
    String capability;

    @Column(name = "position_x", nullable = false)
    Integer positionX;

    @Column(name = "position_y", nullable = false)
    Integer positionY;

    @Column(name = "config_json", columnDefinition = "json")
    String configJson;

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

    public String getNodeKey() {
        return nodeKey;
    }

    public String getLabel() {
        return label;
    }

    public String getNodeType() {
        return nodeType;
    }

    public Long getAgentId() {
        return agentId;
    }

    public String getCapability() {
        return capability;
    }
}
