package com.eap.platform.discovery;

import com.eap.platform.evaluation.EvaluationResultRepository;
import com.eap.platform.observability.TraceSpanObservationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "eap.workflow.execution.worker-enabled=false",
        "eap.evaluation.async-worker-enabled=false"})
@ActiveProfiles("test")
class ContentAnalysisProjectionIntegrationTests {
    @Autowired BusinessContentRecordRepository records;
    @Autowired ContentAnalysisProjectionService service;
    @Autowired TraceSpanObservationRepository spans;
    @Autowired EvaluationResultRepository results;

    @Test
    void concurrentProjectionIsAtomicIdempotentAndDoesNotCopyPrivateContent() throws Exception {
        var record = record("OUTPUT", "{\"outcome\":\"SUCCEEDED\",\"latencyMs\":123,\"usage\":{\"prompt_tokens\":12},\"errorMessage\":\"PRIVATE_MARKER\",\"prompt\":\"PRIVATE_MARKER\"}");
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Void> run = () -> { start.await(); service.project(record.id); return null; };
            var first = pool.submit(run);
            var second = pool.submit(run);
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }
        service.project(record.id);
        var span = spans.findByTraceIdAndSpanId(record.traceId, "eap-content-" + record.id).orElseThrow();
        assertThat(span.getDurationMs()).isEqualTo(123);
        assertThat(span.getAttributesJson()).contains("CONTENT_PROXY", "prompt_tokens").doesNotContain("PRIVATE_MARKER");
        assertThat(span.getAttributesJson()).contains("eap.projection.original_span_id", "original-span");
        var evaluations = results.findTop500ByTraceIdOrderByEvaluatedAtDesc(record.traceId);
        assertThat(evaluations).hasSize(1);
        assertThat(evaluations.getFirst().isPassed()).isTrue();
        assertThat(evaluations.getFirst().getRationale()).contains("业务任务完成度和回答质量未知");
        assertThat(records.findById(record.id).orElseThrow().projectionStatus).isEqualTo("DONE");
        service.recordFailure(record.id, "LateFailure");
        assertThat(records.findById(record.id).orElseThrow().projectionAttempts).isZero();
    }

    @Test
    void failedHttpStatusOverridesSuccessCallback() {
        var record = record("OUTPUT", "{\"outcome\":\"SUCCEEDED\",\"httpStatus\":500}");
        service.project(record.id);
        assertThat(results.findTop500ByTraceIdOrderByEvaluatedAtDesc(record.traceId).getFirst().isPassed()).isFalse();
        assertThat(spans.findByTraceIdAndSpanId(record.traceId, "eap-content-" + record.id).orElseThrow().getStatus()).isEqualTo("FAILED");
    }

    @Test
    void unknownOrMalformedMetadataCreatesUnknownSpanWithoutInventingScore() {
        for (String metadata : new String[]{"{}", "{broken", "null", "{\"httpStatus\":999,\"latencyMs\":-10}", "{\"usage\":{\"total_tokens\":100}}"}) {
            var record = record("OUTPUT", metadata);
            service.project(record.id);
            assertThat(results.findTop500ByTraceIdOrderByEvaluatedAtDesc(record.traceId)).isEmpty();
            assertThat(spans.findByTraceIdAndSpanId(record.traceId, "eap-content-" + record.id).orElseThrow().getStatus()).isEqualTo("UNKNOWN");
            assertThat(records.findById(record.id).orElseThrow().projectionError).isEqualTo("UNKNOWN_OUTCOME_NO_SCORE");
        }
    }

    @Test
    void ignoresInputAndSkipsOutputWithoutTraceIdentity() {
        var input = record("INPUT", "{}");
        service.project(input.id);
        assertThat(spans.findByTraceIdAndSpanId(input.traceId, "eap-content-" + input.id)).isEmpty();
        var output = record("OUTPUT", "{}");
        output.traceId = null;
        records.save(output);
        service.project(output.id);
        assertThat(records.findById(output.id).orElseThrow().projectionStatus).isEqualTo("SKIPPED");
        assertThat(records.findPendingProjectionIds(org.springframework.data.domain.PageRequest.of(0, 500))).doesNotContain(input.id, output.id);
    }

    @Test
    void analysisFailureRollsBackSpanButPreservesCaptureAndStopsAfterThreeAttempts() {
        var record = record("OUTPUT", "{\"outcome\":\"SUCCEEDED\"}");
        record.agentId = Long.MAX_VALUE;
        records.save(record);
        var worker = new ContentAnalysisProjectionWorker(records, service);
        for (int i = 0; i < 4; i++) worker.process();
        var saved = records.findById(record.id).orElseThrow();
        assertThat(saved.projectionStatus).isEqualTo("FAILED");
        assertThat(saved.projectionAttempts).isEqualTo(3);
        assertThat(saved.redactedContent).contains("PRIVATE_MARKER");
        assertThat(saved.projectionError).isEqualTo("BusinessException");
        assertThat(spans.findByTraceIdAndSpanId(record.traceId, "eap-content-" + record.id)).isEmpty();
        assertThat(results.findTop500ByTraceIdOrderByEvaluatedAtDesc(record.traceId)).isEmpty();
    }

    private BusinessContentRecord record(String direction, String metadata) {
        BusinessContentRecord record = new BusinessContentRecord();
        record.observationKey = "projection-test-" + UUID.randomUUID();
        record.traceId = record.observationKey;
        record.spanId = "original-span";
        record.tenantId = "test";
        record.endpointKey = "test-projection-endpoint";
        record.direction = direction;
        record.contentHash = "a".repeat(64);
        record.contentBytes = 14L;
        record.redactedContent = "PRIVATE_MARKER";
        record.metadataJson = metadata;
        record.observedAt = Instant.now();
        return records.save(record);
    }
}
