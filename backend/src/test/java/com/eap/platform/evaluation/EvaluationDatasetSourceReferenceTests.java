package com.eap.platform.evaluation;

import com.eap.platform.agent.AgentRepository;
import com.eap.platform.observability.ObservationProperties;
import com.eap.platform.observability.SensitiveContentSanitizer;
import com.eap.platform.observability.TraceSpanObservation;
import com.eap.platform.observability.TraceSpanObservationRepository;
import com.eap.platform.audit.AuditLogRepository;
import com.eap.platform.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvaluationDatasetSourceReferenceTests {
    private final EvaluationDatasetRepository datasets = mock(EvaluationDatasetRepository.class);
    private final EvaluationDatasetItemRepository items = mock(EvaluationDatasetItemRepository.class);
    private final EvaluatorDefinitionRepository evaluators = mock(EvaluatorDefinitionRepository.class);
    private final AgentRepository agents = mock(AgentRepository.class);
    private final TraceSpanObservationRepository spans = mock(TraceSpanObservationRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private EvaluationDatasetItemService service;
    private EvaluationDataset dataset;
    @BeforeEach void setup() {
        service = new EvaluationDatasetItemService(datasets, items, evaluators, agents,
                mock(AuditLogRepository.class), mapper, new ObservationProperties(), new SensitiveContentSanitizer(), spans);
        dataset = new EvaluationDataset(); dataset.id = 1L; dataset.itemCount = 0;
        when(datasets.findByDatasetKey("DS")).thenReturn(Optional.of(dataset));
        var evaluator = new EvaluatorDefinition(); evaluator.evaluatorKey = "ACTION_COMPLETION"; evaluator.version = "v1";
        when(evaluators.findByEvaluatorKey("ACTION_COMPLETION")).thenReturn(Optional.of(evaluator));
        when(agents.existsById(7L)).thenReturn(true);
        when(items.save(any())).thenAnswer(call -> call.getArgument(0));
    }
    private EvaluationDtos.DatasetItemCreateRequest request(boolean checked, Long agent, String trace, String span) {
        return new EvaluationDtos.DatasetItemCreateRequest("case", "ACTION_COMPLETION", "v1", agent, trace, span,
                mapper.createObjectNode(), null, mapper.createObjectNode(), checked);
    }
    private TraceSpanObservation observation(Long agent, String trace, String span) {
        var row = mock(TraceSpanObservation.class);
        when(row.getAgentId()).thenReturn(agent); when(row.getTraceId()).thenReturn(trace); when(row.getSpanId()).thenReturn(span);
        when(row.getId()).thenReturn(91L); when(row.getEndpointKey()).thenReturn("fixture-endpoint");
        when(row.getObservedAt()).thenReturn(Instant.parse("2026-10-16T00:00:00Z"));
        when(spans.findByTraceIdAndSpanId("Trace-A", "Span-A")).thenReturn(Optional.of(row));
        return row;
    }
    @Test void unverifiedInputNeverReadsOrInfersObservationEvidence() {
        var result = service.create("ds", request(false, 7L, "Trace-A", "Span-A"));
        assertThat(result.sourceReference().state()).isEqualTo("MANUAL_UNVERIFIED");
        assertThat(result.sourceReference().observationId()).isNull(); verifyNoInteractions(spans);
    }
    @Test void checkedInputFreezesMetadataAndRetainsBodyCapturePolicy() {
        observation(7L, "Trace-A", "Span-A");
        var result = service.create("ds", request(true, 7L, " Trace-A ", " Span-A "));
        assertThat(result.sourceReference().state()).isEqualTo("OBSERVATION_MATCHED");
        assertThat(result.sourceReference().observationId()).isEqualTo(91L);
        assertThat(result.sourceReference().endpointKey()).isEqualTo("fixture-endpoint");
        assertThat(result.sourceReference().checkedAt()).isNotNull();
        assertThat(result.input().path("captureMode").asText()).isEqualTo("HASH_ONLY");
        assertThat(dataset.itemCount).isEqualTo(1);
    }
    @ParameterizedTest @CsvSource({"7,,Span-A", "7,Trace-A,", ",Trace-A,Span-A"})
    void incompleteReferenceDoesNotWrite(Long agent, String trace, String span) {
        assertThatThrownBy(() -> service.create("ds", request(true, agent, trace, span))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(spans); verify(items, never()).save(any()); assertThat(dataset.itemCount).isZero();
    }
    @ParameterizedTest @CsvSource({"8,Trace-A,Span-A", ",Trace-A,Span-A", "7,trace-a,Span-A", "7,Trace-A,span-a"})
    void wrongAgentUnattributedOrCollationAliasNeverBinds(Long agent, String trace, String span) {
        observation(agent, trace, span);
        assertThatThrownBy(() -> service.create("ds", request(true, 7L, "Trace-A", "Span-A"))).isInstanceOf(BusinessException.class);
        verify(items, never()).save(any()); assertThat(dataset.itemCount).isZero();
    }
    @Test void absentObservationDoesNotWrite() {
        assertThatThrownBy(() -> service.create("ds", request(true, 7L, "Trace-A", "Span-A"))).isInstanceOf(BusinessException.class);
        verify(items, never()).save(any()); assertThat(dataset.itemCount).isZero();
    }
}
