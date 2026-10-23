package com.eap.platform.safety;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "safety_artifacts")
public class SafetyArtifact {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "session_key", nullable = false, length = 128)
    String sessionKey;

    @Column(name = "event_id", nullable = false)
    Long eventId;

    @Column(name = "artifact_uri", nullable = false, length = 512)
    String artifactUri;

    @Column(name = "artifact_type", nullable = false, length = 64)
    String artifactType;

    @Column(name = "content_hash", length = 128)
    String contentHash;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}

