package com.eap.platform.management;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "management_value_connectors")
public class ManagementValueConnector {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "connector_key", nullable = false, unique = true, length = 128)
    String connectorKey;
    @Column(nullable = false, length = 256)
    String name;
    @Column(name = "system_type", nullable = false, length = 64)
    String systemType;
    @Column(name = "source_system", nullable = false, length = 128)
    String sourceSystem;
    @Column(name = "endpoint_url", nullable = false, length = 2048)
    String endpointUrl;
    @Column(nullable = false)
    Boolean enabled = true;
    @Column(name = "sync_interval_minutes", nullable = false)
    Integer syncIntervalMinutes = 60;
    @Column(name = "auth_type", nullable = false, length = 32)
    String authType = "NONE";
    @Column(name = "credential_env_key", length = 128)
    String credentialEnvKey;
    @Column(name = "auth_header", length = 128)
    String authHeader;
    @Column(name = "auth_prefix", length = 64)
    String authPrefix;
    @Column(name = "records_path", length = 256)
    String recordsPath;
    @Column(name = "record_key_path", nullable = false, length = 256)
    String recordKeyPath;
    @Column(name = "observed_at_path", length = 256)
    String observedAtPath;
    @Column(name = "value_type_path", length = 256)
    String valueTypePath;
    @Column(name = "fixed_value_type", nullable = false, length = 128)
    String fixedValueType = "OTHER";
    @Column(name = "description_path", length = 256)
    String descriptionPath;
    @Column(name = "benefit_money_path", nullable = false, length = 256)
    String benefitMoneyPath;
    @Column(name = "currency_path", length = 256)
    String currencyPath;
    @Column(name = "fixed_currency", nullable = false, length = 16)
    String fixedCurrency = "USD";
    @Column(name = "metric_name_path", length = 256)
    String metricNamePath;
    @Column(name = "fixed_metric_name", length = 128)
    String fixedMetricName;
    @Column(name = "metric_unit_path", length = 256)
    String metricUnitPath;
    @Column(name = "fixed_metric_unit", length = 32)
    String fixedMetricUnit;
    @Column(name = "baseline_value_path", length = 256)
    String baselineValuePath;
    @Column(name = "current_value_path", length = 256)
    String currentValuePath;
    @Column(name = "improvement_direction", nullable = false, length = 32)
    String improvementDirection = "HIGHER_IS_BETTER";
    @Column(name = "agent_id_path", length = 256)
    String agentIdPath;
    @Column(name = "default_agent_id")
    Long defaultAgentId;
    @Column(name = "endpoint_key_path", length = 256)
    String endpointKeyPath;
    @Column(name = "task_id_path", length = 256)
    String taskIdPath;
    @Column(name = "default_endpoint_key", length = 128)
    String defaultEndpointKey;
    @Column(name = "validated_by", nullable = false, length = 128)
    String validatedBy;
    @Column(nullable = false, precision = 5, scale = 4)
    BigDecimal confidence = new BigDecimal("0.9000");
    @Column(name = "measurement_window_start_path", length = 256)
    String measurementWindowStartPath;
    @Column(name = "measurement_window_end_path", length = 256)
    String measurementWindowEndPath;
    @Column(name = "incremental_parameter", length = 128)
    String incrementalParameter;
    @Column(name = "max_records", nullable = false)
    Integer maxRecords = 500;
    @Column(nullable = false, length = 32)
    String status = "NEVER_SYNCED";
    @Column(name = "last_sync_started_at")
    Instant lastSyncStartedAt;
    @Column(name = "last_sync_completed_at")
    Instant lastSyncCompletedAt;
    @Column(name = "last_success_at")
    Instant lastSuccessAt;
    @Column(name = "last_imported_count", nullable = false)
    Integer lastImportedCount = 0;
    @Column(name = "last_skipped_count", nullable = false)
    Integer lastSkippedCount = 0;
    @Column(name = "last_error", length = 1024)
    String lastError;
    @Column(name = "created_at", nullable = false)
    Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getConnectorKey() { return connectorKey; }
    public String getName() { return name; }
    public String getSourceSystem() { return sourceSystem; }
    public Boolean getEnabled() { return enabled; }
    public String getStatus() { return status; }
    public String getLastError() { return lastError; }
    public Long getDefaultAgentId() { return defaultAgentId; }
    public String getDefaultEndpointKey() { return defaultEndpointKey; }
    public String getTaskIdPath() { return taskIdPath; }
    public Instant getLastSyncCompletedAt() { return lastSyncCompletedAt; }
}
