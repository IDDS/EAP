package com.eap.platform.management;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.common.BusinessException;
import com.eap.platform.discovery.DiscoveryEndpointRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ManagementConnectorService {
    private static final Set<String> VALUE_TYPES = Set.of(
            "LABOR_SAVING", "EFFICIENCY_GAIN", "COST_REDUCTION", "REVENUE",
            "LOSS_AVOIDANCE", "QUALITY_IMPROVEMENT", "OTHER"
    );
    private static final Set<String> AUTH_TYPES = Set.of("NONE", "BEARER_ENV", "HEADER_ENV");
    private final ManagementValueConnectorRepository connectorRepository;
    private final ManagementValueEventRepository valueRepository;
    private final AgentRepository agentRepository;
    private final DiscoveryEndpointRepository endpointRepository;
    private final ManagementService managementService;
    private final ManagementConnectorClient client;
    private final Set<Long> runningConnectors = ConcurrentHashMap.newKeySet();

    public ManagementConnectorService(
            ManagementValueConnectorRepository connectorRepository,
            ManagementValueEventRepository valueRepository,
            AgentRepository agentRepository,
            DiscoveryEndpointRepository endpointRepository,
            ManagementService managementService,
            ManagementConnectorClient client
    ) {
        this.connectorRepository = connectorRepository;
        this.valueRepository = valueRepository;
        this.agentRepository = agentRepository;
        this.endpointRepository = endpointRepository;
        this.managementService = managementService;
        this.client = client;
    }

    @Transactional(readOnly = true)
    public List<ManagementConnectorDtos.ConnectorResponse> list() {
        return connectorRepository.findAllByOrderByNameAsc().stream().map(ManagementConnectorDtos::from).toList();
    }

    @Transactional
    public ManagementConnectorDtos.ConnectorResponse create(ManagementConnectorDtos.ConnectorRequest request) {
        String key = normalize(request.connectorKey(), 128);
        if (connectorRepository.findByConnectorKey(key).isPresent()) throw new BusinessException("连接器标识已存在");
        ManagementValueConnector connector = new ManagementValueConnector();
        apply(connector, request);
        return ManagementConnectorDtos.from(connectorRepository.save(connector));
    }

    @Transactional
    public ManagementConnectorDtos.ConnectorResponse update(Long id, ManagementConnectorDtos.ConnectorRequest request) {
        ManagementValueConnector connector = require(id);
        String key = normalize(request.connectorKey(), 128);
        connectorRepository.findByConnectorKey(key)
                .filter(existing -> !existing.id.equals(id))
                .ifPresent(existing -> { throw new BusinessException("连接器标识已存在"); });
        apply(connector, request);
        return ManagementConnectorDtos.from(connectorRepository.save(connector));
    }

    @Transactional
    public ManagementConnectorDtos.ConnectorResponse setEnabled(Long id, boolean enabled) {
        ManagementValueConnector connector = require(id);
        connector.enabled = enabled;
        if (!enabled) connector.status = "DISABLED";
        if (enabled && "DISABLED".equals(connector.status)) connector.status = "NEVER_SYNCED";
        return ManagementConnectorDtos.from(connectorRepository.save(connector));
    }

    public ManagementConnectorDtos.SyncResult sync(Long id) {
        if (!runningConnectors.add(id)) throw new BusinessException("连接器正在同步，请勿重复触发");
        try {
            return syncConnector(id);
        } finally {
            runningConnectors.remove(id);
        }
    }

    private ManagementConnectorDtos.SyncResult syncConnector(Long id) {
        Instant startedAt = Instant.now();
        ManagementValueConnector connector = require(id);
        connector.status = "RUNNING";
        connector.lastSyncStartedAt = startedAt;
        connector.lastError = null;
        connectorRepository.save(connector);
        int fetched = 0;
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        try {
            JsonNode payload = client.fetch(connector);
            List<JsonNode> records = records(payload, connector.recordsPath, connector.maxRecords);
            fetched = records.size();
            for (JsonNode record : records) {
                try {
                    String recordKey = requiredText(record, connector.recordKeyPath, "来源记录标识");
                    if (valueRepository.findBySourceSystemAndSourceRecordKey(connector.sourceSystem, recordKey).isPresent()) {
                        skipped++;
                        continue;
                    }
                    managementService.recordValue(toValueEvent(connector, record, recordKey));
                    imported++;
                } catch (RuntimeException exception) {
                    failed++;
                }
            }
            Instant completedAt = Instant.now();
            connector.status = failed == 0 ? "HEALTHY" : "DEGRADED";
            connector.lastSyncCompletedAt = completedAt;
            if (failed == 0) connector.lastSuccessAt = startedAt;
            connector.lastImportedCount = imported;
            connector.lastSkippedCount = skipped;
            connector.lastError = failed == 0 ? null : failed + " 条记录映射或导入失败，将在下次同步重试";
            connectorRepository.save(connector);
            return new ManagementConnectorDtos.SyncResult(
                    connector.id, connector.connectorKey, failed == 0, fetched, imported, skipped, failed,
                    failed == 0 ? "同步完成" : connector.lastError, startedAt, completedAt
            );
        } catch (RuntimeException exception) {
            Instant completedAt = Instant.now();
            connector.status = "ERROR";
            connector.lastSyncCompletedAt = completedAt;
            connector.lastImportedCount = imported;
            connector.lastSkippedCount = skipped;
            connector.lastError = truncate(exception.getMessage(), 1024, "业务系统同步失败");
            connectorRepository.save(connector);
            return new ManagementConnectorDtos.SyncResult(
                    connector.id, connector.connectorKey, false, fetched, imported, skipped, failed,
                    connector.lastError, startedAt, completedAt
            );
        }
    }

    public void syncDueConnectors() {
        Instant now = Instant.now();
        for (ManagementValueConnector connector : connectorRepository.findByEnabledTrueOrderByNameAsc()) {
            int interval = Math.max(1, connector.syncIntervalMinutes == null ? 60 : connector.syncIntervalMinutes);
            if (connector.lastSyncCompletedAt == null || !connector.lastSyncCompletedAt.plusSeconds(interval * 60L).isAfter(now)) {
                sync(connector.id);
            }
        }
    }

    private ManagementDtos.ValueEventRequest toValueEvent(
            ManagementValueConnector connector,
            JsonNode record,
            String recordKey
    ) {
        Long agentId = firstNonNull(longValue(record, connector.agentIdPath), connector.defaultAgentId);
        String endpointKey = firstNonBlank(text(record, connector.endpointKeyPath), connector.defaultEndpointKey);
        Long taskId = longValue(record, connector.taskIdPath);
        if (agentId == null && blank(endpointKey)) {
            throw new BusinessException("记录未能关联智能体或发现端点");
        }
        String valueType = firstNonBlank(text(record, connector.valueTypePath), connector.fixedValueType, "OTHER").toUpperCase(Locale.ROOT);
        if (!VALUE_TYPES.contains(valueType)) valueType = "OTHER";
        BigDecimal benefit = decimal(record, connector.benefitMoneyPath);
        if (benefit == null || benefit.signum() < 0) throw new BusinessException("价值金额字段缺失或不合法");
        Instant observedAt = instant(record, connector.observedAtPath);
        return new ManagementDtos.ValueEventRequest(
                eventKey(connector.connectorKey, recordKey),
                agentId, endpointKey, taskId, null, valueType,
                truncate(text(record, connector.descriptionPath), 2048, null), connector.validatedBy,
                benefit, firstNonBlank(text(record, connector.currencyPath), connector.fixedCurrency, "USD"),
                observedAt == null ? Instant.now() : observedAt,
                firstNonBlank(text(record, connector.metricNamePath), connector.fixedMetricName),
                firstNonBlank(text(record, connector.metricUnitPath), connector.fixedMetricUnit),
                decimal(record, connector.baselineValuePath), decimal(record, connector.currentValuePath),
                connector.improvementDirection, "BUSINESS_SYSTEM", connector.sourceSystem, recordKey,
                connector.confidence, instant(record, connector.measurementWindowStartPath),
                instant(record, connector.measurementWindowEndPath), List.of()
        );
    }

    private void apply(ManagementValueConnector connector, ManagementConnectorDtos.ConnectorRequest request) {
        connector.connectorKey = normalize(request.connectorKey(), 128);
        if (!connector.connectorKey.matches("[A-Za-z0-9._-]+")) throw new BusinessException("连接器标识只能包含字母、数字、点、下划线或连字符");
        connector.name = normalize(request.name(), 256);
        connector.systemType = normalize(request.systemType(), 64).toUpperCase(Locale.ROOT);
        connector.sourceSystem = normalize(request.sourceSystem(), 128);
        connector.endpointUrl = normalize(request.endpointUrl(), 2048);
        connector.enabled = request.enabled() == null || request.enabled();
        if (!connector.enabled) connector.status = "DISABLED";
        else if ("DISABLED".equals(connector.status)) connector.status = "NEVER_SYNCED";
        connector.syncIntervalMinutes = request.syncIntervalMinutes() == null ? 60 : request.syncIntervalMinutes();
        connector.authType = firstNonBlank(request.authType(), "NONE").toUpperCase(Locale.ROOT);
        if (!AUTH_TYPES.contains(connector.authType)) throw new BusinessException("不支持的连接器认证方式");
        connector.credentialEnvKey = normalize(request.credentialEnvKey(), 128);
        connector.authHeader = normalize(request.authHeader(), 128);
        connector.authPrefix = request.authPrefix() == null ? null : truncate(request.authPrefix(), 64, null);
        if (!"NONE".equals(connector.authType) && blank(connector.credentialEnvKey)) {
            throw new BusinessException("使用认证时必须填写平台登记的凭据引用");
        }
        client.validateConfiguration(connector);
        connector.recordsPath = path(request.recordsPath());
        connector.recordKeyPath = requiredPath(request.recordKeyPath(), "来源记录字段路径");
        connector.observedAtPath = path(request.observedAtPath());
        connector.valueTypePath = path(request.valueTypePath());
        connector.fixedValueType = firstNonBlank(request.fixedValueType(), "OTHER").toUpperCase(Locale.ROOT);
        connector.descriptionPath = path(request.descriptionPath());
        connector.benefitMoneyPath = requiredPath(request.benefitMoneyPath(), "价值金额字段路径");
        connector.currencyPath = path(request.currencyPath());
        connector.fixedCurrency = firstNonBlank(request.fixedCurrency(), "USD").toUpperCase(Locale.ROOT);
        connector.metricNamePath = path(request.metricNamePath());
        connector.fixedMetricName = normalize(request.fixedMetricName(), 128);
        connector.metricUnitPath = path(request.metricUnitPath());
        connector.fixedMetricUnit = normalize(request.fixedMetricUnit(), 32);
        connector.baselineValuePath = path(request.baselineValuePath());
        connector.currentValuePath = path(request.currentValuePath());
        connector.improvementDirection = firstNonBlank(request.improvementDirection(), "HIGHER_IS_BETTER").toUpperCase(Locale.ROOT);
        if (!Set.of("HIGHER_IS_BETTER", "LOWER_IS_BETTER").contains(connector.improvementDirection)) {
            throw new BusinessException("改善方向必须是 HIGHER_IS_BETTER 或 LOWER_IS_BETTER");
        }
        connector.agentIdPath = path(request.agentIdPath());
        connector.defaultAgentId = request.defaultAgentId();
        if (connector.defaultAgentId != null && !agentRepository.existsById(connector.defaultAgentId)) {
            throw new BusinessException("默认智能体不存在");
        }
        connector.endpointKeyPath = path(request.endpointKeyPath());
        connector.taskIdPath = path(request.taskIdPath());
        connector.defaultEndpointKey = normalize(request.defaultEndpointKey(), 128);
        if (!blank(connector.defaultEndpointKey) && endpointRepository.findByEndpointKey(connector.defaultEndpointKey).isEmpty()) {
            throw new BusinessException("默认发现端点不存在");
        }
        if (connector.defaultAgentId == null && blank(connector.agentIdPath)
                && blank(connector.defaultEndpointKey) && blank(connector.endpointKeyPath)) {
            throw new BusinessException("连接器必须配置默认智能体/端点，或配置对应字段路径");
        }
        connector.validatedBy = normalize(request.validatedBy(), 128);
        connector.confidence = request.confidence() == null ? new BigDecimal("0.9000") : request.confidence();
        connector.measurementWindowStartPath = path(request.measurementWindowStartPath());
        connector.measurementWindowEndPath = path(request.measurementWindowEndPath());
        connector.incrementalParameter = normalize(request.incrementalParameter(), 128);
        connector.maxRecords = request.maxRecords() == null ? 500 : request.maxRecords();
    }

    private ManagementValueConnector require(Long id) {
        return connectorRepository.findById(id).orElseThrow(() -> new BusinessException("业务价值连接器不存在"));
    }

    private List<JsonNode> records(JsonNode root, String path, int limit) {
        JsonNode value = node(root, path);
        List<JsonNode> result = new ArrayList<>();
        if (value.isArray()) value.elements().forEachRemaining(item -> { if (result.size() < limit) result.add(item); });
        else if (value.isObject()) result.add(value);
        else throw new BusinessException("记录路径没有指向 JSON 数组或对象");
        return result;
    }

    private JsonNode node(JsonNode root, String path) {
        if (root == null) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        if (blank(path) || "$".equals(path)) return root;
        String normalized = path.trim().replace("$.", "").replace("[", ".").replace("]", "");
        JsonNode current = root;
        for (String part : normalized.split("\\.")) {
            if (part.isBlank()) continue;
            if (current.isArray() && part.matches("\\d+")) current = current.path(Integer.parseInt(part));
            else current = current.path(part);
        }
        return current;
    }

    private String text(JsonNode record, String path) {
        if (blank(path)) return null;
        JsonNode value = node(record, path);
        return value.isValueNode() ? normalize(value.asText(), 2048) : null;
    }

    private String requiredText(JsonNode record, String path, String label) {
        String value = text(record, path);
        if (blank(value)) throw new BusinessException(label + "字段缺失");
        return truncate(value, 128, value);
    }

    private BigDecimal decimal(JsonNode record, String path) {
        if (blank(path)) return null;
        JsonNode value = node(record, path);
        if (!value.isNumber() && !value.isTextual()) return null;
        try { return new BigDecimal(value.asText().replace(",", "").trim()); }
        catch (NumberFormatException exception) { return null; }
    }

    private Long longValue(JsonNode record, String path) {
        BigDecimal value = decimal(record, path);
        if (value == null) return null;
        try { return value.longValueExact(); }
        catch (ArithmeticException exception) { return null; }
    }

    private Instant instant(JsonNode record, String path) {
        if (blank(path)) return null;
        JsonNode value = node(record, path);
        if (value.isNumber()) {
            long epoch = value.asLong();
            return epoch > 10_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
        }
        if (!value.isTextual() || value.asText().isBlank()) return null;
        try { return Instant.parse(value.asText().trim()); }
        catch (Exception ignored) {
            try { return OffsetDateTime.parse(value.asText().trim()).toInstant(); }
            catch (Exception exception) { return null; }
        }
    }

    private String path(String value) { return normalize(value, 256); }
    private String eventKey(String connectorKey, String recordKey) {
        String hash = Integer.toUnsignedString((connectorKey + ":" + recordKey).hashCode(), 36);
        return "connector:" + truncate(connectorKey, 32, connectorKey)
                + ":" + truncate(recordKey, 60, recordKey) + ":" + hash;
    }
    private String requiredPath(String value, String label) {
        String result = path(value);
        if (blank(result)) throw new BusinessException(label + "不能为空");
        return result;
    }
    private String normalize(String value, int max) {
        if (blank(value)) return null;
        return truncate(value.replaceAll("[\\r\\n\\t]", " ").trim(), max, null);
    }
    private String truncate(String value, int max, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.substring(0, Math.min(normalized.length(), max));
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private <T> T firstNonNull(T first, T second) { return first == null ? second : first; }
    private String firstNonBlank(String... values) {
        for (String value : values) if (!blank(value)) return value.trim();
        return null;
    }
}
