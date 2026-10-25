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
import jakarta.persistence.Version;

import java.time.Instant;

@Entity
@Table(name = "governance_preflight_decisions")
public class GovernancePreflightDecision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Version
    @Column(nullable = false)
    long revision;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR)
    @Column(name = "policy_context_digest", length = 64)
    String policyContextDigest;

    @Column(name = "decision_key", nullable = false, unique = true, length = 128)
    String decisionKey;

    @Column(name = "data_event_id", nullable = false, unique = true)
    Long dataEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    GovernanceDataAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "gateway_directive", nullable = false, length = 32)
    GovernanceGatewayDirective gatewayDirective;

    @Column(name = "request_allowed", nullable = false)
    Boolean requestAllowed = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false, length = 32)
    GovernanceApprovalStatus approvalStatus = GovernanceApprovalStatus.NOT_REQUIRED;

    @Column(name = "approved_by", length = 128)
    String approvedBy;

    @Column(name = "approval_reason", length = 512)
    String approvalReason;

    @Column(name = "approved_at")
    Instant approvedAt;

    @Column(name = "matched_policy_count", nullable = false)
    Integer matchedPolicyCount = 0;

    @Column(name = "matched_policies_json", columnDefinition = "text")
    String matchedPoliciesJson;

    @Column(name = "reason_codes_json", columnDefinition = "text")
    String reasonCodesJson;

    @Column(name = "classifier", nullable = false, length = 128)
    String classifier;

    @Column(name = "provider", length = 128)
    String provider;

    @Column(name = "model_name", length = 128)
    String modelName;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    String requestFingerprint;

    @Column(name = "decided_at", nullable = false)
    Instant decidedAt;

    @PrePersist
    void prePersist() {
        decidedAt = decidedAt == null ? Instant.now() : decidedAt;
    }

    public boolean isRequestAllowed() {
        return Boolean.TRUE.equals(requestAllowed);
    }

    public GovernanceGatewayDirective getGatewayDirective() {
        return gatewayDirective;
    }

    public Long getDataEventId() {
        return dataEventId;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getProvider() {
        return provider;
    }

    public String getModelName() {
        return modelName;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public GovernanceApprovalStatus getApprovalStatus() {
        return approvalStatus == null ? GovernanceApprovalStatus.NOT_REQUIRED : approvalStatus;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public String getApprovalReason() {
        return approvalReason;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public String getDecisionKey() { return decisionKey; }
    public Long getId() { return id; }
}
