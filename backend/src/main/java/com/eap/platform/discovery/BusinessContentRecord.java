package com.eap.platform.discovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "business_content_records")
public class BusinessContentRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "observation_key", nullable = false, unique = true, length = 128)
    String observationKey;

    @Column(name = "tenant_id", nullable = false, length = 64)
    String tenantId;

    @Column(name = "endpoint_key", nullable = false, length = 128)
    String endpointKey;

    @Column(name = "agent_id")
    Long agentId;

    @Column(name = "task_id")
    Long taskId;

    @Column(name = "trace_id", length = 128)
    String traceId;

    @Column(name = "span_id", length = 128)
    String spanId;

    @Column(nullable = false, length = 32)
    String direction;

    @Column(length = 128)
    String provider;

    @Column(name = "model_name", length = 128)
    String modelName;

    @Column(name = "content_hash", nullable = false, length = 64)
    String contentHash;

    @Column(name = "content_bytes", nullable = false)
    Long contentBytes;

    @Column(name = "encrypted_content", columnDefinition = "longtext")
    String encryptedContent;

    @Column(name = "encryption_iv", length = 64)
    String encryptionIv;

    @Column(name = "encryption_key_version", length = 32)
    String encryptionKeyVersion;

    @Column(name = "redacted_content", columnDefinition = "longtext")
    String redactedContent;

    @Column(name = "metadata_json", columnDefinition = "text")
    String metadataJson;

    @Column(name = "projection_status", nullable = false, length = 16)
    String projectionStatus = "PENDING";

    @Column(name = "projection_attempts", nullable = false)
    int projectionAttempts;

    @Column(name = "projection_error", length = 128)
    String projectionError;

    @Column(length = 32)
    String sensitivity;

    @Column(name = "data_category", length = 128)
    String dataCategory;

    @Column(name = "raw_retained", nullable = false)
    Boolean rawRetained = false;

    @Column(nullable = false)
    Boolean sampled = false;

    @Column(name = "expires_at")
    Instant expiresAt;

    @Column(name = "raw_erased_at")
    Instant rawErasedAt;

    @Column(name = "observed_at", nullable = false)
    Instant observedAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        observedAt = observedAt == null ? now : observedAt;
        createdAt = now;
    }
}
