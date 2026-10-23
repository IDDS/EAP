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
@Table(name = "agent_teams")
public class AgentTeam {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, unique = true, length = 64)
    String code;

    @Column(nullable = false, length = 128)
    String name;

    @Column(length = 512)
    String objective;

    @Column(name = "coordination_mode", nullable = false, length = 32)
    String coordinationMode;

    @Column(name = "workspace_policy", nullable = false, length = 128)
    String workspacePolicy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    TeamStatus status = TeamStatus.DRAFT;

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

