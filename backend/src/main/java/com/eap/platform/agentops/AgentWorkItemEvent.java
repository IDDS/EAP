package com.eap.platform.agentops;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "agent_work_item_events")
public class AgentWorkItemEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "work_item_id", nullable = false)
    Long workItemId;

    @Column(name = "event_type", nullable = false, length = 64)
    String eventType;

    @Column(nullable = false, length = 128)
    String actor;

    @Column(name = "from_status", length = 32)
    String fromStatus;

    @Column(name = "to_status", length = 32)
    String toStatus;

    @Column(length = 2048)
    String note;

    @Column(name = "evidence_json", columnDefinition = "text")
    String evidenceJson;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
