package com.eap.platform.evaluation;

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

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "eap_evaluators")
public class EvaluatorDefinition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "evaluator_key", nullable = false, unique = true, length = 128)
    String evaluatorKey;

    @Column(nullable = false, length = 256)
    String name;

    @Column(length = 2048)
    String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluator_type", nullable = false, length = 32)
    EvaluatorType evaluatorType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    EvaluationLevel level;

    @Column(name = "implementation_key", nullable = false, length = 128)
    String implementationKey;

    @Column(nullable = false, length = 64)
    String version;

    @Column(nullable = false, precision = 8, scale = 6)
    BigDecimal threshold = new BigDecimal("0.500000");

    @Column(nullable = false)
    boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getEvaluatorKey() { return evaluatorKey; }
    public String getName() { return name; }
    public String getVersion() { return version; }
}
