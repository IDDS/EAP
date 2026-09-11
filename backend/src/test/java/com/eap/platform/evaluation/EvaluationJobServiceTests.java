package com.eap.platform.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class EvaluationJobServiceTests {
    @Mock
    private EvaluationProjectionJobRepository repository;
    @Mock
    private EvaluationProjectionService projectionService;

    private EvaluationJobService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        service = new EvaluationJobService(repository, projectionService, transactionManager);
    }

    @Test
    void submitsBoundedQueuedJob() {
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EvaluationDtos.EvaluationJobResponse response = service.submit(999);

        assertThat(response.requestedLimit()).isEqualTo(500);
        assertThat(response.status()).isEqualTo(EvaluationJobStatus.QUEUED);
        assertThat(response.jobKey()).startsWith("eval-job-");
    }

    @Test
    void workerCompletesJobAndPersistsCounters() {
        EvaluationProjectionJob job = new EvaluationProjectionJob();
        ReflectionTestUtils.setField(job, "id", 5L);
        ReflectionTestUtils.setField(job, "jobKey", "eval-job-5");
        ReflectionTestUtils.setField(job, "requestedLimit", 200);
        ReflectionTestUtils.setField(job, "status", EvaluationJobStatus.QUEUED);
        ReflectionTestUtils.setField(job, "createdAt", Instant.parse("2026-09-02T00:00:00Z"));
        when(repository.findTop1ByStatusOrderByCreatedAtAsc(EvaluationJobStatus.QUEUED)).thenReturn(Optional.of(job));
        when(projectionService.synchronize(org.mockito.ArgumentMatchers.eq(200), any())).thenReturn(new EvaluationDtos.EvaluationSyncResponse(8, 6, 1, 1));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.processNext();

        assertThat(job.getStatus()).isEqualTo(EvaluationJobStatus.PARTIAL);
        assertThat(job.getScanned()).isEqualTo(8);
        assertThat(job.getEvaluated()).isEqualTo(6);
        assertThat(job.getSkipped()).isEqualTo(1);
        assertThat(job.getFailed()).isEqualTo(1);
        assertThat(job.getCompletedAt()).isNotNull();
        verify(repository).save(job);
        verify(repository).finishRunning(job.id, EvaluationJobStatus.PARTIAL, 8, 6, 1, 1, null, job.completedAt);
    }
}
