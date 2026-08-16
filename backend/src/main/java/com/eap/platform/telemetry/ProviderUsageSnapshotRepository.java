package com.eap.platform.telemetry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProviderUsageSnapshotRepository extends JpaRepository<ProviderUsageSnapshot, Long> {
    Optional<ProviderUsageSnapshot> findBySnapshotKey(String snapshotKey);

    Optional<ProviderUsageSnapshot> findFirstByUsagePoolKeyAndWindowStartLessThanAndWindowEndGreaterThan(
            String usagePoolKey,
            Instant windowEnd,
            Instant windowStart
    );

    Optional<ProviderUsageSnapshot> findFirstByUsagePoolKeyOrderByWindowEndDesc(String usagePoolKey);

    List<ProviderUsageSnapshot> findTop500ByOrderByWindowEndDesc();

    List<ProviderUsageSnapshot> findByWindowEndGreaterThanAndWindowStartLessThanOrderByWindowEndDesc(
            Instant windowStart,
            Instant windowEnd
    );
}
