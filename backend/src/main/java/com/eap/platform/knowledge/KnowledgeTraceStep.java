package com.eap.platform.knowledge;

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
@Table(name = "knowledge_trace_steps")
public class KnowledgeTraceStep {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "interaction_id", nullable = false)
    Long interactionId;

    @Column(name = "step_order", nullable = false)
    Integer stepOrder;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type", nullable = false, length = 32)
    KnowledgeStepType stepType;

    @Column(nullable = false, length = 256)
    String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    KnowledgeStepStatus status;

    @Column(name = "source_count", nullable = false)
    Integer sourceCount = 0;

    @Column(name = "input_tokens", nullable = false)
    Long inputTokens = 0L;

    @Column(name = "output_tokens", nullable = false)
    Long outputTokens = 0L;

    @Column(name = "latency_ms", nullable = false)
    Long latencyMs = 0L;

    @Column(name = "refs_json", columnDefinition = "text")
    String refsJson;

    @Column(name = "metadata_json", columnDefinition = "text")
    String metadataJson;

    @Column(name = "observed_at", nullable = false)
    Instant observedAt;

    @PrePersist
    void prePersist() {
        observedAt = observedAt == null ? Instant.now() : observedAt;
    }
}
