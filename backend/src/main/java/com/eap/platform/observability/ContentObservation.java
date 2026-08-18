package com.eap.platform.observability;

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
@Table(name = "content_observations")
public class ContentObservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "observation_key", nullable = false, unique = true, length = 128)
    String observationKey;

    @Column(name = "event_key", length = 128)
    String eventKey;

    @Column(name = "trace_id", length = 128)
    String traceId;

    @Column(name = "span_id", length = 128)
    String spanId;

    @Column(name = "agent_id")
    Long agentId;

    @Column(name = "endpoint_key", length = 128)
    String endpointKey;

    @Column(name = "task_id")
    Long taskId;

    @Column(length = 128)
    String provider;

    @Column(name = "model_name", length = 128)
    String modelName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    ContentDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_mode", nullable = false, length = 32)
    ContentCaptureMode captureMode;

    @Column(name = "content_hash", nullable = false, length = 64)
    String contentHash;

    @Column(name = "content_bytes", nullable = false)
    Long contentBytes = 0L;

    @Column(name = "estimated_tokens", nullable = false)
    Long estimatedTokens = 0L;

    @Column(name = "content_json", columnDefinition = "clob")
    String contentJson;

    @Column(name = "data_category", length = 128)
    String dataCategory;

    @Column(name = "sensitivity", length = 32)
    String sensitivity;

    @Column(name = "governance_action", length = 32)
    String governanceAction;

    @Column(name = "redaction_applied", nullable = false)
    Boolean redactionApplied = false;

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

    public Long getAgentId() { return agentId; }
    public String getEndpointKey() { return endpointKey; }
    public Instant getObservedAt() { return observedAt; }
}
