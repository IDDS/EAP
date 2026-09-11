package com.eap.platform.evaluation;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "eap_evaluation_scan_state")
public class EvaluationScanState {
    @Id Long id;
    @Column(name = "tool_cursor", nullable = false) long toolCursor;
    @Column(name = "tool_upper", nullable = false) long toolUpper;
    @Column(name = "span_cursor", nullable = false) long spanCursor;
    @Column(name = "span_upper", nullable = false) long spanUpper;
    @Column(name = "rag_cursor", nullable = false) long ragCursor;
    @Column(name = "rag_upper", nullable = false) long ragUpper;
    @Column(name = "lease_owner", length = 64) String leaseOwner;
    @Column(name = "lease_until") Instant leaseUntil;
    @Column(name = "updated_at") Instant updatedAt;
}
