package com.eap.platform.evaluation;

import com.eap.platform.observability.ToolInvocationEvent;
import com.eap.platform.observability.ToolInvocationEventRepository;
import com.eap.platform.observability.ToolInvocationStatus;
import com.eap.platform.observability.TraceSpanKind;
import com.eap.platform.observability.TraceSpanObservation;
import com.eap.platform.observability.TraceSpanObservationRepository;
import com.eap.platform.knowledge.KnowledgeInteraction;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationProjectionServiceTests {
    @Mock
    private ToolInvocationEventRepository toolRepository;
    @Mock
    private TraceSpanObservationRepository traceRepository;
    @Mock
    private com.eap.platform.knowledge.KnowledgeInteractionRepository knowledgeRepository;
    @Mock
    private EvaluationService evaluationService;

    @Mock private EvaluationScanCoordinator coordinator;
    private EvaluationProjectionService service;

    @BeforeEach
    void setUp() {
        when(coordinator.acquire(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(new EvaluationScanCoordinator.Lease("test", new EvaluationScanCoordinator.Window(0, 1000),
                        new EvaluationScanCoordinator.Window(0, 1000), new EvaluationScanCoordinator.Window(0, 1000)));
        org.mockito.Mockito.lenient().when(traceRepository.findByTraceIdOrderByIdAsc(any(), any())).thenAnswer(call -> {
            var page = traceRepository.findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(0, 1000, org.springframework.data.domain.PageRequest.of(0, 200));
            return page.stream().filter(item -> item.getTraceId().equals(call.getArgument(0))).toList();
        });
        service = new EvaluationProjectionService(toolRepository, traceRepository, knowledgeRepository,
                evaluationService, new ObjectMapper(), coordinator);
    }

    @Test
    void projectsTerminalToolObservationIntoStatusEvaluation() {
        ToolInvocationEvent event = event(41L, ToolInvocationStatus.SUCCEEDED, "{\"requiredArguments\":[\"accountId\"],\"arguments\":{\"accountId\":\"a-1\"}}");
        when(toolRepository.findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of(event));

        EvaluationDtos.EvaluationSyncResponse response = service.synchronize(200);

        assertThat(response.scanned()).isEqualTo(1);
        assertThat(response.evaluated()).isEqualTo(2);
        assertThat(response.failed()).isZero();
        verify(evaluationService, times(2)).evaluate(any());
    }

    @Test
    void skipsPendingToolObservation() {
        ToolInvocationEvent event = event(42L, ToolInvocationStatus.REQUESTED, null);
        when(toolRepository.findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of(event));

        EvaluationDtos.EvaluationSyncResponse response = service.synchronize(1);

        assertThat(response.scanned()).isEqualTo(1);
        assertThat(response.evaluated()).isZero();
        assertThat(response.skipped()).isEqualTo(1);
        verify(evaluationService, times(0)).evaluate(any());
    }

    @Test
    void projectsTraceIntoActionCompletionUsingExplicitTaskSignal() {
        TraceSpanObservation model = span("trace-77", "span-model", TraceSpanKind.MODEL, "OK",
                "{\"eap.task.completed\":\"true\"}", 300L);
        TraceSpanObservation tool = span("trace-77", "span-tool", TraceSpanKind.TOOL, "SUCCEEDED", "{}", 120L);
        when(traceRepository.findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of(tool, model));

        EvaluationDtos.EvaluationSyncResponse response = service.synchronize(200);

        assertThat(response.evaluated()).isEqualTo(1);
        assertThat(response.failed()).isZero();
        verify(evaluationService, times(1)).evaluate(any());
    }

    @Test
    void projectsRagInteractionIntoContextCoverage() {
        KnowledgeInteraction interaction = new KnowledgeInteraction();
        ReflectionTestUtils.setField(interaction, "id", 1L);
        ReflectionTestUtils.setField(interaction, "interactionKey", "rag-1");
        ReflectionTestUtils.setField(interaction, "traceId", "trace-rag-1");
        ReflectionTestUtils.setField(interaction, "agentId", 7L);
        ReflectionTestUtils.setField(interaction, "inputType", "RAG_QUERY");
        ReflectionTestUtils.setField(interaction, "outputType", "RAG_CONTEXT");
        ReflectionTestUtils.setField(interaction, "inputSourceCount", 2);
        ReflectionTestUtils.setField(interaction, "outputSourceCount", 1);
        ReflectionTestUtils.setField(interaction, "inputRefsJson", "[\"doc-1\",\"doc-2\"]");
        ReflectionTestUtils.setField(interaction, "outputRefsJson", "[\"doc-1\"]");
        ReflectionTestUtils.setField(interaction, "status", com.eap.platform.knowledge.KnowledgeInteractionStatus.SUCCEEDED);
        ReflectionTestUtils.setField(interaction, "adopted", true);
        ReflectionTestUtils.setField(interaction, "createdAt", Instant.parse("2026-09-02T00:00:00Z"));
        when(knowledgeRepository.findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of(interaction));

        EvaluationDtos.EvaluationSyncResponse response = service.synchronize(200);

        assertThat(response.evaluated()).isEqualTo(1);
        verify(evaluationService, atLeastOnce()).evaluate(argThat(request ->
                "RAG_CONTEXT_COVERAGE".equals(request.evaluatorKey())
                        && request.actual().path("referenceOverlapCount").asInt() == 1));
    }

    @Test
    void contentDerivedModelAndSuccessfulToolsDoNotImplyTaskCompletion() {
        var model = span("content-trace", "content-span", TraceSpanKind.MODEL, "SUCCEEDED",
                "{\"eap.projection.source\":\"CONTENT_PROXY\"}", 100L);
        var tool = span("content-trace", "tool-span", TraceSpanKind.TOOL, "SUCCEEDED", "{}", 10L);
        when(traceRepository.findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of(model, tool));
        var response = service.synchronize(200);
        assertThat(response.evaluated()).isZero();
        assertThat(response.skipped()).isEqualTo(1);
        verify(evaluationService, times(0)).evaluate(any());
    }

    @Test
    void contentDerivedModelStillRespectsExplicitTaskFailure() {
        var model = span("content-trace", "content-span", TraceSpanKind.MODEL, "SUCCEEDED",
                "{\"eap.projection.source\":\"CONTENT_PROXY\"}", 100L);
        var agent = span("content-trace", "agent-span", TraceSpanKind.AGENT, "OK",
                "{\"eap.task.completed\":false}", 200L);
        when(traceRepository.findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of(model, agent));
        assertThat(service.synchronize(200).evaluated()).isEqualTo(1);
        verify(evaluationService).evaluate(argThat(request -> !request.actual().path("completed").asBoolean()
                && "EXPLICIT".equals(request.actual().path("inferenceMode").asText())));
    }

    @Test
    void successfulModelWithoutTaskSignalIsNotTaskCompletion() {
        var model = span("no-signal", "model", TraceSpanKind.MODEL, "SUCCEEDED", "{}", 100L);
        when(traceRepository.findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of(model));
        assertThat(service.synchronize(200).skipped()).isEqualTo(1);
        verify(evaluationService, times(0)).evaluate(any());
    }

    @Test
    void refusesOversizeTraceRatherThanEvaluatingTruncatedEvidence() {
        var model = span("oversize", "model", TraceSpanKind.AGENT, "OK", "{\"eap.task.completed\":true}", 100L);
        when(traceRepository.findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of(model));
        when(traceRepository.findByTraceIdOrderByIdAsc(any(), any())).thenReturn(java.util.Collections.nCopies(2001, model));
        assertThat(service.synchronize(200).skipped()).isEqualTo(1);
        verify(evaluationService, times(0)).evaluate(any());
    }

    @Test
    void ambiguousAgentAttributionDoesNotCreditTheLatestAgent() {
        var a = span("multi", "a", TraceSpanKind.AGENT, "OK", "{\"eap.task.completed\":true}", 100L);
        var b = span("multi", "b", TraceSpanKind.AGENT, "OK", "{}", 100L);
        ReflectionTestUtils.setField(b, "agentId", 8L);
        when(traceRepository.findByIdGreaterThanAndIdLessThanEqualOrderByIdAsc(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), any())).thenReturn(List.of(a, b));
        assertThat(service.synchronize(200).skipped()).isEqualTo(1);
        verify(evaluationService, times(0)).evaluate(any());
    }

    private ToolInvocationEvent event(Long id, ToolInvocationStatus status, String attributes) {
        ToolInvocationEvent event = new ToolInvocationEvent();
        ReflectionTestUtils.setField(event, "id", id);
        ReflectionTestUtils.setField(event, "agentId", 7L);
        ReflectionTestUtils.setField(event, "traceId", "trace-" + id);
        ReflectionTestUtils.setField(event, "spanId", "span-" + id);
        ReflectionTestUtils.setField(event, "toolName", "lookup-account");
        ReflectionTestUtils.setField(event, "status", status);
        ReflectionTestUtils.setField(event, "attributesJson", attributes);
        ReflectionTestUtils.setField(event, "durationMs", 120L);
        ReflectionTestUtils.setField(event, "observedAt", Instant.parse("2026-09-02T00:00:00Z"));
        return event;
    }

    private TraceSpanObservation span(String traceId, String spanId, TraceSpanKind kind, String status,
                                      String attributes, Long durationMs) {
        TraceSpanObservation span = new TraceSpanObservation();
        ReflectionTestUtils.setField(span, "id", (long) Math.abs(spanId.hashCode()));
        ReflectionTestUtils.setField(span, "traceId", traceId);
        ReflectionTestUtils.setField(span, "spanId", spanId);
        ReflectionTestUtils.setField(span, "agentId", 7L);
        ReflectionTestUtils.setField(span, "spanKind", kind);
        ReflectionTestUtils.setField(span, "status", status);
        ReflectionTestUtils.setField(span, "attributesJson", attributes);
        ReflectionTestUtils.setField(span, "durationMs", durationMs);
        ReflectionTestUtils.setField(span, "observedAt", Instant.parse("2026-09-02T00:00:00Z"));
        return span;
    }
}
