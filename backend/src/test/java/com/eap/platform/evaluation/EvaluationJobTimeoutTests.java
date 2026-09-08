package com.eap.platform.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
class EvaluationJobTimeoutTests {
    @Autowired EvaluationProjectionJobRepository repository;
    @Autowired TestEntityManager entities;

    @Test
    void expiredWorkerCannotOverwriteTimeoutAndFreshJobsStayRunning() {
        Instant now = Instant.now();
        EvaluationProjectionJob stale = job("stale", now.minusSeconds(901));
        EvaluationProjectionJob fresh = job("fresh", now.minusSeconds(10));
        entities.flush();
        entities.clear();

        assertThat(repository.expireRunning(now.minusSeconds(900), now)).isEqualTo(1);
        assertThat(repository.findById(stale.id).orElseThrow().status).isEqualTo(EvaluationJobStatus.FAILED);
        assertThat(repository.findById(fresh.id).orElseThrow().status).isEqualTo(EvaluationJobStatus.RUNNING);
        assertThat(repository.finishRunning(stale.id, EvaluationJobStatus.SUCCEEDED, 1, 1, 0, 0, null, now)).isZero();
        assertThat(repository.findById(stale.id).orElseThrow().errorMessage).contains("结果未知");
        assertThat(repository.finishRunning(fresh.id, EvaluationJobStatus.SUCCEEDED, 1, 1, 0, 0, null, now)).isEqualTo(1);
        assertThat(repository.findById(fresh.id).orElseThrow().status).isEqualTo(EvaluationJobStatus.SUCCEEDED);
    }

    private EvaluationProjectionJob job(String key, Instant startedAt) {
        EvaluationProjectionJob job = new EvaluationProjectionJob();
        job.jobKey = key; job.status = EvaluationJobStatus.RUNNING; job.startedAt = startedAt;
        return repository.save(job);
    }
}
