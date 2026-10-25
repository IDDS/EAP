package com.eap.platform.agentops;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import java.time.Instant;

@Entity
@Table(name = "agentops_signal_sync_state")
public class AgentOpsSignalSyncState {
    @Id
    Long id;
    @Column(name = "last_completed_at")
    Instant lastCompletedAt;
    @Column(name = "last_created_count", nullable = false)
    int lastCreatedCount;

    @Column(name = "approval_cursor", nullable = false)
    long approvalCursor;
    @Column(name = "approval_upper_bound", nullable = false)
    long approvalUpperBound;
    @Column(name = "evaluation_cursor", nullable = false)
    long evaluationCursor;
    @Column(name = "evaluation_upper_bound", nullable = false)
    long evaluationUpperBound;
}
