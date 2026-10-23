package com.eap.platform.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Component;
import java.util.Locale;

@Component
public class SensitiveContentSanitizer {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public SanitizedContent sanitize(String content) {
        String value = content == null ? "" : content;
        String normalized = value.toLowerCase(Locale.ROOT);
        boolean secret = containsAny(normalized, "company_secret", "confidential", "restricted", "private_key", "机密", "秘密");
        boolean internal = secret || containsAny(normalized, "internal", "intranet", "employee", "内部", "员工");
        String redacted = redact(value, 0);
        boolean redactedApplied = !redacted.equals(value);
        if (secret || internal) {
            return new SanitizedContent(null, secret ? "SECRET" : "INTERNAL",
                    secret ? "COMPANY_SECRET" : "INTERNAL_TEXT", "PASSIVE_WITHHELD", true);
        }
        if (redactedApplied) return new SanitizedContent(redacted, "INTERNAL", "SENSITIVE_TEXT", "PASSIVE_REDACT", true);
        // PUBLIC remains a legacy heuristic classification, not a confidentiality attestation.
        return new SanitizedContent(value, "PUBLIC", "GENERAL_TEXT", "PASSIVE_OBSERVED", false);
    }
    private String redact(String value, int depth) {
        if (depth > 32) return "[CONTENT_WITHHELD]";
        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")) {
            try {
                JsonNode parsed = JSON.readTree(value);
                JsonNode safe = redactNode(parsed, depth + 1);
                // Preserve formatting and classification for unchanged safe JSON.
                if (!safe.equals(parsed)) return JSON.writeValueAsString(safe);
                return value;
            } catch (Exception ignored) {
                // Malformed structured input cannot safely fall back to a permissive text rule.
                return "[CONTENT_WITHHELD]";
            }
        }
        return value
                .replaceAll("(?i)(api[_-]?key|access[_-]?token|token|secret|password)[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)", "[REDACTED]")
                .replaceAll("(?i)bearer\\s+[a-z0-9._-]+", "[REDACTED]")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[REDACTED_EMAIL]")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[REDACTED_PHONE]");
    }
    private JsonNode redactNode(JsonNode value, int depth) {
        if (depth > 32) return TextNode.valueOf("[CONTENT_WITHHELD]");
        if (value.isObject()) {
            var result = JSON.createObjectNode();
            value.fields().forEachRemaining(entry -> {
                String key = entry.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                result.set(entry.getKey(), containsAny(key, "password", "passwd", "secret", "token", "apikey", "authorization", "privatekey", "cookie", "credential")
                        ? TextNode.valueOf("[REDACTED]") : redactNode(entry.getValue(), depth + 1));
            });
            return result;
        }
        if (value.isArray()) {
            var result = JSON.createArrayNode();
            value.forEach(item -> result.add(redactNode(item, depth + 1)));
            return result;
        }
        if (value.isTextual()) return TextNode.valueOf(redact(value.asText(), depth + 1));
        return value;
    }
    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }
    public record SanitizedContent(String safeContent, String sensitivity, String dataCategory,
                                   String governanceAction, boolean redactionApplied) { }
}
