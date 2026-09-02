package com.eap.platform.observability;

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
@Table(name = "tool_invocation_events")
public class ToolInvocationEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "invocation_key", nullable = false, unique = true, length = 128)
    String invocationKey;

    @Column(name = "trace_id", length = 128)
    String traceId;

    @Column(name = "span_id", length = 128)
    String spanId;

    @Column(name = "parent_span_id", length = 128)
    String parentSpanId;

    @Column(name = "agent_id")
    Long agentId;

    @Column(name = "endpoint_key", length = 128)
    String endpointKey;

    @Column(name = "runtime_instance_key", length = 128)
    String runtimeInstanceKey;

    @Column(name = "task_id")
    Long taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "observation_source", nullable = false, length = 32)
    ToolInvocationSource observationSource;

    @Enumerated(EnumType.STRING)
    @Column(name = "tool_kind", nullable = false, length = 32)
    ToolInvocationKind toolKind;

    @Column(name = "tool_name", nullable = false, length = 256)
    String toolName;

    @Column(name = "mcp_server_name", length = 256)
    String mcpServerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    ToolInvocationStatus status;

    @Column(name = "duration_ms", nullable = false)
    Long durationMs = 0L;

    @Column(name = "input_observation_key", length = 128)
    String inputObservationKey;

    @Column(name = "output_observation_key", length = 128)
    String outputObservationKey;

    @Column(name = "attributes_json", columnDefinition = "text")
    String attributesJson;

    @Column(name = "observed_at", nullable = false)
    Instant observedAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        observedAt = observedAt == null ? now : observedAt;
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getAgentId() { return agentId; }
    public Long getId() { return id; }
    public String getInvocationKey() { return invocationKey; }
    public String getTraceId() { return traceId; }
    public String getSpanId() { return spanId; }
    public String getToolName() { return toolName; }
    public String getAttributesJson() { return attributesJson; }
    public Long getDurationMs() { return durationMs; }
    public String getEndpointKey() { return endpointKey; }
    public ToolInvocationStatus getStatus() { return status; }
    public Instant getObservedAt() { return observedAt; }
}
