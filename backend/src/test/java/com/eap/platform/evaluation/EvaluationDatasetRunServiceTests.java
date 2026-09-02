package com.eap.platform.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationDatasetRunServiceTests {
    @Mock
    private EvaluationDatasetRepository datasetRepository;
    @Mock
    private EvaluationDatasetItemRepository itemRepository;
    @Mock
    private EvaluatorDefinitionRepository evaluatorRepository;
    @Mock
    private EvaluationResultRepository resultRepository;
    @Mock
    private EvaluationService evaluationService;

    private EvaluationDatasetRunService service;
    private EvaluationDataset dataset;
    private EvaluatorDefinition evaluator;

    @BeforeEach
    void setUp() {
        service = new EvaluationDatasetRunService(datasetRepository, itemRepository, evaluatorRepository,
                resultRepository, evaluationService, new ObjectMapper());
        dataset = new EvaluationDataset();
        dataset.id = 9L;
        dataset.datasetKey = "REGRESSION";
        dataset.version = "v1";
        evaluator = new EvaluatorDefinition();
        evaluator.id = 3L;
        evaluator.evaluatorKey = "ACTION_COMPLETION";
        evaluator.version = "v1";
        evaluator.enabled = true;
        when(datasetRepository.findByDatasetKey("REGRESSION")).thenReturn(Optional.of(dataset));
        when(evaluatorRepository.findByEvaluatorKey("ACTION_COMPLETION")).thenReturn(Optional.of(evaluator));
    }

    @Test
    void runsDatasetAndCalculatesConfusionMatrix() {
        List<EvaluationDatasetItem> items = List.of(
                item("pass-true", "PASS"), item("pass-false", "PASS"),
                item("fail-true", "FAIL"), item("fail-false", "FAIL")
        );
        when(itemRepository.findAllByDatasetIdOrderByCreatedAtAsc(9L)).thenReturn(items);
        when(evaluationService.evaluate(any())).thenReturn(
                result(true), result(false), result(true), result(false));

        EvaluationDtos.EvaluationDatasetCalibrationResponse response = service.run("regression", 200);

        assertThat(response.totalItems()).isEqualTo(4);
        assertThat(response.evaluatedItems()).isEqualTo(4);
        assertThat(response.truePositive()).isEqualTo(1);
        assertThat(response.falseNegative()).isEqualTo(1);
        assertThat(response.falsePositive()).isEqualTo(1);
        assertThat(response.trueNegative()).isEqualTo(1);
        assertThat(response.precision()).isEqualByComparingTo("0.500000");
        assertThat(response.recall()).isEqualByComparingTo("0.500000");
        assertThat(response.f1()).isEqualByComparingTo("0.500000");
        assertThat(response.accuracy()).isEqualByComparingTo("0.500000");
        assertThat(response.evaluators()).singleElement().satisfies(item -> {
            assertThat(item.evaluatorKey()).isEqualTo("ACTION_COMPLETION");
            assertThat(item.evaluatorVersion()).isEqualTo("v1");
            assertThat(item.evaluatedItems()).isEqualTo(4);
        });
    }

    @Test
    void skipsWithheldObservationPayloadsAndSeparatesUnannotatedItems() {
        EvaluationDatasetItem withheld = item("withheld", "PASS");
        withheld.actualJson = "{\"captureMode\":\"HASH_ONLY\"}";
        EvaluationDatasetItem unannotated = item("unannotated", null);
        when(itemRepository.findAllByDatasetIdOrderByCreatedAtAsc(9L)).thenReturn(List.of(withheld, unannotated));
        when(evaluationService.evaluate(any())).thenReturn(result(true));

        EvaluationDtos.EvaluationDatasetCalibrationResponse response = service.run("REGRESSION", 2);

        assertThat(response.totalItems()).isEqualTo(2);
        assertThat(response.evaluatedItems()).isEqualTo(1);
        assertThat(response.skippedItems()).isEqualTo(1);
        assertThat(response.unannotatedItems()).isEqualTo(1);
        assertThat(response.precision()).isEqualByComparingTo("0.000000");
    }

    @Test
    void reusesSafeRedactedJsonButNeverHashOnlyPayloads() {
        EvaluationDatasetItem redacted = item("redacted", "PASS");
        redacted.actualJson = "{\"captureMode\":\"REDACTED\",\"content\":\"{\\\"completed\\\":true}\"}";
        EvaluationDatasetItem hashOnly = item("hash", "PASS");
        hashOnly.actualJson = "{\"captureMode\":\"HASH_ONLY\",\"sha256\":\"abc\"}";
        when(itemRepository.findAllByDatasetIdOrderByCreatedAtAsc(9L)).thenReturn(List.of(redacted, hashOnly));
        when(evaluationService.evaluate(any())).thenReturn(result(true));

        EvaluationDtos.EvaluationDatasetCalibrationResponse response = service.run("REGRESSION", 2);

        assertThat(response.evaluatedItems()).isEqualTo(1);
        assertThat(response.skippedItems()).isEqualTo(1);
        assertThat(response.truePositive()).isEqualTo(1);
    }

    private EvaluationDatasetItem item(String key, String label) {
        EvaluationDatasetItem item = new EvaluationDatasetItem();
        item.datasetId = dataset.id;
        item.itemKey = key;
        item.evaluatorKey = evaluator.evaluatorKey;
        item.evaluatorVersion = evaluator.version;
        item.inputJson = "{}";
        item.actualJson = "{\"completed\":true}";
        item.groundTruthLabel = label;
        return item;
    }

    private EvaluationDtos.EvaluationResultResponse result(boolean passed) {
        return new EvaluationDtos.EvaluationResultResponse(1L, "result", evaluator.evaluatorKey, evaluator.version,
                dataset.datasetKey, null, null, null, evaluator.level, EvaluationStatus.COMPLETED,
                passed ? BigDecimal.ONE : BigDecimal.ZERO, passed, BigDecimal.ONE, "test", null,
                Instant.now(), Instant.now());
    }
}
