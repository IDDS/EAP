package com.eap.platform.discovery;

import com.eap.platform.agent.RiskLevel;
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
@Table(name = "discovered_agents")
public class DiscoveredAgent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(nullable = false, unique = true, length = 64)
    String fingerprint;

    @Column(nullable = false, length = 256)
    String name;

    @Column(nullable = false, length = 128)
    String vendor;

    @Column(nullable = false, length = 128)
    String product;

    @Column(length = 64)
    String version;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 32)
    DiscoveryAssetType assetType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    DiscoveryAssetStatus status = DiscoveryAssetStatus.DISCOVERED;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 32)
    RiskLevel riskLevel = RiskLevel.LOW;

    @Column(nullable = false)
    Integer confidence;

    @Column(name = "source_types", nullable = false, length = 256)
    String sourceTypes;

    @Column(name = "host_name", length = 256)
    String hostName;

    @Column(name = "account_name", length = 256)
    String accountName;

    @Column(name = "process_name", length = 256)
    String processName;

    @Column(name = "executable_path", length = 1024)
    String executablePath;

    @Column(name = "auto_approve")
    Boolean autoApprove;

    @Column(name = "trusted_process")
    Boolean trustedProcess;

    @Column(name = "mcp_server_count", nullable = false)
    Integer mcpServerCount = 0;

    @Column(name = "evidence_count", nullable = false)
    Integer evidenceCount = 0;

    @Column(name = "managed_agent_id")
    Long managedAgentId;

    @Column(name = "availability_status", nullable = false, length = 32)
    String availabilityStatus = "ONLINE";

    @Column(name = "last_endpoint_key", length = 128)
    String lastEndpointKey;

    @Column(name = "missing_snapshot_count", nullable = false)
    Integer missingSnapshotCount = 0;

    @Column(name = "first_seen_at", nullable = false)
    Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    Instant lastSeenAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        firstSeenAt = firstSeenAt == null ? now : firstSeenAt;
        lastSeenAt = lastSeenAt == null ? now : lastSeenAt;
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getProduct() { return product; }
    public DiscoveryAssetStatus getStatus() { return status; }
    public RiskLevel getRiskLevel() { return riskLevel; }
    public Integer getConfidence() { return confidence; }
    public Long getManagedAgentId() { return managedAgentId; }
    public String getAvailabilityStatus() { return availabilityStatus; }
    public String getLastEndpointKey() { return lastEndpointKey; }
    public Instant getLastSeenAt() { return lastSeenAt; }
}
