package com.eap.platform.agentops;

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
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "agent_work_items")
public class AgentWorkItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Version
    @Column(name = "row_version", nullable = false)
    long rowVersion;

    @Column(name = "item_key", nullable = false, unique = true, length = 160)
    String itemKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 64)
    WorkItemType itemType;

    @Column(nullable = false, length = 256)
    String title;

    @Column(length = 2048)
    String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    WorkItemSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    WorkItemStatus status = WorkItemStatus.OPEN;

    @Column(name = "agent_id")
    Long agentId;

    @Column(name = "endpoint_key", length = 128)
    String endpointKey;

    @Column(name = "runtime_instance_key", length = 128)
    String runtimeInstanceKey;

    @Column(name = "source_type", nullable = false, length = 64)
    String sourceType;

    @Column(name = "source_id", length = 160)
    String sourceId;

    @Column(name = "organization_scope", length = 256)
    String organizationScope;

    @Column(length = 128)
    String assignee;

    @Column(name = "assigned_team", length = 128)
    String assignedTeam;

    @Enumerated(EnumType.STRING)
    @Column(name = "evidence_basis", nullable = false, length = 32)
    EvidenceBasis evidenceBasis = EvidenceBasis.DERIVED;

    @Column(name = "evidence_json", columnDefinition = "text")
    String evidenceJson;

    @Column(name = "occurrence_count", nullable = false)
    Integer occurrenceCount = 1;

    @Column(name = "first_detected_at", nullable = false)
    Instant firstDetectedAt;

    @Column(name = "last_detected_at", nullable = false)
    Instant lastDetectedAt;

    @Column(name = "sla_due_at")
    Instant slaDueAt;

    @Column(length = 2048)
    String resolution;

    @Column(name = "resolution_evidence_json", columnDefinition = "text")
    String resolutionEvidenceJson;

    @Column(name = "resolved_at")
    Instant resolvedAt;

    @Column(name = "closed_at")
    Instant closedAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        firstDetectedAt = firstDetectedAt == null ? now : firstDetectedAt;
        lastDetectedAt = lastDetectedAt == null ? firstDetectedAt : lastDetectedAt;
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
