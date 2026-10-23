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
@Table(name = "trace_span_observations")
public class TraceSpanObservation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "trace_id", nullable = false, length = 128)
    String traceId;

    @Column(name = "span_id", nullable = false, length = 128)
    String spanId;

    @Column(name = "parent_span_id", length = 128)
    String parentSpanId;

    @Column(name = "endpoint_key", nullable = false, length = 128)
    String endpointKey;

    /**
     * Managed agent attribution extracted from OTLP resource/span attributes.
     * It remains nullable because endpoint-only telemetry cannot always identify
     * a managed agent without an explicit identity signal.
     */
    @Column(name = "agent_id")
    Long agentId;

    @Column(name = "span_name", nullable = false, length = 256)
    String spanName;

    @Enumerated(EnumType.STRING)
    @Column(name = "span_kind", nullable = false, length = 32)
    TraceSpanKind spanKind;

    @Column(length = 128)
    String operation;

    @Column(name = "tool_name", length = 256)
    String toolName;

    @Column(name = "mcp_server_name", length = 256)
    String mcpServerName;

    @Column(name = "rag_source", length = 512)
    String ragSource;

    @Column(name = "status", length = 32)
    String status;

    @Column(name = "duration_ms", nullable = false)
    Long durationMs = 0L;

    @Column(name = "attributes_json", columnDefinition = "text")
    String attributesJson;

    /** Producer timestamps only; null for legacy or missing timestamp evidence. */
    @Column(name = "started_at")
    Instant startedAt;

    @Column(name = "ended_at")
    Instant endedAt;

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

    public Long getId() { return id; }
    public Long getAgentId() { return agentId; }
    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getSpanName() { return spanName; }
    public String getParentSpanId() { return parentSpanId; }
    public TraceSpanKind getSpanKind() { return spanKind; }
    public String getStatus() { return status; }
    public Long getDurationMs() { return durationMs; }
    public String getAttributesJson() { return attributesJson; }
    public String getEndpointKey() { return endpointKey; }
    public Instant getObservedAt() { return observedAt; }
}
