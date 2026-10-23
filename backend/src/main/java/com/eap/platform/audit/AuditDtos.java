package com.eap.platform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

public final class AuditDtos {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private AuditDtos() {
    }

    public record AuditLogResponse(
            Long id,
            String actor,
            String action,
            String resourceType,
            String resourceId,
            String beforeJson,
            String afterJson,
            String result,
            String traceId,
            Instant createdAt
    ) {
    }

    static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.id,
                log.actor,
                log.action,
                log.resourceType,
                log.resourceId,
                normalizeJson(log.beforeJson),
                normalizeJson(log.afterJson),
                log.result,
                log.traceId,
                log.createdAt
        );
    }

    private static String normalizeJson(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            return value;
        }
        try {
            return OBJECT_MAPPER.readValue(value, String.class);
        } catch (JsonProcessingException ignored) {
            return value;
        }
    }
}
