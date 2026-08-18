package com.eap.platform.governance;

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
@Table(name = "governance_data_events")
public class GovernanceDataEvent {
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

    @Column(name = "trace_id", length = 128)
    String traceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    GovernanceDataDirection direction;

    @Column(name = "data_category", nullable = false, length = 128)
    String dataCategory;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    GovernanceDataSensitivity sensitivity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    GovernanceDataAction action;

    @Column(name = "internal_data", nullable = false)
    Boolean internalData = false;

    @Column(name = "company_secret", nullable = false)
    Boolean companySecret = false;

    @Column(name = "token_count", nullable = false)
    Long tokenCount = 0L;

    @Column(name = "payload_bytes", nullable = false)
    Long payloadBytes = 0L;

    @Column(name = "policy_id")
    Long policyId;

    @Column(name = "policy_rule", length = 256)
    String policyRule;

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
    public GovernanceDataDirection getDirection() { return direction; }
    public String getDataCategory() { return dataCategory; }
    public GovernanceDataSensitivity getSensitivity() { return sensitivity; }
    public GovernanceDataAction getAction() { return action; }
    public Instant getObservedAt() { return observedAt; }
    public boolean isInternalData() { return Boolean.TRUE.equals(internalData); }
    public boolean isCompanySecret() { return Boolean.TRUE.equals(companySecret); }
}
