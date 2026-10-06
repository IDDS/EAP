package com.eap.platform.registry;

import jakarta.persistence.*;
import java.time.Instant;

/** Shared by every runtime of an agent; never reset by credential rotation or restore. */
@Entity
@Table(name="runtime_tool_policies")
public class RuntimeToolPolicy {
    @Id @Column(name="agent_id") Long agentId;
    @Column(nullable=false) long revision;
    @Column(nullable=false) boolean enabled;
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name="allowed_tools_json",nullable=false,columnDefinition="json") String allowedToolsJson;
    @Column(name="claim_limit",nullable=false) long claimLimit;
    @Column(name="claimed_count",nullable=false) long claimedCount;
    @Column(name="updated_at",nullable=false) Instant updatedAt;
}
