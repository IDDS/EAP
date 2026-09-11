package com.eap.platform.governance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GovernanceDataEventRepository extends JpaRepository<GovernanceDataEvent, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<GovernanceDataEvent> {
    Optional<GovernanceDataEvent> findByEventKey(String eventKey);

    List<GovernanceDataEvent> findTop500ByOrderByObservedAtDesc();

    List<GovernanceDataEvent> findAllByOrderByObservedAtDescIdDesc(Pageable pageable);

    List<GovernanceDataEvent> findByAgentIdOrderByObservedAtDescIdDesc(Long agentId, Pageable pageable);

    List<GovernanceDataEvent> findByObservedAtGreaterThanEqualOrderByObservedAtDesc(Instant since);
}
