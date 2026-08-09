package com.eap.platform.governance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GovernanceDataEventRepository extends JpaRepository<GovernanceDataEvent, Long> {
    Optional<GovernanceDataEvent> findByEventKey(String eventKey);

    List<GovernanceDataEvent> findTop500ByOrderByObservedAtDesc();

    List<GovernanceDataEvent> findByObservedAtGreaterThanEqualOrderByObservedAtDesc(Instant since);
}
