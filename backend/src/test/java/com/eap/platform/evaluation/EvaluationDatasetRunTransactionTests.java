package com.eap.platform.evaluation;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.observability.ObservationProperties;
import com.eap.platform.observability.SensitiveContentSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationDatasetRunTransactionTests {
    @Test
    void failedSampleDoesNotRollBackSuccessfulSamplesThroughRealTransactionProxies() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table saved_results (result_key varchar(128))");
        var transactionManager = new DataSourceTransactionManager(dataSource);
        var datasets = mock(EvaluationDatasetRepository.class);
        var items = mock(EvaluationDatasetItemRepository.class);
        var evaluators = mock(EvaluatorDefinitionRepository.class);
        var results = mock(EvaluationResultRepository.class);
        var mapper = new ObjectMapper();
        var dataset = new EvaluationDataset();
        dataset.id = 1L;
        dataset.datasetKey = "BATCH";
        var evaluator = new EvaluatorDefinition();
        evaluator.id = 1L;
        evaluator.evaluatorKey = "BUDGET";
        evaluator.implementationKey = "COST_BUDGET";
        evaluator.version = "v1";
        evaluator.enabled = true;
        evaluator.threshold = new BigDecimal("0.5");
        when(datasets.findByDatasetKey("BATCH")).thenReturn(Optional.of(dataset));
        when(evaluators.findByEvaluatorKey("BUDGET")).thenReturn(Optional.of(evaluator));
        when(items.findByDatasetIdOrderByCreatedAtAscIdAsc(1L, PageRequest.of(0, 3)))
                .thenReturn(List.of(item("first", "{\"cost\":1}"), item("invalid", "{}"), item("last", "{\"cost\":2}")));
        when(results.save(any())).thenAnswer(invocation -> {
            EvaluationResult result = invocation.getArgument(0);
            jdbc.update("insert into saved_results (result_key) values (?)", result.resultKey);
            result.id = 1L;
            return result;
        });
        EvaluationService evaluation = transactional(new EvaluationService(evaluators, datasets, results,
                mock(AgentRepository.class), mapper, new ObservationProperties(), new SensitiveContentSanitizer()), transactionManager);
        EvaluationDatasetRunService runner = transactional(new EvaluationDatasetRunService(datasets, items,
                evaluators, results, evaluation, mapper, mock(EvaluationCalibrationHistoryService.class)), transactionManager);

        var response = runner.run("BATCH", 3);

        assertThat(response.failedItems()).isEqualTo(1);
        assertThat(response.evaluatedItems()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from saved_results", Integer.class)).isEqualTo(2);
        jdbc.execute("shutdown");
    }

    private EvaluationDatasetItem item(String key, String actual) {
        var item = new EvaluationDatasetItem();
        item.datasetId = 1L;
        item.itemKey = key;
        item.evaluatorKey = "BUDGET";
        item.evaluatorVersion = "v1";
        item.inputJson = "{\"maxCost\":10}";
        item.actualJson = actual;
        item.groundTruthLabel = "PASS";
        return item;
    }

    @SuppressWarnings("unchecked")
    private <T> T transactional(T target, DataSourceTransactionManager manager) {
        var interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }
}
