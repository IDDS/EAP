package com.eap.platform.management;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

public final class ManagementConnectorDtos {
    private ManagementConnectorDtos() { }

    public record ConnectorRequest(
            @NotBlank @Size(max = 128) String connectorKey,
            @NotBlank @Size(max = 256) String name,
            @NotBlank @Size(max = 64) String systemType,
            @NotBlank @Size(max = 128) String sourceSystem,
            @NotBlank @Size(max = 2048) String endpointUrl,
            Boolean enabled,
            @Min(1) @Max(10080) Integer syncIntervalMinutes,
            @Size(max = 32) String authType,
            @Size(max = 128) String credentialEnvKey,
            @Size(max = 128) String authHeader,
            @Size(max = 64) String authPrefix,
            @Size(max = 256) String recordsPath,
            @NotBlank @Size(max = 256) String recordKeyPath,
            @Size(max = 256) String observedAtPath,
            @Size(max = 256) String valueTypePath,
            @Size(max = 128) String fixedValueType,
            @Size(max = 256) String descriptionPath,
            @NotBlank @Size(max = 256) String benefitMoneyPath,
            @Size(max = 256) String currencyPath,
            @Size(max = 16) String fixedCurrency,
            @Size(max = 256) String metricNamePath,
            @Size(max = 128) String fixedMetricName,
            @Size(max = 256) String metricUnitPath,
            @Size(max = 32) String fixedMetricUnit,
            @Size(max = 256) String baselineValuePath,
            @Size(max = 256) String currentValuePath,
            @Size(max = 32) String improvementDirection,
            @Size(max = 256) String agentIdPath,
            Long defaultAgentId,
            @Size(max = 256) String endpointKeyPath,
            @Size(max = 256) String taskIdPath,
            @Size(max = 128) String defaultEndpointKey,
            @NotBlank @Size(max = 128) String validatedBy,
            @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
            @Size(max = 256) String measurementWindowStartPath,
            @Size(max = 256) String measurementWindowEndPath,
            @Size(max = 128) String incrementalParameter,
            @Min(1) @Max(5000) Integer maxRecords
    ) { }

    public record ConnectorResponse(
            Long id,
            String connectorKey,
            String name,
            String systemType,
            String sourceSystem,
            String endpointUrl,
            boolean enabled,
            int syncIntervalMinutes,
            String authType,
            String credentialEnvKey,
            String authHeader,
            String authPrefix,
            String recordsPath,
            String recordKeyPath,
            String observedAtPath,
            String valueTypePath,
            String fixedValueType,
            String descriptionPath,
            String benefitMoneyPath,
            String currencyPath,
            String fixedCurrency,
            String metricNamePath,
            String fixedMetricName,
            String metricUnitPath,
            String fixedMetricUnit,
            String baselineValuePath,
            String currentValuePath,
            String improvementDirection,
            String agentIdPath,
            Long defaultAgentId,
            String endpointKeyPath,
            String taskIdPath,
            String defaultEndpointKey,
            String validatedBy,
            BigDecimal confidence,
            String measurementWindowStartPath,
            String measurementWindowEndPath,
            String incrementalParameter,
            int maxRecords,
            String status,
            Instant lastSyncStartedAt,
            Instant lastSyncCompletedAt,
            Instant lastSuccessAt,
            int lastImportedCount,
            int lastSkippedCount,
            String lastError,
            Instant createdAt,
            Instant updatedAt
    ) { }

    public record SyncResult(
            Long connectorId,
            String connectorKey,
            boolean success,
            int fetchedRecords,
            int importedRecords,
            int skippedRecords,
            int failedRecords,
            String message,
            Instant startedAt,
            Instant completedAt
    ) { }

    static ConnectorResponse from(ManagementValueConnector connector) {
        return new ConnectorResponse(
                connector.id, connector.connectorKey, connector.name, connector.systemType,
                connector.sourceSystem, connector.endpointUrl, Boolean.TRUE.equals(connector.enabled),
                connector.syncIntervalMinutes, connector.authType, connector.credentialEnvKey,
                connector.authHeader, connector.authPrefix, connector.recordsPath, connector.recordKeyPath,
                connector.observedAtPath, connector.valueTypePath, connector.fixedValueType,
                connector.descriptionPath, connector.benefitMoneyPath, connector.currencyPath,
                connector.fixedCurrency, connector.metricNamePath, connector.fixedMetricName,
                connector.metricUnitPath, connector.fixedMetricUnit, connector.baselineValuePath,
                connector.currentValuePath, connector.improvementDirection, connector.agentIdPath,
                connector.defaultAgentId, connector.endpointKeyPath, connector.taskIdPath, connector.defaultEndpointKey,
                connector.validatedBy, connector.confidence, connector.measurementWindowStartPath,
                connector.measurementWindowEndPath, connector.incrementalParameter, connector.maxRecords,
                connector.status, connector.lastSyncStartedAt, connector.lastSyncCompletedAt,
                connector.lastSuccessAt, connector.lastImportedCount, connector.lastSkippedCount,
                connector.lastError, connector.createdAt, connector.updatedAt
        );
    }
}
