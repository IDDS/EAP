package com.eap.platform.safety;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "safety_events")
public class SafetyEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "session_key", nullable = false, length = 128)
    String sessionKey;

    @Column(name = "agent_id")
    Long agentId;

    @Column(name = "parent_agent_id")
    Long parentAgentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    SafetyEventType eventType;

    @Column(nullable = false, length = 128)
    String action;

    @Column(nullable = false, length = 256)
    String target;

    @Column(name = "payload_summary", length = 1024)
    String payloadSummary;

    @Column(name = "payload_json", columnDefinition = "json")
    String payloadJson;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}

