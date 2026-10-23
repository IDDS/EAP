package com.eap.platform.telemetry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ModelInvocationEventRepository extends JpaRepository<ModelInvocationEvent, Long>, JpaSpecificationExecutor<ModelInvocationEvent> {
    @org.springframework.data.jpa.repository.Query("select e.traceId from ModelInvocationEvent e where e.agentId=:agentId and e.traceId is not null order by e.id desc")
    java.util.List<String> feedbackTraces(@org.springframework.data.repository.query.Param("agentId") Long agentId,org.springframework.data.domain.Pageable page);
    boolean existsByTraceIdAndAgentId(String traceId, Long agentId);

    Optional<ModelInvocationEvent> findByEventKey(String eventKey);

    List<ModelInvocationEvent> findTop500ByOrderByObservedAtDesc();

    List<ModelInvocationEvent> findByObservedAtGreaterThanEqualOrderByObservedAtDesc(Instant observedAt);
    @Query("select distinct e.currency from ModelInvocationEvent e where e.observedAt >= :since")
    List<String> findCurrenciesSince(@Param("since") Instant since);
}
