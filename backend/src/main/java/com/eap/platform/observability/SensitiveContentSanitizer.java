package com.eap.platform.observability;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class SensitiveContentSanitizer {
    public SanitizedContent sanitize(String content) {
        String value = content == null ? "" : content;
        String normalized = value.toLowerCase(Locale.ROOT);
        boolean secret = containsAny(normalized, "company_secret", "confidential", "restricted", "private_key", "机密", "秘密");
        boolean internal = secret || containsAny(normalized, "internal", "intranet", "employee", "内部", "员工");
        String redacted = value
                .replaceAll("(?i)(api[_-]?key|access[_-]?token|token|secret|password)\\s*[:=]\\s*[^\\s,;\\\"]+", "[REDACTED]")
                .replaceAll("(?i)bearer\\s+[a-z0-9._-]+", "[REDACTED]")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[REDACTED_EMAIL]")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[REDACTED_PHONE]");
        boolean redactedApplied = !redacted.equals(value);
        if (secret || internal) {
            return new SanitizedContent(null, secret ? "SECRET" : "INTERNAL",
                    secret ? "COMPANY_SECRET" : "INTERNAL_TEXT", "PASSIVE_WITHHELD", true);
        }
        if (redactedApplied) {
            return new SanitizedContent(redacted, "INTERNAL", "SENSITIVE_TEXT", "PASSIVE_REDACT", true);
        }
        return new SanitizedContent(value, "PUBLIC", "GENERAL_TEXT", "PASSIVE_OBSERVED", false);
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    public record SanitizedContent(
            String safeContent,
            String sensitivity,
            String dataCategory,
            String governanceAction,
            boolean redactionApplied
    ) {
    }
}
