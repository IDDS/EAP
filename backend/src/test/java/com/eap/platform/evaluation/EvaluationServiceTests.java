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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationServiceTests {
    @Mock
    private EvaluatorDefinitionRepository evaluatorRepository;
    @Mock
    private EvaluationDatasetRepository datasetRepository;
    @Mock
    private EvaluationResultRepository resultRepository;
    @Mock
    private AgentRepository agentRepository;

    private EvaluationService service;
    private ObservationProperties observationProperties;

    @BeforeEach
    void setUp() {
        observationProperties = new ObservationProperties();
        service = new EvaluationService(evaluatorRepository, datasetRepository, resultRepository,
                agentRepository, new ObjectMapper(), observationProperties, new SensitiveContentSanitizer());
    }

    @Test
    void evaluatesActionCompletionWithoutCallingExternalModel() throws Exception {
        EvaluatorDefinition evaluator = evaluator("ACTION_COMPLETION", "ACTION_COMPLETION", EvaluationLevel.SESSION,
                new BigDecimal("0.500000"));
        when(evaluatorRepository.findByEvaluatorKey("ACTION_COMPLETION")).thenReturn(Optional.of(evaluator));
        when(resultRepository.save(any())).thenAnswer(invocation -> {
            EvaluationResult result = invocation.getArgument(0);
            result.id = 7L;
            result.createdAt = result.evaluatedAt;
            return result;
        });

        EvaluationDtos.EvaluationResultResponse response = service.evaluate(new EvaluationDtos.EvaluationRequest(
                "result-1", "ACTION_COMPLETION", "v1", null, null, "trace-1", "span-1",
                new ObjectMapper().readTree("{}"), null, new ObjectMapper().readTree("{\"completed\":true}"), null
        ));

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.score()).isEqualByComparingTo("1.000000");
        assertThat(response.passed()).isTrue();
        assertThat(response.rationale()).contains("满足");
    }

    @Test
    void reportsMissingToolArgumentsWithEvidence() throws Exception {
        EvaluatorDefinition evaluator = evaluator("TOOL_ARGUMENT_VALIDITY", "TOOL_ARGUMENT_VALIDITY", EvaluationLevel.SPAN,
                BigDecimal.ONE);
        when(evaluatorRepository.findByEvaluatorKey("TOOL_ARGUMENT_VALIDITY")).thenReturn(Optional.of(evaluator));
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EvaluationDtos.EvaluationResultResponse response = service.evaluate(new EvaluationDtos.EvaluationRequest(
                null, "TOOL_ARGUMENT_VALIDITY", null, null, null, null, null,
                new ObjectMapper().readTree("{\"requiredArguments\":[\"accountId\",\"region\"]}"), null,
                new ObjectMapper().readTree("{\"arguments\":{\"accountId\":\"a-1\"}}"), null
        ));

        assertThat(response.score()).isEqualByComparingTo("0.500000");
        assertThat(response.passed()).isFalse();
        assertThat(response.evidence().path("missing").asInt()).isEqualTo(1);
        assertThat(response.evidence().path("missingNames").get(0).asText()).isEqualTo("region");
    }

    @Test
    void enforcesCostBudgetAndKeepsResultIdempotent() throws Exception {
        EvaluatorDefinition evaluator = evaluator("COST_BUDGET", "COST_BUDGET", EvaluationLevel.TRACE,
                new BigDecimal("0.500000"));
        when(evaluatorRepository.findByEvaluatorKey("COST_BUDGET")).thenReturn(Optional.of(evaluator));
        when(resultRepository.findByResultKey("cost-result")).thenReturn(Optional.empty());
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EvaluationDtos.EvaluationResultResponse response = service.evaluate(new EvaluationDtos.EvaluationRequest(
                "cost-result", "COST_BUDGET", "v1", null, null, null, null,
                new ObjectMapper().readTree("{\"maxCost\":2.0}"), null,
                new ObjectMapper().readTree("{\"cost\":3.0}"), null
        ));

        assertThat(response.score()).isEqualByComparingTo("0.500000");
        assertThat(response.passed()).isTrue();
        assertThat(response.evidence().path("cost").asDouble()).isEqualTo(3.0);
    }

    @Test
    void returnsSameResultOnlyForTheSameRequestFingerprint() throws Exception {
        EvaluatorDefinition evaluator = evaluator("ACTION_COMPLETION", "ACTION_COMPLETION", EvaluationLevel.SESSION,
                new BigDecimal("0.500000"));
        when(evaluatorRepository.findByEvaluatorKey("ACTION_COMPLETION")).thenReturn(Optional.of(evaluator));
        when(resultRepository.findByResultKey("stable-result")).thenReturn(Optional.empty());
        when(resultRepository.save(any())).thenAnswer(invocation -> {
            EvaluationResult result = invocation.getArgument(0);
            result.id = 42L;
            result.createdAt = result.evaluatedAt;
            return result;
        });

        EvaluationDtos.EvaluationRequest request = new EvaluationDtos.EvaluationRequest(
                "stable-result", "ACTION_COMPLETION", "v1", null, null, null, null,
                new ObjectMapper().readTree("{}"), null,
                new ObjectMapper().readTree("{\"completed\":true}"), null);
        EvaluationDtos.EvaluationResultResponse first = service.evaluate(request);

        EvaluationResult existing = new EvaluationResult();
        existing.id = first.id();
        existing.resultKey = "stable-result";
        existing.evaluatorId = evaluator.id;
        existing.evaluatorVersion = evaluator.version;
        existing.requestFingerprint = "different";
        existing.score = BigDecimal.ONE.setScale(6);
        existing.confidence = BigDecimal.ONE.setScale(6);
        existing.passed = true;
        existing.level = evaluator.level;
        existing.status = EvaluationStatus.COMPLETED;
        existing.evaluatedAt = Instant.now();
        when(resultRepository.findByResultKey("stable-result")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.evaluate(new EvaluationDtos.EvaluationRequest(
                "stable-result", "ACTION_COMPLETION", "v1", null, null, null, null,
                new ObjectMapper().readTree("{}"), null,
                new ObjectMapper().readTree("{\"completed\":false}"), null)))
                .isInstanceOf(com.eap.platform.common.BusinessException.class)
                .hasMessageContaining("不同的评估请求");
    }

    @Test
    void persistsOnlyHashWhenDefaultObservationModeIsHashOnly() throws Exception {
        EvaluatorDefinition evaluator = evaluator("PII_POLICY", "PII_POLICY", EvaluationLevel.SPAN, BigDecimal.ONE);
        when(evaluatorRepository.findByEvaluatorKey("PII_POLICY")).thenReturn(Optional.of(evaluator));
        ArgumentCaptor<EvaluationResult> captor = ArgumentCaptor.forClass(EvaluationResult.class);
        when(resultRepository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluate(new EvaluationDtos.EvaluationRequest(
                null, "PII_POLICY", null, null, null, null, null,
                new ObjectMapper().readTree("{\"message\":\"internal employee data\"}"), null,
                new ObjectMapper().readTree("{\"containsPii\":false}"), null
        ));

        assertThat(captor.getValue().actualJson).contains("HASH_ONLY").doesNotContain("containsPii");
        assertThat(captor.getValue().inputJson).contains("HASH_ONLY").doesNotContain("internal employee data");
    }

    @Test
    void fingerprintsDifferentRequestsEvenWhenObservationIsOff() throws Exception {
        observationProperties.setCaptureMode(com.eap.platform.observability.ContentCaptureMode.OFF);
        EvaluatorDefinition evaluator = evaluator("ACTION_COMPLETION", "ACTION_COMPLETION", EvaluationLevel.SESSION,
                new BigDecimal("0.500000"));
        when(evaluatorRepository.findByEvaluatorKey("ACTION_COMPLETION")).thenReturn(Optional.of(evaluator));
        when(resultRepository.findByResultKey("off-result")).thenReturn(Optional.empty());
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.evaluate(new EvaluationDtos.EvaluationRequest(
                "off-result", "ACTION_COMPLETION", "v1", null, null, null, null,
                new ObjectMapper().readTree("{\"task\":\"first\"}"), null,
                new ObjectMapper().readTree("{\"completed\":true}"), null));

        EvaluationResult existing = new EvaluationResult();
        existing.evaluatorId = evaluator.id;
        existing.evaluatorVersion = evaluator.version;
        existing.requestFingerprint = "fingerprint-from-first-request";
        when(resultRepository.findByResultKey("off-result")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.evaluate(new EvaluationDtos.EvaluationRequest(
                "off-result", "ACTION_COMPLETION", "v1", null, null, null, null,
                new ObjectMapper().readTree("{\"task\":\"second\"}"), null,
                new ObjectMapper().readTree("{\"completed\":true}"), null)))
                .isInstanceOf(com.eap.platform.common.BusinessException.class)
                .hasMessageContaining("不同的评估请求");
    }

    @Test
    void scoresRagContextCoverageFromTraceableEvidence() throws Exception {
        EvaluatorDefinition evaluator = evaluator("RAG_CONTEXT_COVERAGE", "RAG_CONTEXT_COVERAGE", EvaluationLevel.TRACE,
                new BigDecimal("0.500000"));
        when(evaluatorRepository.findByEvaluatorKey("RAG_CONTEXT_COVERAGE")).thenReturn(Optional.of(evaluator));
        when(resultRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EvaluationDtos.EvaluationResultResponse response = service.evaluate(new EvaluationDtos.EvaluationRequest(
                null, "RAG_CONTEXT_COVERAGE", "v1", null, null, "trace-rag", null,
                new ObjectMapper().readTree("{}"), null,
                new ObjectMapper().readTree("{\"status\":\"SUCCEEDED\",\"inputSourceCount\":4,\"outputSourceCount\":3,\"inputRefsCount\":4,\"outputRefsCount\":3,\"referenceOverlapCount\":3,\"adopted\":true,\"qualityScore\":80}"), null
        ));

        assertThat(response.score()).isGreaterThan(new BigDecimal("0.500000"));
        assertThat(response.passed()).isTrue();
        assertThat(response.evidence().path("coverage").asDouble()).isEqualTo(0.75);
    }

    @Test
    void summarizesRecentResultsByEvaluator() {
        EvaluatorDefinition evaluator = evaluator("TOOL_STATUS", "TOOL_STATUS", EvaluationLevel.SPAN, BigDecimal.ONE);
        evaluator.name = "工具调用结果";
        when(resultRepository.findByEvaluatedAtGreaterThanEqualOrderByEvaluatedAtDesc(any(Instant.class)))
                .thenReturn(List.of(result(true, "1.000000"), result(false, "0.000000")));
        when(evaluatorRepository.findAllById(List.of(1L))).thenReturn(List.of(evaluator));

        EvaluationDtos.EvaluationSummaryResponse response = service.summary(24);

        assertThat(response.total()).isEqualTo(2);
        assertThat(response.passed()).isEqualTo(1);
        assertThat(response.failed()).isEqualTo(1);
        assertThat(response.passRate()).isEqualByComparingTo("0.500000");
        assertThat(response.evaluators()).singleElement().satisfies(item -> {
            assertThat(item.evaluatorKey()).isEqualTo("TOOL_STATUS");
            assertThat(item.failed()).isEqualTo(1);
        });
    }

    private EvaluationResult result(boolean passed, String score) {
        EvaluationResult result = new EvaluationResult();
        result.evaluatorId = 1L;
        result.score = new BigDecimal(score);
        result.confidence = BigDecimal.ONE;
        result.passed = passed;
        result.evaluatedAt = Instant.parse("2026-09-02T00:00:00Z");
        return result;
    }

    private EvaluatorDefinition evaluator(String key, String implementation, EvaluationLevel level, BigDecimal threshold) {
        EvaluatorDefinition evaluator = new EvaluatorDefinition();
        evaluator.id = 1L;
        evaluator.evaluatorKey = key;
        evaluator.version = "v1";
        evaluator.implementationKey = implementation;
        evaluator.level = level;
        evaluator.evaluatorType = EvaluatorType.RULE;
        evaluator.threshold = threshold;
        evaluator.enabled = true;
        return evaluator;
    }
}
