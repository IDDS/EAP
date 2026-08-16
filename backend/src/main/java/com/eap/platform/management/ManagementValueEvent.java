package com.eap.platform.management;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "management_value_events")
public class ManagementValueEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "event_key", nullable = false, unique = true, length = 128)
    String eventKey;

    @Column(name = "agent_id")
    Long agentId;

    @Column(name = "endpoint_key", length = 128)
    String endpointKey;

    @Column(name = "task_id")
    Long taskId;

    @Column(name = "knowledge_interaction_id")
    Long knowledgeInteractionId;

    @Column(name = "value_type", nullable = false, length = 128)
    String valueType;

    @Column(length = 2048)
    String description;

    @Column(name = "validated_by", nullable = false, length = 128)
    String validatedBy;

    @Column(name = "benefit_money", nullable = false, precision = 18, scale = 6)
    BigDecimal benefitMoney = BigDecimal.ZERO;

    @Column(nullable = false, length = 16)
    String currency = "USD";

    @Column(name = "metric_name", length = 128)
    String metricName;

    @Column(name = "metric_unit", length = 32)
    String metricUnit;

    @Column(name = "baseline_value", precision = 18, scale = 6)
    BigDecimal baselineValue;

    @Column(name = "current_value", precision = 18, scale = 6)
    BigDecimal currentValue;

    @Column(name = "improvement_direction", length = 32)
    String improvementDirection;

    @Column(name = "source_type", nullable = false, length = 32)
    String sourceType = "MANUAL";

    @Column(name = "source_system", length = 128)
    String sourceSystem;

    @Column(name = "source_record_key", length = 128)
    String sourceRecordKey;

    @Column(name = "confidence", nullable = false, precision = 5, scale = 4)
    BigDecimal confidence = BigDecimal.ONE;

    @Column(name = "measurement_window_start")
    Instant measurementWindowStart;

    @Column(name = "measurement_window_end")
    Instant measurementWindowEnd;

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
