package com.eap.platform.telemetry;

import com.eap.platform.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProviderUsageReconciliationServiceTests {
    @Mock private ProviderUsageSnapshotRepository snapshotRepository;
    @Mock private ModelInvocationEventRepository eventRepository;

    private ProviderUsageReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new ProviderUsageReconciliationService(snapshotRepository, eventRepository);
    }

    @Test
    void reconcilesProviderTotalWithPerAgentTelemetryAndKeepsUnmappedSeparate() {
        Instant now = Instant.now();
        ProviderUsageSnapshot provider = snapshot(
                "provider-day-1", "shared-openai-prod", 1000, 500, "30.000000",
                now.minus(20, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS)
        );
        when(snapshotRepository.findByWindowEndGreaterThanAndWindowStartLessThanOrderByWindowEndDesc(any(), any()))
                .thenReturn(List.of(provider));
        when(eventRepository.findByObservedAtGreaterThanEqualOrderByObservedAtDesc(any())).thenReturn(List.of(
                event("agent-1-call", "shared-openai-prod", 1L, 600, 300, "18.000000"),
                event("agent-2-call", "shared-openai-prod", 2L, 100, 50, "3.000000"),
                event("unmapped-call", null, 3L, 20, 10, "1.000000")
        ));

        ProviderUsageDtos.ReconciliationSummary result = service.reconcile(24, null);

        assertThat(result.snapshotCount()).isEqualTo(1);
        assertThat(result.unmappedTelemetryCalls()).isEqualTo(1);
        assertThat(result.unmappedTelemetryTokens()).isEqualTo(30);
        assertThat(result.pools()).hasSize(1);
        ProviderUsageDtos.UsagePoolReconciliation pool = result.pools().getFirst();
        assertThat(pool.providerTotalTokens()).isEqualTo(1500);
        assertThat(pool.attributedTotalTokens()).isEqualTo(1050);
        assertThat(pool.unallocatedTokens()).isEqualTo(450);
        assertThat(pool.overAttributedTokens()).isZero();
        assertThat(pool.unallocatedMoneyCost()).isEqualByComparingTo("9.000000");
        assertThat(pool.attributedAgents()).isEqualTo(2);
        assertThat(pool.tokenCoverageRate()).isEqualTo(0.7);
    }

    @Test
    void rejectsOverlappingProviderWindowsToPreventDoubleCounting() {
        Instant start = Instant.parse("2026-08-14T00:00:00Z");
        when(snapshotRepository.findBySnapshotKey("provider-window-2")).thenReturn(Optional.empty());
        when(snapshotRepository.findFirstByUsagePoolKeyOrderByWindowEndDesc(anyString())).thenReturn(Optional.empty());
        when(snapshotRepository.findFirstByUsagePoolKeyAndWindowStartLessThanAndWindowEndGreaterThan(
                anyString(), any(), any())).thenReturn(Optional.of(snapshot(
                "provider-window-1", "shared-openai-prod", 10, 5, "0.1", start, start.plus(1, ChronoUnit.DAYS)
        )));

        ProviderUsageDtos.SnapshotRequest request = new ProviderUsageDtos.SnapshotRequest(
                "provider-window-2", "shared-openai-prod", "openai", "生产共享凭证",
                start.plus(12, ChronoUnit.HOURS), start.plus(36, ChronoUnit.HOURS),
                20, 10, 0, 0, new BigDecimal("0.2"), "USD", ProviderUsageSource.PROVIDER_API, null
        );

        assertThatThrownBy(() -> service.record(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("时间窗不能重叠");
    }

    private ProviderUsageSnapshot snapshot(
            String key,
            String poolKey,
            long input,
            long output,
            String cost,
            Instant start,
            Instant end
    ) {
        ProviderUsageSnapshot snapshot = new ProviderUsageSnapshot();
        snapshot.id = 1L;
        snapshot.snapshotKey = key;
        snapshot.usagePoolKey = poolKey;
        snapshot.provider = "openai";
        snapshot.accountLabel = "生产共享凭证";
        snapshot.windowStart = start;
        snapshot.windowEnd = end;
        snapshot.inputTokens = input;
        snapshot.outputTokens = output;
        snapshot.cachedInputTokens = 0L;
        snapshot.reasoningTokens = 0L;
        snapshot.moneyCost = new BigDecimal(cost);
        snapshot.currency = "USD";
        snapshot.source = ProviderUsageSource.PROVIDER_API;
        snapshot.observedAt = end;
        snapshot.createdAt = end;
        return snapshot;
    }

    private ModelInvocationEvent event(
            String key,
            String poolKey,
            Long agentId,
            long input,
            long output,
            String cost
    ) {
        ModelInvocationEvent event = new ModelInvocationEvent();
        event.eventKey = key;
        event.usagePoolKey = poolKey;
        event.agentId = agentId;
        event.provider = "openai";
        event.modelName = "gpt-5";
        event.status = ModelInvocationStatus.SUCCEEDED;
        event.inputTokens = input;
        event.outputTokens = output;
        event.cachedInputTokens = 0L;
        event.reasoningTokens = 0L;
        event.latencyMs = 100L;
        event.moneyCost = new BigDecimal(cost);
        event.currency = "USD";
        event.observedAt = Instant.now().minus(1, ChronoUnit.HOURS);
        return event;
    }
}
