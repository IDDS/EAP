package com.eap.platform.telemetry;

import com.eap.platform.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ProviderUsageReconciliationService {
    private final ProviderUsageSnapshotRepository snapshotRepository;
    private final ModelInvocationEventRepository eventRepository;

    public ProviderUsageReconciliationService(
            ProviderUsageSnapshotRepository snapshotRepository,
            ModelInvocationEventRepository eventRepository
    ) {
        this.snapshotRepository = snapshotRepository;
        this.eventRepository = eventRepository;
    }

    @Transactional
    public ProviderUsageDtos.SnapshotResponse record(ProviderUsageDtos.SnapshotRequest request) {
        ProviderUsageSnapshot existing = snapshotRepository.findBySnapshotKey(request.snapshotKey()).orElse(null);
        if (existing != null) return ProviderUsageDtos.from(existing);
        if (!request.windowEnd().isAfter(request.windowStart())) {
            throw new BusinessException("供应商用量窗口结束时间必须晚于开始时间");
        }
        String poolKey = normalize(request.usagePoolKey(), 128);
        String provider = normalize(request.provider(), 128);
        String currency = normalize(request.currency() == null ? "USD" : request.currency().toUpperCase(), 16);
        ProviderUsageSnapshot latest = snapshotRepository.findFirstByUsagePoolKeyOrderByWindowEndDesc(poolKey).orElse(null);
        if (latest != null && !latest.provider.equalsIgnoreCase(provider)) {
            throw new BusinessException("同一用量池不能混用不同模型供应商");
        }
        if (latest != null && !latest.currency.equalsIgnoreCase(currency)) {
            throw new BusinessException("同一用量池不能混用不同结算币种");
        }
        if (snapshotRepository.findFirstByUsagePoolKeyAndWindowStartLessThanAndWindowEndGreaterThan(
                poolKey, request.windowEnd(), request.windowStart()).isPresent()) {
            throw new BusinessException("同一用量池的供应商快照时间窗不能重叠，请复用原 snapshotKey 或拆分账单窗口");
        }

        ProviderUsageSnapshot snapshot = new ProviderUsageSnapshot();
        snapshot.snapshotKey = normalize(request.snapshotKey(), 128);
        snapshot.usagePoolKey = poolKey;
        snapshot.provider = provider;
        snapshot.accountLabel = normalize(request.accountLabel(), 128);
        snapshot.windowStart = request.windowStart();
        snapshot.windowEnd = request.windowEnd();
        snapshot.inputTokens = request.inputTokens();
        snapshot.outputTokens = request.outputTokens();
        snapshot.cachedInputTokens = request.cachedInputTokens();
        snapshot.reasoningTokens = request.reasoningTokens();
        snapshot.moneyCost = request.moneyCost() == null ? BigDecimal.ZERO : request.moneyCost();
        snapshot.currency = currency;
        snapshot.source = request.source();
        snapshot.observedAt = request.observedAt() == null ? request.windowEnd() : request.observedAt();
        return ProviderUsageDtos.from(snapshotRepository.save(snapshot));
    }

    @Transactional(readOnly = true)
    public List<ProviderUsageDtos.SnapshotResponse> list(int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        return snapshotRepository.findTop500ByOrderByWindowEndDesc().stream()
                .limit(bounded)
                .map(ProviderUsageDtos::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProviderUsageDtos.ReconciliationSummary reconcile(int windowHours, String requestedPoolKey) {
        int boundedHours = Math.max(1, Math.min(windowHours, 8760));
        Instant windowEnd = Instant.now();
        Instant windowStart = windowEnd.minus(boundedHours, ChronoUnit.HOURS);
        String poolFilter = normalize(requestedPoolKey, 128);
        List<ProviderUsageSnapshot> snapshots = snapshotRepository
                .findByWindowEndGreaterThanAndWindowStartLessThanOrderByWindowEndDesc(windowStart, windowEnd).stream()
                .filter(snapshot -> poolFilter == null || poolFilter.equals(snapshot.usagePoolKey))
                .toList();
        List<ModelInvocationEvent> events = eventRepository
                .findByObservedAtGreaterThanEqualOrderByObservedAtDesc(windowStart).stream()
                .filter(event -> event.observedAt == null || !event.observedAt.isAfter(windowEnd))
                .toList();

        long unmappedCalls = events.stream().filter(event -> blank(event.usagePoolKey)).count();
        long unmappedTokens = events.stream().filter(event -> blank(event.usagePoolKey))
                .mapToLong(this::totalTokens).sum();
        BigDecimal unmappedCost = events.stream().filter(event -> blank(event.usagePoolKey))
                .map(event -> money(event.moneyCost)).reduce(BigDecimal.ZERO, BigDecimal::add);

        Set<String> poolKeys = new LinkedHashSet<>();
        snapshots.forEach(snapshot -> poolKeys.add(snapshot.usagePoolKey));
        events.stream().filter(event -> !blank(event.usagePoolKey))
                .filter(event -> poolFilter == null || poolFilter.equals(event.usagePoolKey))
                .forEach(event -> poolKeys.add(event.usagePoolKey));

        Map<String, List<ProviderUsageSnapshot>> snapshotsByPool = new LinkedHashMap<>();
        snapshots.forEach(snapshot -> snapshotsByPool
                .computeIfAbsent(snapshot.usagePoolKey, ignored -> new ArrayList<>()).add(snapshot));
        Map<String, List<ModelInvocationEvent>> eventsByPool = new LinkedHashMap<>();
        events.stream().filter(event -> !blank(event.usagePoolKey))
                .filter(event -> poolFilter == null || poolFilter.equals(event.usagePoolKey))
                .forEach(event -> eventsByPool.computeIfAbsent(event.usagePoolKey, ignored -> new ArrayList<>()).add(event));

        List<ProviderUsageDtos.UsagePoolReconciliation> pools = poolKeys.stream()
                .map(poolKey -> reconcilePool(poolKey,
                        snapshotsByPool.getOrDefault(poolKey, List.of()),
                        eventsByPool.getOrDefault(poolKey, List.of())))
                .sorted(Comparator.comparingLong(ProviderUsageDtos.UsagePoolReconciliation::unallocatedTokens).reversed()
                        .thenComparing(ProviderUsageDtos.UsagePoolReconciliation::usagePoolKey))
                .toList();
        return new ProviderUsageDtos.ReconciliationSummary(
                boundedHours, windowStart, windowEnd, snapshots.size(), pools.size(),
                unmappedCalls, unmappedTokens, unmappedCost, pools
        );
    }

    private ProviderUsageDtos.UsagePoolReconciliation reconcilePool(
            String poolKey,
            List<ProviderUsageSnapshot> snapshots,
            List<ModelInvocationEvent> events
    ) {
        ProviderUsageSnapshot latest = snapshots.stream()
                .max(Comparator.comparing(snapshot -> snapshot.windowEnd)).orElse(null);
        long providerInput = snapshots.stream().mapToLong(snapshot -> snapshot.inputTokens).sum();
        long providerOutput = snapshots.stream().mapToLong(snapshot -> snapshot.outputTokens).sum();
        long providerTokens = providerInput + providerOutput;
        BigDecimal providerCost = snapshots.stream().map(snapshot -> money(snapshot.moneyCost))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long attributedInput = events.stream().mapToLong(event -> event.inputTokens).sum();
        long attributedOutput = events.stream().mapToLong(event -> event.outputTokens).sum();
        long attributedTokens = attributedInput + attributedOutput;
        BigDecimal attributedCost = events.stream().map(event -> money(event.moneyCost))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long unallocatedTokens = Math.max(0L, providerTokens - attributedTokens);
        long overAttributedTokens = Math.max(0L, attributedTokens - providerTokens);
        BigDecimal unallocatedCost = providerCost.subtract(attributedCost).max(BigDecimal.ZERO);
        BigDecimal overAttributedCost = attributedCost.subtract(providerCost).max(BigDecimal.ZERO);
        Set<Long> agentIds = new LinkedHashSet<>();
        events.stream().map(event -> event.agentId).filter(java.util.Objects::nonNull).forEach(agentIds::add);
        Instant providerWindowStart = snapshots.stream().map(snapshot -> snapshot.windowStart).min(Instant::compareTo).orElse(null);
        Instant providerWindowEnd = snapshots.stream().map(snapshot -> snapshot.windowEnd).max(Instant::compareTo).orElse(null);
        Instant lastSnapshotAt = snapshots.stream().map(snapshot -> snapshot.observedAt).max(Instant::compareTo).orElse(null);
        String provider = latest != null ? latest.provider : events.stream().map(event -> event.provider).filter(value -> !blank(value)).findFirst().orElse("unknown");
        String currency = latest != null ? latest.currency : events.stream().map(event -> event.currency).filter(value -> !blank(value)).findFirst().orElse("USD");
        return new ProviderUsageDtos.UsagePoolReconciliation(
                poolKey, provider, latest == null ? null : latest.accountLabel, currency,
                snapshots.size(), events.size(), agentIds.size(), providerInput, providerOutput, providerTokens,
                providerCost, attributedInput, attributedOutput, attributedTokens, attributedCost,
                unallocatedTokens, unallocatedCost, overAttributedTokens, overAttributedCost,
                coverage(attributedTokens, providerTokens), coverage(attributedCost, providerCost),
                providerWindowStart, providerWindowEnd, lastSnapshotAt
        );
    }

    private double coverage(long attributed, long provider) {
        if (provider <= 0) return 0.0;
        return Math.min(1.0, (double) attributed / provider);
    }

    private double coverage(BigDecimal attributed, BigDecimal provider) {
        if (provider == null || provider.signum() <= 0) return 0.0;
        return attributed.divide(provider, 6, RoundingMode.HALF_UP).min(BigDecimal.ONE).doubleValue();
    }

    private long totalTokens(ModelInvocationEvent event) {
        return event.inputTokens + event.outputTokens;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String normalize(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
