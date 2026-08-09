package com.eap.platform.telemetry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ModelInvocationEventRepository extends JpaRepository<ModelInvocationEvent, Long> {
    Optional<ModelInvocationEvent> findByEventKey(String eventKey);

    List<ModelInvocationEvent> findTop500ByOrderByObservedAtDesc();

    List<ModelInvocationEvent> findByObservedAtGreaterThanEqualOrderByObservedAtDesc(Instant observedAt);
}
