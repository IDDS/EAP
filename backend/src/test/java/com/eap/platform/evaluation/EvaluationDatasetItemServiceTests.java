package com.eap.platform.evaluation;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.observability.ObservationProperties;
import com.eap.platform.observability.SensitiveContentSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationDatasetItemServiceTests {
    @Mock
    private EvaluationDatasetRepository datasetRepository;
    @Mock
    private EvaluationDatasetItemRepository itemRepository;
    @Mock
    private EvaluatorDefinitionRepository evaluatorRepository;
    @Mock
    private AgentRepository agentRepository;

    private EvaluationDatasetItemService service;
    private EvaluationDataset dataset;
    private EvaluatorDefinition evaluator;

    @BeforeEach
    void setUp() {
        service = new EvaluationDatasetItemService(datasetRepository, itemRepository, evaluatorRepository,
                agentRepository, new ObjectMapper(), new ObservationProperties(), new SensitiveContentSanitizer());
        dataset = new EvaluationDataset();
        dataset.id = 9L;
        dataset.datasetKey = "REGRESSION_V1";
        dataset.itemCount = 0;
        evaluator = new EvaluatorDefinition();
        evaluator.id = 3L;
        evaluator.evaluatorKey = "ACTION_COMPLETION";
        evaluator.version = "v1";
        when(datasetRepository.findByDatasetKey("REGRESSION_V1")).thenReturn(Optional.of(dataset));
    }

    @Test
    void storesDatasetItemWithSanitizedObservationAndIncrementsCount() throws Exception {
        when(evaluatorRepository.findByEvaluatorKey("ACTION_COMPLETION")).thenReturn(Optional.of(evaluator));
        when(itemRepository.findByDatasetIdAndItemKey(9L, "case-1")).thenReturn(Optional.empty());
        when(itemRepository.save(any())).thenAnswer(invocation -> {
            EvaluationDatasetItem item = invocation.getArgument(0);
            item.id = 11L;
            return item;
        });
        EvaluationDtos.DatasetItemResponse response = service.create("regression_v1",
                new EvaluationDtos.DatasetItemCreateRequest("case-1", "action_completion", null,
                        null, "trace-1", null, new ObjectMapper().readTree("{\"task\":\"ok\"}"), null,
                        new ObjectMapper().readTree("{\"completed\":true}")));

        assertThat(response.id()).isEqualTo(11L);
        assertThat(response.itemKey()).isEqualTo("case-1");
        assertThat(response.evaluatorKey()).isEqualTo("ACTION_COMPLETION");
        assertThat(response.input().path("captureMode").asText()).isEqualTo("HASH_ONLY");
        assertThat(dataset.itemCount).isEqualTo(1);
    }

    @Test
    void appliesDefaultScoresForHumanGroundTruthLabels() {
        EvaluationDatasetItem item = new EvaluationDatasetItem();
        item.id = 11L;
        item.datasetId = 9L;
        item.itemKey = "case-1";
        item.evaluatorKey = "ACTION_COMPLETION";
        item.inputJson = "{}";
        item.actualJson = "{}";
        when(itemRepository.findByDatasetIdAndItemKey(9L, "case-1")).thenReturn(Optional.of(item));
        when(itemRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EvaluationDtos.DatasetItemResponse response = service.annotate("regression_v1", "case-1",
                new EvaluationDtos.DatasetItemAnnotationRequest("fail", null, "SME-1", "未完成目标"));

        assertThat(response.groundTruthLabel()).isEqualTo("FAIL");
        assertThat(response.groundTruthScore()).isEqualByComparingTo(BigDecimal.ZERO.setScale(6));
        assertThat(response.annotatedBy()).isEqualTo("SME-1");
    }

    @Test
    void listsDatasetItemsWithinRequestedLimit() {
        EvaluationDatasetItem first = new EvaluationDatasetItem();
        first.datasetId = 9L;
        first.itemKey = "case-1";
        first.evaluatorKey = "ACTION_COMPLETION";
        first.inputJson = "{}";
        first.actualJson = "{}";
        EvaluationDatasetItem second = new EvaluationDatasetItem();
        second.datasetId = 9L;
        second.itemKey = "case-2";
        second.evaluatorKey = "ACTION_COMPLETION";
        second.inputJson = "{}";
        second.actualJson = "{}";
        when(itemRepository.findAllByDatasetIdOrderByCreatedAtAsc(9L)).thenReturn(List.of(first, second));

        assertThat(service.list("regression_v1", 1)).extracting(EvaluationDtos.DatasetItemResponse::itemKey)
                .containsExactly("case-1");
    }
}
