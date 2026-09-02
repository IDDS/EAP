package com.eap.platform.evaluation;

import com.eap.platform.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class EvaluationJobService {
    private final EvaluationProjectionJobRepository repository;
    private final EvaluationProjectionService projectionService;

    public EvaluationJobService(EvaluationProjectionJobRepository repository,
                                EvaluationProjectionService projectionService) {
        this.repository = repository;
        this.projectionService = projectionService;
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

    @Transactional
    public void processNext() {
        EvaluationProjectionJob job = repository.findTop1ByStatusOrderByCreatedAtAsc(EvaluationJobStatus.QUEUED)
                .orElse(null);
        if (job == null) return;
        job.status = EvaluationJobStatus.RUNNING;
        job.startedAt = Instant.now();
        repository.save(job);
        try {
            EvaluationDtos.EvaluationSyncResponse result = projectionService.synchronize(job.requestedLimit);
            job.scanned = result.scanned();
            job.evaluated = result.evaluated();
            job.skipped = result.skipped();
            job.failed = result.failed();
            job.status = EvaluationJobStatus.SUCCEEDED;
            job.completedAt = Instant.now();
        } catch (RuntimeException exception) {
            job.status = EvaluationJobStatus.FAILED;
            job.errorMessage = trim(exception.getMessage());
            job.completedAt = Instant.now();
        }
        repository.save(job);
    }

    private String trim(String value) {
        if (value == null || value.isBlank()) return "评估投影失败";
        return value.length() <= 2048 ? value : value.substring(0, 2048);
    }
}
