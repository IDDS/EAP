package com.eap.platform.workflow;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, Long> {
    Optional<WorkflowExecution> findByWorkflowIdAndSubmissionKey(Long workflowId, String submissionKey);
    Optional<WorkflowExecution> findByWorkflowIdAndRunKey(Long workflowId, String runKey);
    List<WorkflowExecution> findByWorkflowIdOrderByCreatedAtDescIdDesc(Long workflowId, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<WorkflowExecution> findFirstByStatusOrderByCreatedAtAscIdAsc(String status);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update WorkflowExecution e set e.stepsJson = :steps, e.status = :status, e.reason = :reason, "
            + "e.completedAt = :completedAt where e.id = :id and e.status = 'RUNNING'")
    int progress(Long id, String steps, String status, String reason, Instant completedAt);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update WorkflowExecution e set e.status = 'EXECUTION_UNKNOWN', e.reason = '工作进程失联或执行超时，可能已产生外部效果；请核对 Runtime', "
            + "e.completedAt = :now where e.status = 'RUNNING' and e.startedAt <= :cutoff")
    int expire(Instant cutoff, Instant now);
}
