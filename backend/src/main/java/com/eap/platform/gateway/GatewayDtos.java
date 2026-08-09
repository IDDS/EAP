package com.eap.platform.gateway;

import com.eap.platform.governance.GovernanceDataSensitivity;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

public final class GatewayDtos {
    private GatewayDtos() {}

    public record GatewayStatus(
            boolean enabled,
            boolean configured,
            boolean authenticationConfigured,
            String provider,
            String classifier,
            long readTimeoutMs,
            long maxResponseBytes,
            boolean pricingConfigured,
            Instant checkedAt
    ) {}

    public record GatewayError(String error, String code, String traceId) {}

    public record ClassificationHints(
            String dataCategory,
            GovernanceDataSensitivity sensitivity,
            Boolean internalData,
            Boolean companySecret,
            List<String> detectedTypes
    ) {
        public static ClassificationHints empty() {
            return new ClassificationHints(null, null, null, null, List.of());
        }
    }

    public record CompletionUsage(long promptTokens, long completionTokens, long totalTokens) {
        static CompletionUsage from(JsonNode root) {
            JsonNode usage = root == null ? null : root.path("usage");
            if (usage == null || usage.isMissingNode() || !usage.isObject()) {
                return new CompletionUsage(0, 0, 0);
            }
            long prompt = number(usage, "prompt_tokens", "input_tokens");
            long completion = number(usage, "completion_tokens", "output_tokens");
            long total = number(usage, "total_tokens", "totalTokens");
            return new CompletionUsage(prompt, completion, total > 0 ? total : prompt + completion);
        }

        private static long number(JsonNode node, String first, String second) {
            JsonNode value = node.get(first);
            if (value == null) value = node.get(second);
            return value == null || !value.canConvertToLong() ? 0 : value.asLong();
        }
    }
}
