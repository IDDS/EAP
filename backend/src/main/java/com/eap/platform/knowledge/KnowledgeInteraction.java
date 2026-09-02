package com.eap.platform.knowledge;

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
@Table(name = "knowledge_interactions")
public class KnowledgeInteraction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "interaction_key", nullable = false, unique = true, length = 128)
    String interactionKey;

    @Column(name = "trace_id", length = 128)
    String traceId;

    @Column(name = "agent_id")
    Long agentId;

    @Column(name = "endpoint_key", length = 128)
    String endpointKey;

    @Column(name = "task_id")
    Long taskId;

    @Column(name = "input_type", nullable = false, length = 128)
    String inputType;

    @Column(name = "input_source_count", nullable = false)
    Integer inputSourceCount = 0;

    @Column(name = "input_token_count", nullable = false)
    Long inputTokenCount = 0L;

    @Column(name = "input_summary", length = 2048)
    String inputSummary;

    @Column(name = "input_refs_json", columnDefinition = "text")
    String inputRefsJson;

    @Column(name = "output_type", length = 128)
    String outputType;

    @Column(name = "output_source_count", nullable = false)
    Integer outputSourceCount = 0;

    @Column(name = "output_token_count", nullable = false)
    Long outputTokenCount = 0L;

    @Column(name = "output_summary", length = 2048)
    String outputSummary;

    @Column(name = "output_refs_json", columnDefinition = "text")
    String outputRefsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    KnowledgeInteractionStatus status = KnowledgeInteractionStatus.RUNNING;

    @Column(nullable = false)
    Boolean adopted = false;

    @Column(nullable = false)
    Boolean reused = false;

    @Column(name = "quality_score")
    Integer qualityScore;

    @Column(name = "process_step_count", nullable = false)
    Integer processStepCount = 0;

    @Column(name = "process_token_count", nullable = false)
    Long processTokenCount = 0L;

    @Column(name = "process_latency_ms", nullable = false)
    Long processLatencyMs = 0L;

    @Column(name = "started_at")
    Instant startedAt;

    @Column(name = "completed_at")
    Instant completedAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        startedAt = startedAt == null ? now : startedAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getAgentId() { return agentId; }
    public String getInteractionKey() { return interactionKey; }
    public String getTraceId() { return traceId; }
    public String getInputType() { return inputType; }
    public String getOutputType() { return outputType; }
    public Integer getInputSourceCount() { return inputSourceCount; }
    public Integer getOutputSourceCount() { return outputSourceCount; }
    public String getInputRefsJson() { return inputRefsJson; }
    public String getOutputRefsJson() { return outputRefsJson; }
    public KnowledgeInteractionStatus getStatus() { return status; }
    public Boolean getAdopted() { return adopted; }
    public Boolean getReused() { return reused; }
    public Integer getQualityScore() { return qualityScore; }
    public Instant getCreatedAt() { return createdAt; }
}
