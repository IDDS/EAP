package com.eap.platform.management;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ManagementValueEventRepository extends JpaRepository<ManagementValueEvent, Long> {
    Optional<ManagementValueEvent> findByEventKey(String eventKey);

    List<ManagementValueEvent> findTop500ByOrderByObservedAtDesc();

    List<ManagementValueEvent> findByObservedAtGreaterThanEqualOrderByObservedAtDesc(Instant observedAt);
}
