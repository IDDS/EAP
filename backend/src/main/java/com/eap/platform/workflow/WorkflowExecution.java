package com.eap.platform.workflow;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "workflow_executions")
public class WorkflowExecution {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
    @Column(name = "workflow_id", nullable = false) Long workflowId;
    @Column(name = "run_key", nullable = false, unique = true, length = 128) String runKey;
    @Column(name = "submission_key", length = 64) String submissionKey;
    @Column(name = "request_fingerprint", length = 64) String requestFingerprint;
    @Column(nullable = false, length = 32) String status;
    @Column(nullable = false, length = 128) String objective;
    @Column(nullable = false, length = 128) String requester;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "text") String snapshotJson;
    @Column(name = "steps_json", nullable = false, columnDefinition = "mediumtext") String stepsJson = "[]";
    @Column(length = 512) String reason;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "started_at") Instant startedAt;
    @Column(name = "completed_at") Instant completedAt;
    @Column(name = "recovery_version", nullable = false) int recoveryVersion;
    @Column(name = "owner_epoch", nullable = false) long ownerEpoch;
    @Column(name = "owner_id", length = 36) String ownerId;
}
