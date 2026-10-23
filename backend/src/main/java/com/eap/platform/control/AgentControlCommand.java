package com.eap.platform.control;

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
@Table(name = "agent_control_commands")
public class AgentControlCommand {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "command_key", nullable = false, unique = true, length = 128)
    String commandKey;

    @Column(name = "agent_id", nullable = false)
    Long agentId;

    @Column(name = "command_type", nullable = false, length = 64)
    String commandType;

    @Column(name = "payload_json", columnDefinition = "json")
    String payloadJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    ControlCommandStatus status = ControlCommandStatus.QUEUED;

    @Column(name = "issued_by", nullable = false, length = 128)
    String issuedBy;

    @Column(name = "dispatch_mode", nullable = false, length = 64)
    String dispatchMode;

    @Column(name = "runtime_instance_key", length = 128)
    String runtimeInstanceKey;

    @Column(name = "attempt_count", nullable = false)
    Integer attemptCount = 0;

    @Column(name = "http_status")
    Integer httpStatus;

    @Column(name = "result_message", length = 512)
    String resultMessage;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @Column(name = "dispatched_at")
    Instant dispatchedAt;

    @Column(name = "acknowledged_at")
    Instant acknowledgedAt;

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
