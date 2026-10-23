package com.eap.platform.telemetry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "provider_usage_snapshots")
public class ProviderUsageSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;

    @Column(name = "snapshot_key", nullable = false, unique = true, length = 128)
    String snapshotKey;

    @Column(name = "usage_pool_key", nullable = false, length = 128)
    String usagePoolKey;

    @Column(nullable = false, length = 128)
    String provider;

    @Column(name = "account_label", length = 128)
    String accountLabel;

    @Column(name = "window_start", nullable = false)
    Instant windowStart;

    @Column(name = "window_end", nullable = false)
    Instant windowEnd;

    @Column(name = "input_tokens", nullable = false)
    Long inputTokens = 0L;

    @Column(name = "output_tokens", nullable = false)
    Long outputTokens = 0L;

    @Column(name = "cached_input_tokens", nullable = false)
    Long cachedInputTokens = 0L;

    @Column(name = "reasoning_tokens", nullable = false)
    Long reasoningTokens = 0L;

    @Column(name = "money_cost", nullable = false, precision = 18, scale = 6)
    BigDecimal moneyCost = BigDecimal.ZERO;

    @Column(nullable = false, length = 16)
    String currency = "USD";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    ProviderUsageSource source;

    @Column(name = "observed_at", nullable = false)
    Instant observedAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        observedAt = observedAt == null ? now : observedAt;
        createdAt = now;
    }
}
