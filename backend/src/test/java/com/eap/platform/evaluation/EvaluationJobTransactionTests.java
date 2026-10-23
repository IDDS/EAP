package com.eap.platform.evaluation;

import com.eap.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationJobTransactionTests {
    @Test
    void commitsRunningBeforeProjectionAndPersistsFailureAfterAnEvaluationRollback() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table job_state (id bigint primary key, status varchar(32), error_message varchar(2048))");
        jdbc.update("insert into job_state (id, status) values (5, 'QUEUED')");
        var manager = new DataSourceTransactionManager(dataSource);
        var repository = mock(EvaluationProjectionJobRepository.class);
        var projection = mock(EvaluationProjectionService.class);
        var job = new EvaluationProjectionJob();
        job.id = 5L;
        job.requestedLimit = 10;
        when(repository.findTop1ByStatusOrderByCreatedAtAsc(EvaluationJobStatus.QUEUED)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return Optional.of(job);
        });
        when(repository.save(any())).thenAnswer(invocation -> {
            EvaluationProjectionJob saved = invocation.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jdbc.update("update job_state set status = ?, error_message = ? where id = ?",
                    saved.status.name(), saved.errorMessage, saved.id);
            return saved;
        });
        when(repository.finishRunning(org.mockito.ArgumentMatchers.eq(5L), any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                    return jdbc.update("update job_state set status = ?, error_message = ? where id = ? and status = 'RUNNING'",
                            invocation.getArgument(1).toString(), invocation.getArgument(6), 5L);
                });
        when(projection.synchronize(org.mockito.ArgumentMatchers.eq(10), any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // A separate connection can observe RUNNING while the projection is still executing.
            assertThat(jdbc.queryForObject("select status from job_state where id = 5", String.class)).isEqualTo("RUNNING");
            return new TransactionTemplate(manager).execute(status -> {
                throw new BusinessException("evaluation failed");
            });
        });
        var target = new EvaluationJobService(repository, projection, manager);
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        var service = (EvaluationJobService) factory.getProxy();

        service.processNext();

        assertThat(jdbc.queryForObject("select status from job_state where id = 5", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select error_message from job_state where id = 5", String.class)).isEqualTo("评估任务中断，已保存结果保留，请检查任务状态后重试");
        assertThat(job.completedAt).isNotNull();
        jdbc.execute("shutdown");
    }
}
