package com.eap.platform.evaluation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface EvaluationProjectionJobRepository extends JpaRepository<EvaluationProjectionJob, Long> {
    Optional<EvaluationProjectionJob> findByJobKey(String jobKey);

    List<EvaluationProjectionJob> findTop20ByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EvaluationProjectionJob> findTop1ByStatusOrderByCreatedAtAsc(EvaluationJobStatus status);

    boolean existsByStatusIn(Collection<EvaluationJobStatus> statuses);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EvaluationProjectionJob j set j.status = com.eap.platform.evaluation.EvaluationJobStatus.FAILED, "
            + "j.errorMessage = '执行超时或工作进程失联；结果未知，请检查后重新提交', j.completedAt = :now, j.updatedAt = :now "
            + "where j.status = com.eap.platform.evaluation.EvaluationJobStatus.RUNNING and (j.startedAt <= :cutoff or j.startedAt is null)")
    int expireRunning(Instant cutoff, Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EvaluationProjectionJob j set j.status = :status, j.scanned = :scanned, j.evaluated = :evaluated, "
            + "j.skipped = :skipped, j.failed = :failed, j.errorMessage = :error, j.completedAt = :now, j.updatedAt = :now "
            + "where j.id = :id and j.status = com.eap.platform.evaluation.EvaluationJobStatus.RUNNING")
    int finishRunning(Long id, EvaluationJobStatus status, int scanned, int evaluated, int skipped, int failed,
                      String error, Instant now);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update EvaluationProjectionJob j set j.phase = :phase, j.processed = :processed, j.total = :total, "
            + "j.scanned = :scanned, j.evaluated = :evaluated, j.skipped = :skipped, j.failed = :failed, j.updatedAt = :now "
            + "where j.id = :id and j.status = com.eap.platform.evaluation.EvaluationJobStatus.RUNNING and j.startedAt > :cutoff")
    int progress(Long id, String phase, int processed, int total, int scanned, int evaluated, int skipped, int failed,
                 Instant now, Instant cutoff);

}
