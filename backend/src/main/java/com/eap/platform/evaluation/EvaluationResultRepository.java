package com.eap.platform.evaluation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface EvaluationResultRepository extends JpaRepository<EvaluationResult, Long> {
    Optional<EvaluationResult> findByResultKey(String resultKey);

    List<EvaluationResult> findTop500ByOrderByEvaluatedAtDesc();

    List<EvaluationResult> findTop500ByAgentIdOrderByEvaluatedAtDesc(Long agentId);

    List<EvaluationResult> findTop500ByTraceIdOrderByEvaluatedAtDesc(String traceId);

    List<EvaluationResult> findByEvaluatedAtGreaterThanEqualOrderByEvaluatedAtDesc(Instant evaluatedAt);

    @org.springframework.data.jpa.repository.Query("select coalesce(max(r.id), 0) from EvaluationResult r")
    long signalUpperBound();

    @org.springframework.data.jpa.repository.Query("""
            select r from EvaluationResult r where r.status = :status and r.passed = false
            and r.id > :cursor and r.id <= :upperBound order by r.id
            """)
    List<EvaluationResult> scanSignals(EvaluationStatus status, long cursor, long upperBound,
                                       org.springframework.data.domain.Pageable page);
}
