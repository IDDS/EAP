package com.eap.platform.telemetry;

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
@Table(name = "model_invocation_events")
public class ModelInvocationEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "event_key", nullable = false, unique = true, length = 128)
    String eventKey;

    @Column(name = "trace_id", length = 128)
    String traceId;

    @Column(name = "span_id", length = 128)
    String spanId;

    @Column(name = "agent_id")
    Long agentId;

    @Column(name = "endpoint_key", length = 128)
    String endpointKey;

    @Column(name = "runtime_instance_key", length = 128)
    String runtimeInstanceKey;

    @Column(name = "task_id")
    Long taskId;

    @Column(name = "governance_decision_key", length = 128)
    String governanceDecisionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "observation_source", nullable = false, length = 32)
    ModelObservationSource observationSource = ModelObservationSource.TELEMETRY_API;

    @Column(name = "governance_covered", nullable = false)
    Boolean governanceCovered = false;

    @Column(name = "usage_pool_key", length = 128)
    String usagePoolKey;

    @Column(nullable = false, length = 128)
    String provider;

    @Column(name = "model_name", nullable = false, length = 128)
    String modelName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    ModelInvocationStatus status;

    @Column(name = "input_tokens", nullable = false)
    Long inputTokens = 0L;

    @Column(name = "output_tokens", nullable = false)
    Long outputTokens = 0L;

    @Column(name = "cached_input_tokens", nullable = false)
    Long cachedInputTokens = 0L;

    @Column(name = "reasoning_tokens", nullable = false)
    Long reasoningTokens = 0L;

    @Column(name = "latency_ms", nullable = false)
    Long latencyMs = 0L;

    @Column(name = "money_cost", nullable = false, precision = 18, scale = 6)
    BigDecimal moneyCost = BigDecimal.ZERO;

    @Column(nullable = false, length = 16)
    String currency = "USD";

    @Column(name = "tool_call_count", nullable = false)
    Integer toolCallCount = 0;

    @Column(name = "mcp_server_count", nullable = false)
    Integer mcpServerCount = 0;

    @Column(name = "error_type", length = 128)
    String errorType;

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
    public ModelInvocationStatus getStatus() { return status; }
    public Long getLatencyMs() { return latencyMs; }
    public Instant getObservedAt() { return observedAt; }
}
