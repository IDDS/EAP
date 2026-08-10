package com.eap.platform.telemetry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ModelInvocationEventRepository extends JpaRepository<ModelInvocationEvent, Long>, JpaSpecificationExecutor<ModelInvocationEvent> {
    Optional<ModelInvocationEvent> findByEventKey(String eventKey);

    List<ModelInvocationEvent> findTop500ByOrderByObservedAtDesc();

    List<ModelInvocationEvent> findByObservedAtGreaterThanEqualOrderByObservedAtDesc(Instant observedAt);
}
