package com.eap.platform.evaluation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationJobServiceTests {
    @Mock
    private EvaluationProjectionJobRepository repository;
    @Mock
    private EvaluationProjectionService projectionService;

    private EvaluationJobService service;

    @BeforeEach
    void setUp() {
        service = new EvaluationJobService(repository, projectionService);
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
        when(projectionService.synchronize(200)).thenReturn(new EvaluationDtos.EvaluationSyncResponse(8, 6, 1, 1));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.processNext();

        assertThat(job.getStatus()).isEqualTo(EvaluationJobStatus.SUCCEEDED);
        assertThat(job.getScanned()).isEqualTo(8);
        assertThat(job.getEvaluated()).isEqualTo(6);
        assertThat(job.getSkipped()).isEqualTo(1);
        assertThat(job.getFailed()).isEqualTo(1);
        assertThat(job.getCompletedAt()).isNotNull();
        verify(repository, org.mockito.Mockito.atLeast(2)).save(job);
    }
}
