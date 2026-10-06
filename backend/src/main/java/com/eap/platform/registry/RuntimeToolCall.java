package com.eap.platform.registry;

import jakarta.persistence.*;
import java.time.Instant;

/** Only attribution, decisions and digests; never arguments, results or credentials. */
@Entity
@Table(name="runtime_tool_calls",uniqueConstraints=@UniqueConstraint(columnNames={"instance_key","call_id"}))
public class RuntimeToolCall {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) Long id;
    @Column(name="instance_key",nullable=false,length=128) String instanceKey;
    @Column(name="call_id",nullable=false,length=36) String callId;
    @Column(nullable=false,length=36) String generation;
    @Column(name="agent_id",nullable=false) Long agentId;
    @Column(name="endpoint_key",nullable=false,length=128) String endpointKey;
    @Column(name="tool_name",nullable=false,length=96) String toolName;
    @Column(name="tool_kind",nullable=false,length=16) String toolKind;
    @Column(name="trace_id",nullable=false,length=32) String traceId;
    @Column(nullable=false,length=64) String fingerprint;
    @Column(name="decision_key",nullable=false,length=128) String decisionKey;
    @Column(name="safety_decision",nullable=false,length=16) String safetyDecision;
    @Column(name="data_action",nullable=false,length=32) String dataAction;
    @Column(name="reason_code",nullable=false,length=128) String reasonCode;
    @Column(nullable=false,length=24) String state;
    @Column(name="created_at",nullable=false) Instant createdAt;
    @Column(name="expires_at",nullable=false) Instant expiresAt;
    @Column(name="claimed_at") Instant claimedAt;
    @Column(name="completed_at") Instant completedAt;
    @Column(name="duration_ms") Long durationMs;
    @Column(name="policy_revision") Long policyRevision;
    @Column(name="output_digest",length=64) String outputDigest;
    @Column(name="evidence_source",nullable=false,length=32) String evidenceSource="RUNTIME_REPORTED";
    @Column(name="effect_id",length=36) String effectId;
    @Column(name="root_grant_id",length=36) String rootGrantId;
}
