package com.eap.platform.discovery;

import com.eap.platform.common.BusinessException;
import com.eap.platform.observability.ContentDirection;
import com.eap.platform.observability.ContentObservationService;
import com.eap.platform.observability.SensitiveContentSanitizer;
import com.eap.platform.telemetry.ModelInvocationStatus;
import com.eap.platform.telemetry.ModelObservationSource;
import com.eap.platform.telemetry.ModelTelemetryDtos;
import com.eap.platform.telemetry.ModelTelemetryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;

@Service
public class BusinessContentVaultService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final BusinessContentRecordRepository repository;
    private final DiscoveryAgentV1Service agentService;
    private final DiscoveryAgentV1Properties properties;
    private final SensitiveContentSanitizer sanitizer;
    private final ContentObservationService observationService;
    private final ModelTelemetryService telemetryService;
    private final ObjectMapper objectMapper;

    public BusinessContentVaultService(
            BusinessContentRecordRepository repository,
            DiscoveryAgentV1Service agentService,
            DiscoveryAgentV1Properties properties,
            SensitiveContentSanitizer sanitizer,
            ContentObservationService observationService,
            ModelTelemetryService telemetryService,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.agentService = agentService;
        this.properties = properties;
        this.sanitizer = sanitizer;
        this.observationService = observationService;
        this.telemetryService = telemetryService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DiscoveryAgentV1Dtos.ContentCaptureResponse capture(
            String credential,
            DiscoveryAgentV1Dtos.ContentCaptureRequest request
    ) {
        DiscoveryEndpoint endpoint = agentService.authenticateContent(credential);
        if (!agentService.isContentCaptureEnabled(endpoint)) {
            throw new BusinessException("该发现探针尚未启用业务内容采集");
        }
        if (Boolean.TRUE.equals(endpoint.fingerprintReviewRequired)) {
            throw new BusinessException("机器指纹待复核，业务内容采集已暂停");
        }
        BusinessContentRecord existing = repository.findByObservationKey(request.observationKey()).orElse(null);
        if (existing != null) {
            recordModelUsage(existing, request.metadata());
            return response(existing);
        }

        byte[] content = request.content().getBytes(StandardCharsets.UTF_8);
        SensitiveContentSanitizer.SanitizedContent safe = sanitizer.sanitize(request.content());
        BusinessContentRecord record = new BusinessContentRecord();
        record.observationKey = request.observationKey();
        record.tenantId = endpoint.tenantId;
        record.endpointKey = endpoint.endpointKey;
        record.agentId = request.agentId();
        record.taskId = request.taskId();
        record.traceId = normalize(request.traceId(), 128);
        record.spanId = normalize(request.spanId(), 128);
        record.direction = request.direction().toUpperCase();
        record.provider = normalize(request.provider(), 128);
        record.modelName = normalize(request.modelName(), 128);
        record.contentHash = sha256(content);
        record.contentBytes = (long) content.length;
        record.redactedContent = safe.safeContent();
        record.sensitivity = normalize(request.sensitivity() == null ? safe.sensitivity() : request.sensitivity(), 32);
        record.dataCategory = normalize(request.dataCategory() == null ? safe.dataCategory() : request.dataCategory(), 128);
        record.metadataJson = json(request.metadata());
        record.sampled = request.sampled();
        record.observedAt = request.observedAt() == null ? Instant.now() : request.observedAt();

        boolean retainRaw = request.retainRaw() && request.sampled();
        if (retainRaw) {
            Encrypted encrypted = encrypt(endpoint.tenantId, content);
            record.encryptedContent = encrypted.ciphertext();
            record.encryptionIv = encrypted.iv();
            record.encryptionKeyVersion = properties.getContentVaultKeyVersion();
            record.rawRetained = true;
            // Probe timestamps are evidence, not authority to extend raw-content retention.
            record.expiresAt = Instant.now().plus(properties.getRawContentRetentionDays(), ChronoUnit.DAYS);
        }
        BusinessContentRecord saved = repository.save(record);
        observationService.capture(new ContentObservationService.CaptureCommand(
                saved.observationKey, "content-proxy:" + saved.observationKey, saved.traceId, saved.spanId,
                saved.agentId, saved.endpointKey, saved.taskId, saved.provider, saved.modelName,
                direction(saved.direction), request.content(), saved.redactedContent, saved.dataCategory,
                saved.sensitivity, "OBSERVE", saved.observedAt
        ));
        recordModelUsage(saved, request.metadata());
        return response(saved);
    }

    private void recordModelUsage(BusinessContentRecord record, Map<String, Object> metadata) {
        if (!"OUTPUT".equals(record.direction) || metadata == null) return;
        Map<String, Object> usage = map(metadata.get("usage"));
        if (usage.isEmpty()) usage = map(metadata.get("usageMetadata"));
        Integer httpStatus = integer(metadata, "httpStatus", "statusCode");
        boolean failed = "FAILED".equalsIgnoreCase(text(metadata, "outcome"))
                || (httpStatus != null && httpStatus >= 400);
        if (usage.isEmpty() && !failed) return;

        long inputTokens = firstLong(usage,
                "prompt_tokens", "input_tokens", "promptTokenCount", "inputTokenCount");
        long outputTokens = firstLong(usage,
                "completion_tokens", "output_tokens", "candidatesTokenCount", "outputTokenCount");
        long cachedTokens = firstLong(usage,
                "cached_input_tokens", "cache_read_input_tokens", "cachedContentTokenCount");
        cachedTokens = Math.max(cachedTokens, firstLong(map(usage.get("prompt_tokens_details")), "cached_tokens"));
        cachedTokens = Math.max(cachedTokens, firstLong(map(usage.get("input_tokens_details")), "cached_tokens"));
        long reasoningTokens = firstLong(usage, "reasoning_tokens", "thoughtsTokenCount");
        reasoningTokens = Math.max(reasoningTokens,
                firstLong(map(usage.get("completion_tokens_details")), "reasoning_tokens"));
        reasoningTokens = Math.max(reasoningTokens,
                firstLong(map(usage.get("output_tokens_details")), "reasoning_tokens"));

        BigDecimal moneyCost = firstDecimal(metadata, "moneyCost", "totalCost", "total_cost", "cost");
        if (moneyCost == null) moneyCost = firstDecimal(usage, "moneyCost", "totalCost", "total_cost", "cost");
        String currency = firstNonBlank(text(metadata, "currency"), text(usage, "currency"), "USD");
        String errorType = firstNonBlank(text(metadata, "errorType"), failed && httpStatus != null ? "HTTP_" + httpStatus : null);

        telemetryService.recordPassive(new ModelTelemetryDtos.ModelInvocationRequest(
                "content-proxy-" + sha256(record.observationKey.getBytes(StandardCharsets.UTF_8)),
                record.traceId, record.spanId, record.agentId, record.endpointKey, null, record.taskId, null,
                firstNonBlank(record.provider, "unknown"), firstNonBlank(record.modelName, "unknown"),
                failed ? ModelInvocationStatus.FAILED : ModelInvocationStatus.SUCCEEDED,
                inputTokens, outputTokens, cachedTokens, reasoningTokens,
                firstLong(metadata, "latencyMs", "durationMs"), moneyCost, currency,
                boundedInt(firstLong(metadata, "toolCallCount")), boundedInt(firstLong(metadata, "mcpServerCount")),
                errorType, firstNonBlank(text(metadata, "usagePoolKey")), record.observedAt
        ), ModelObservationSource.CONTENT_PROXY);
    }

    private Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) return Map.of();
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (key != null) result.put(String.valueOf(key), item);
        });
        return result;
    }

    private String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private Integer integer(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value instanceof Number number) return number.intValue();
            if (value != null) {
                try {
                    return Integer.parseInt(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    // Try the next provider-specific key.
                }
            }
        }
        return null;
    }

    private long firstLong(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value instanceof Number number) return Math.max(0L, number.longValue());
            if (value != null) {
                try {
                    return Math.max(0L, Long.parseLong(String.valueOf(value)));
                } catch (NumberFormatException ignored) {
                    // Try the next provider-specific key.
                }
            }
        }
        return 0L;
    }

    private BigDecimal firstDecimal(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value == null) continue;
            try {
                BigDecimal decimal = new BigDecimal(String.valueOf(value));
                if (decimal.signum() >= 0) return decimal;
            } catch (NumberFormatException ignored) {
                // Try the next provider-specific key.
            }
        }
        return null;
    }

    private int boundedInt(long value) {
        return (int) Math.min(Math.max(0L, value), 100_000L);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private ContentDirection direction(String value) {
        try {
            return ContentDirection.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("业务内容方向必须是 INPUT、OUTPUT、TOOL_INPUT、TOOL_OUTPUT 或 SYSTEM");
        }
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("业务内容元数据无法序列化");
        }
    }

    private Encrypted encrypt(String tenantId, byte[] content) {
        byte[] master = masterKey();
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(master, "HmacSHA256"));
            byte[] tenantKey = hmac.doFinal(tenantId.getBytes(StandardCharsets.UTF_8));
            byte[] iv = new byte[12];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(tenantKey, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(tenantId.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(content);
            return new Encrypted(Base64.getEncoder().encodeToString(encrypted), Base64.getEncoder().encodeToString(iv));
        } catch (Exception exception) {
            throw new BusinessException("业务内容加密失败");
        }
    }

    private byte[] masterKey() {
        String configured = properties.getContentVaultMasterKey();
        if (configured == null || configured.isBlank()) {
            throw new BusinessException("EAP 尚未配置业务内容库主密钥，不能保留原文");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(configured.trim());
            if (decoded.length < 32) throw new IllegalArgumentException("short key");
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException("业务内容库主密钥必须是至少 32 字节的 Base64 值");
        }
    }

    private DiscoveryAgentV1Dtos.ContentCaptureResponse response(BusinessContentRecord record) {
        return new DiscoveryAgentV1Dtos.ContentCaptureResponse(
                record.id, record.observationKey, record.contentHash,
                Boolean.TRUE.equals(record.rawRetained), record.expiresAt
        );
    }

    private String normalize(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record Encrypted(String ciphertext, String iv) { }
}
