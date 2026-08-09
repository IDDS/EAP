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
@Table(name = "safety_decision_logs")
public class SafetyDecisionLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "event_id", nullable = false)
    Long eventId;

    @Column(name = "session_key", nullable = false, length = 128)
    String sessionKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    SafetyDecision decision;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    SafetySeverity severity;

    @Column(name = "reason_code", nullable = false, length = 64)
    String reasonCode;

    @Column(nullable = false, length = 1024)
    String reason;

    @Column(name = "control_actions", nullable = false, length = 1024)
    String controlActions;

    @Column(name = "sidecar_mode", nullable = false, length = 32)
    String sidecarMode;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}

