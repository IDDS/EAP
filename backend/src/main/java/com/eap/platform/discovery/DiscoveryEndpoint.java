package com.eap.platform.discovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "discovery_endpoints")
public class DiscoveryEndpoint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "endpoint_key", nullable = false, unique = true, length = 128)
    String endpointKey;

    @Column(name = "host_name", nullable = false, length = 256)
    String hostName;

    @Column(name = "account_name", length = 256)
    String accountName;

    @Column(name = "operating_system", length = 128)
    String operatingSystem;

    @Column(name = "collector_version", length = 64)
    String collectorVersion;

    @Column(name = "installation_id", unique = true, length = 64)
    String installationId;

    @Column(name = "machine_fingerprint", length = 128)
    String machineFingerprint;

    @Column(name = "credential_hash", unique = true, length = 64)
    String credentialHash;

    @Column(name = "credential_hint", length = 32)
    String credentialHint;

    @Column(name = "content_credential_hash", unique = true, length = 64)
    String contentCredentialHash;

    @Column(name = "content_credential_hint", length = 32)
    String contentCredentialHint;

    @Column(name = "tenant_id", nullable = false, length = 64)
    String tenantId = "default";

    @Column(name = "site_name", length = 128)
    String siteName;

    @Column(name = "environment_name", length = 64)
    String environmentName;

    @Column(length = 128)
    String department;

    @Column(name = "labels_json", columnDefinition = "text")
    String labelsJson;

    @Column(length = 32)
    String architecture;

    @Column(name = "capabilities_json", columnDefinition = "text")
    String capabilitiesJson;

    @Column(name = "policy_version", length = 64)
    String policyVersion;

    @Column(name = "rules_version", length = 64)
    String rulesVersion;

    @Column(name = "last_heartbeat_at")
    Instant lastHeartbeatAt;

    @Column(name = "last_scan_at")
    Instant lastScanAt;

    @Column(name = "queue_depth", nullable = false)
    Long queueDepth = 0L;

    @Column(name = "queue_bytes", nullable = false)
    Long queueBytes = 0L;

    @Column(name = "last_error", length = 1024)
    String lastError;

    @Column(name = "fingerprint_review_required", nullable = false)
    Boolean fingerprintReviewRequired = false;

    @Column(name = "content_capture_status", nullable = false, length = 32)
    String contentCaptureStatus = "DISABLED";

    @Column(name = "content_platform_approved_by", length = 128)
    String contentPlatformApprovedBy;

    @Column(name = "content_platform_approved_at")
    Instant contentPlatformApprovedAt;

    @Column(name = "content_data_approved_by", length = 128)
    String contentDataApprovedBy;

    @Column(name = "content_data_approved_at")
    Instant contentDataApprovedAt;

    @Column(name = "uninstalled_at")
    Instant uninstalledAt;

    @Column(nullable = false, length = 32)
    String status = "ACTIVE";

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

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public String getEndpointKey() { return endpointKey; }
    public String getHostName() { return hostName; }
    public String getStatus() { return status; }
    public String getSiteName() { return siteName; }
    public String getEnvironmentName() { return environmentName; }
    public String getDepartment() { return department; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public String getLastError() { return lastError; }
    public boolean isFingerprintReviewRequired() { return Boolean.TRUE.equals(fingerprintReviewRequired); }
}
