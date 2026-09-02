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
}
