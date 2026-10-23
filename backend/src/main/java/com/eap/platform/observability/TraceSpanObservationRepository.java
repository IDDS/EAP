package com.eap.platform.observability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface TraceSpanObservationRepository extends JpaRepository<TraceSpanObservation, Long>, JpaSpecificationExecutor<TraceSpanObservation> {
    @org.springframework.data.jpa.repository.Query("select e.traceId from TraceSpanObservation e where e.agentId=:agentId and e.traceId is not null order by e.id desc")
    java.util.List<String> feedbackTraces(@org.springframework.data.repository.query.Param("agentId") Long agentId,org.springframework.data.domain.Pageable page);
    boolean existsByTraceIdAndAgentId(String traceId, Long agentId);

    boolean existsByTraceIdAndAgentIdAndEndpointKey(String traceId, Long agentId, String endpointKey);

    Optional<TraceSpanObservation> findByTraceIdAndSpanId(String traceId, String spanId);

    List<TraceSpanObservation> findTop500ByOrderByObservedAtDesc();
    @org.springframework.data.jpa.repository.Query("select coalesce(max(e.id), 0) from TraceSpanObservation e")
    long scanUpperBound();

    List<TraceSpanObservation> findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(
            long after, long upper, org.springframework.data.domain.Pageable page);

    List<TraceSpanObservation> findByTraceIdOrderByIdAsc(String traceId, org.springframework.data.domain.Pageable page);
}
