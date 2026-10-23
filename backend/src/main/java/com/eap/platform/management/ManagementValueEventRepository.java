package com.eap.platform.management;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ManagementValueEventRepository extends JpaRepository<ManagementValueEvent, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<ManagementValueEvent> {
    Optional<ManagementValueEvent> findByEventKey(String eventKey);

    Optional<ManagementValueEvent> findBySourceSystemAndSourceRecordKey(String sourceSystem, String sourceRecordKey);

    List<ManagementValueEvent> findTop500ByOrderByObservedAtDesc();

    List<ManagementValueEvent> findByObservedAtGreaterThanEqualOrderByObservedAtDesc(Instant observedAt);
}
