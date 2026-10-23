package com.eap.platform.telemetry;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ProviderUsageDtos {
    private ProviderUsageDtos() {
    }

    public record SnapshotRequest(
            @NotBlank @Size(max = 128) String snapshotKey,
            @NotBlank @Size(max = 128) String usagePoolKey,
            @NotBlank @Size(max = 128) String provider,
            @Size(max = 128) String accountLabel,
            @NotNull Instant windowStart,
            @NotNull Instant windowEnd,
            @Min(0) long inputTokens,
            @Min(0) long outputTokens,
            @Min(0) long cachedInputTokens,
            @Min(0) long reasoningTokens,
            @DecimalMin("0.0") @Digits(integer = 12, fraction = 6) BigDecimal moneyCost,
            @Size(max = 16) String currency,
            @NotNull ProviderUsageSource source,
            Instant observedAt
    ) {
    }

    public record SnapshotResponse(
            Long id,
            String snapshotKey,
            String usagePoolKey,
            String provider,
            String accountLabel,
            Instant windowStart,
            Instant windowEnd,
            long inputTokens,
            long outputTokens,
            long cachedInputTokens,
            long reasoningTokens,
            long totalTokens,
            BigDecimal moneyCost,
            String currency,
            ProviderUsageSource source,
            Instant observedAt,
            Instant createdAt
    ) {
    }

    public record UsagePoolReconciliation(
            String usagePoolKey,
            String provider,
            String accountLabel,
            String currency,
            int snapshotCount,
            long attributedCalls,
            long attributedAgents,
            long providerInputTokens,
            long providerOutputTokens,
            long providerTotalTokens,
            BigDecimal providerMoneyCost,
            long attributedInputTokens,
            long attributedOutputTokens,
            long attributedTotalTokens,
            BigDecimal attributedMoneyCost,
            long unallocatedTokens,
            BigDecimal unallocatedMoneyCost,
            long overAttributedTokens,
            BigDecimal overAttributedMoneyCost,
            double tokenCoverageRate,
            double costCoverageRate,
            Instant providerWindowStart,
            Instant providerWindowEnd,
            Instant lastSnapshotAt
    ) {
    }

    public record ReconciliationSummary(
            int windowHours,
            Instant windowStart,
            Instant windowEnd,
            int snapshotCount,
            int usagePoolCount,
            long unmappedTelemetryCalls,
            long unmappedTelemetryTokens,
            BigDecimal unmappedTelemetryMoneyCost,
            List<UsagePoolReconciliation> pools
    ) {
    }

    static SnapshotResponse from(ProviderUsageSnapshot snapshot) {
        return new SnapshotResponse(
                snapshot.id, snapshot.snapshotKey, snapshot.usagePoolKey, snapshot.provider, snapshot.accountLabel,
                snapshot.windowStart, snapshot.windowEnd, snapshot.inputTokens, snapshot.outputTokens,
                snapshot.cachedInputTokens, snapshot.reasoningTokens, snapshot.inputTokens + snapshot.outputTokens,
                snapshot.moneyCost, snapshot.currency, snapshot.source, snapshot.observedAt, snapshot.createdAt
        );
    }
}
