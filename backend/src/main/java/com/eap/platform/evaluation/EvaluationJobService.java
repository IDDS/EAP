package com.eap.platform.evaluation;

import com.eap.platform.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class EvaluationJobService {
    private final EvaluationProjectionJobRepository repository;
    private final EvaluationProjectionService projectionService;
    private final TransactionTemplate jobTransaction;
    @Value("${eap.evaluation.job-timeout-seconds:900}")
    private long jobTimeoutSeconds = 900;

    public EvaluationJobService(EvaluationProjectionJobRepository repository,
                                EvaluationProjectionService projectionService,
                                PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.projectionService = projectionService;
        this.jobTransaction = new TransactionTemplate(transactionManager);
        this.jobTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public EvaluationDtos.EvaluationJobResponse submit(int requestedLimit) {
        EvaluationProjectionJob job = new EvaluationProjectionJob();
        job.jobKey = "eval-job-" + UUID.randomUUID();
        job.requestedLimit = Math.max(1, Math.min(requestedLimit, 500));
        job.status = EvaluationJobStatus.QUEUED;
        return EvaluationDtos.EvaluationJobResponse.from(repository.save(job));
    }

    @Transactional
    public EvaluationDtos.EvaluationJobResponse submitIfIdle(int requestedLimit) {
        expireTimedOutJobs();
        if (repository.existsByStatusIn(List.of(EvaluationJobStatus.QUEUED, EvaluationJobStatus.RUNNING))) {
            return repository.findTop1ByStatusOrderByCreatedAtAsc(EvaluationJobStatus.QUEUED)
                    .or(() -> repository.findTop1ByStatusOrderByCreatedAtAsc(EvaluationJobStatus.RUNNING))
                    .map(EvaluationDtos.EvaluationJobResponse::from)
                    .orElseGet(() -> submit(requestedLimit));
        }
        return submit(requestedLimit);
    }

    @Transactional(readOnly = true)
    public List<EvaluationDtos.EvaluationJobResponse> list() {
        return repository.findTop20ByOrderByCreatedAtDesc().stream()
                .map(EvaluationDtos.EvaluationJobResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public EvaluationDtos.EvaluationJobResponse get(Long id) {
        return EvaluationDtos.EvaluationJobResponse.from(repository.findById(id)
                .orElseThrow(() -> new BusinessException("评估任务不存在")));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processNext() {
        EvaluationProjectionJob job = jobTransaction.execute(status -> {
            expireTimedOutJobs();
            EvaluationProjectionJob queued = repository.findTop1ByStatusOrderByCreatedAtAsc(EvaluationJobStatus.QUEUED)
                    .orElse(null);
            if (queued == null) return null;
            queued.status = EvaluationJobStatus.RUNNING;
            queued.phase = "PREPARING";
            queued.startedAt = Instant.now();
            return repository.save(queued);
        });
        if (job == null) return;
        try {
            EvaluationDtos.EvaluationSyncResponse result = projectionService.synchronize(job.requestedLimit, progress -> {
                Instant now = Instant.now();
                Integer updated = jobTransaction.execute(status -> repository.progress(job.id, progress.phase(),
                        progress.processed(), progress.total(), progress.scanned(), progress.evaluated(),
                        progress.skipped(), progress.failed(), now, now.minusSeconds(Math.max(1, jobTimeoutSeconds))));
                if (updated == null || updated == 0) throw new BusinessException("任务已停止或超时");
                job.scanned = progress.scanned(); job.evaluated = progress.evaluated();
                job.skipped = progress.skipped(); job.failed = progress.failed();
            });
            job.scanned = result.scanned();
            job.evaluated = result.evaluated();
            job.skipped = result.skipped();
            job.failed = result.failed();
            job.status = result.failed() > 0 ? EvaluationJobStatus.PARTIAL : EvaluationJobStatus.SUCCEEDED;
            job.completedAt = Instant.now();
        } catch (RuntimeException exception) {
            job.status = EvaluationJobStatus.FAILED;
            job.errorMessage = exception instanceof com.eap.platform.common.ConflictException
                    ? "已有扫描运行或扫描租约失效，请稍后重试；已保存结果会复用"
                    : "评估任务中断，已保存结果保留，请检查任务状态后重试";
            job.completedAt = Instant.now();
        }
        jobTransaction.executeWithoutResult(status -> repository.finishRunning(job.id, job.status, job.scanned,
                job.evaluated, job.skipped, job.failed, job.errorMessage, job.completedAt));
    }

    private void expireTimedOutJobs() {
        Instant now = Instant.now();
        repository.expireRunning(now.minusSeconds(Math.max(1, jobTimeoutSeconds)), now);
    }

    private String trim(String value) {
        if (value == null || value.isBlank()) return "评估投影失败";
        return value.length() <= 2048 ? value : value.substring(0, 2048);
    }
}
