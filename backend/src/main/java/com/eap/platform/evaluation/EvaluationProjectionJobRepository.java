package com.eap.platform.evaluation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EvaluationProjectionJobRepository extends JpaRepository<EvaluationProjectionJob, Long> {
    Optional<EvaluationProjectionJob> findByJobKey(String jobKey);

    List<EvaluationProjectionJob> findTop20ByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EvaluationProjectionJob> findTop1ByStatusOrderByCreatedAtAsc(EvaluationJobStatus status);

    boolean existsByStatusIn(Collection<EvaluationJobStatus> statuses);
}
