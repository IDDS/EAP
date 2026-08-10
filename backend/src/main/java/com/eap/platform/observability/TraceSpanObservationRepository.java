package com.eap.platform.observability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface TraceSpanObservationRepository extends JpaRepository<TraceSpanObservation, Long>, JpaSpecificationExecutor<TraceSpanObservation> {
    Optional<TraceSpanObservation> findByTraceIdAndSpanId(String traceId, String spanId);

    List<TraceSpanObservation> findTop500ByOrderByObservedAtDesc();
}
