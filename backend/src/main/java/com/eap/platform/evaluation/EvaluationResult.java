package com.eap.platform.evaluation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "eap_evaluation_results")
public class EvaluationResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "result_key", nullable = false, unique = true, length = 128)
    String resultKey;

    @Column(name = "evaluator_id", nullable = false)
    Long evaluatorId;

    @Column(name = "evaluator_version", nullable = false, length = 64)
    String evaluatorVersion;

    @Column(name = "request_fingerprint", length = 64)
    String requestFingerprint;

    @Column(name = "dataset_id")
    Long datasetId;

    @Column(name = "agent_id")
    Long agentId;

    @Column(name = "trace_id", length = 128)
    String traceId;

    @Column(name = "span_id", length = 128)
    String spanId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    EvaluationLevel level;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    EvaluationStatus status;

    @Column(nullable = false, precision = 8, scale = 6)
    BigDecimal score;

    @Column(nullable = false)
    boolean passed;

    @Column(nullable = false, precision = 8, scale = 6)
    BigDecimal confidence;

    @Column(length = 2048)
    String rationale;

    @Column(name = "input_json", columnDefinition = "text")
    String inputJson;

    @Column(name = "expected_json", columnDefinition = "text")
    String expectedJson;

    @Column(name = "actual_json", columnDefinition = "text")
    String actualJson;

    @Column(name = "evidence_json", columnDefinition = "text")
    String evidenceJson;

    @Column(name = "evaluated_at", nullable = false)
    Instant evaluatedAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        evaluatedAt = evaluatedAt == null ? now : evaluatedAt;
        createdAt = now;
    }

    public Long getId() { return id; }
    public Long getEvaluatorId() { return evaluatorId; }
    public String getEvaluatorVersion() { return evaluatorVersion; }
    public Long getAgentId() { return agentId; }
    public String getResultKey() { return resultKey; }
    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public EvaluationLevel getLevel() { return level; }
    public EvaluationStatus getStatus() { return status; }
    public BigDecimal getScore() { return score; }
    public boolean isPassed() { return passed; }
    public BigDecimal getConfidence() { return confidence; }
    public String getRationale() { return rationale; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
}
