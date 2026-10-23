package com.eap.platform.evaluation;

import com.eap.platform.common.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.datasource.url=jdbc:h2:mem:eap_scan_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "eap.workflow.execution.worker-enabled=false", "eap.evaluation.async-worker-enabled=false"})
@ActiveProfiles("test")
class EvaluationDurableScanIntegrationTests {
    @Autowired EvaluationProjectionService projection;
    @Autowired EvaluationScanCoordinator coordinator;
    @Autowired EvaluationJobService jobs;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void reset() {
        jdbc.update("delete from eap_evaluation_results");
        jdbc.update("delete from tool_invocation_events");
        jdbc.update("delete from trace_span_observations");
        jdbc.update("delete from knowledge_interactions");
        jdbc.update("delete from eap_evaluation_jobs");
        jdbc.update("update eap_evaluation_scan_state set tool_cursor=0, tool_upper=0, span_cursor=0, span_upper=0, rag_cursor=0, rag_upper=0, lease_owner=null, lease_until=null where id=1");
    }

    @Test void scansBeyondFiveHundredAndRevisitsLateMutationsWithoutDuplicateResults() {
        for (int i = 0; i < 530; i++) tool("tool-" + i, i == 0 ? "REQUESTED" : "SUCCEEDED");
        assertThat(projection.synchronize(200).scanned()).isEqualTo(200);
        assertThat(projection.synchronize(200).scanned()).isEqualTo(200);
        assertThat(projection.synchronize(200).scanned()).isEqualTo(130);
        assertThat(countResults()).isEqualTo(529);
        jdbc.update("update tool_invocation_events set status='FAILED' where invocation_key='tool-0'");
        projection.synchronize(200);
        projection.synchronize(200);
        projection.synchronize(200);
        assertThat(countResults()).isEqualTo(530);
        jdbc.update("update tool_invocation_events set status='FAILED' where invocation_key='tool-1'");
        projection.synchronize(200);
        assertThat(countResults()).isEqualTo(531);
    }

    @Test void readsWholeTraceAcrossPageAndRejectsConflictingAndMissingCompletionEvidence() {
        span("shared", "first", "{\"eap.task.completed\":true}");
        for (int i = 0; i < 510; i++) span("shared", "middle-" + i, "{}");
        span("shared", "last", "{\"eap.task.completed\":false}");
        assertThat(projection.synchronize(1).evaluated()).isZero();
        assertThat(countResults()).isZero();
        jdbc.update("delete from trace_span_observations where span_id='last'");
        assertThat(projection.synchronize(1).evaluated()).isEqualTo(1);
        String evidence = jdbc.queryForObject("select evidence_json from eap_evaluation_results", String.class);
        assertThat(evidence).contains("511");
        jdbc.update("update trace_span_observations set attributes_json='{}' where span_id='first'");
        assertThat(projection.synchronize(1).evaluated()).isZero();
        assertThat(countResults()).isEqualTo(1);
    }

    @Test void failedRecordDoesNotBlockFollowingRecordsAndIsRetriedNextSweep() {
        tool("broken", "SUCCEEDED"); tool("valid", "SUCCEEDED");
        jdbc.update("update tool_invocation_events set agent_id=999999 where invocation_key='broken'");
        var result = projection.synchronize(10);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.evaluated()).isEqualTo(1);
        jdbc.update("update tool_invocation_events set agent_id=null where invocation_key='broken'");
        assertThat(projection.synchronize(10).failed()).isZero();
        assertThat(countResults()).isEqualTo(2);
    }

    @Test void fixedHighWaterRevisitsOldRowsDespiteContinuousInserts() {
        var first = coordinator.acquire(10, 20, 30);
        coordinator.advance(first.owner(), "TOOLS", 5);
        coordinator.release(first.owner());
        var second = coordinator.acquire(100, 200, 300);
        assertThat(second.tools()).isEqualTo(new EvaluationScanCoordinator.Window(5, 10));
        coordinator.advance(second.owner(), "TOOLS", 10);
        coordinator.release(second.owner());
        var third = coordinator.acquire(100, 200, 300);
        assertThat(third.tools()).isEqualTo(new EvaluationScanCoordinator.Window(0, 100));
        coordinator.release(third.owner());
    }

    @Test void expiredOwnerCannotAdvanceOrReleaseNewOwner() {
        var old = coordinator.acquire(10, 10, 10);
        jdbc.update("update eap_evaluation_scan_state set lease_until=?", java.sql.Timestamp.from(Instant.now().minusSeconds(1)));
        var current = coordinator.acquire(10, 10, 10);
        assertThatThrownBy(() -> coordinator.advance(old.owner(), "TOOLS", 9)).isInstanceOf(ConflictException.class);
        coordinator.release(old.owner());
        assertThatThrownBy(() -> coordinator.acquire(10, 10, 10)).isInstanceOf(ConflictException.class);
        coordinator.release(current.owner());
    }

    @Test void concurrentScannersHaveOneOwner() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) futures.add(pool.submit(() -> {
                start.await();
                try { return coordinator.acquire(1, 1, 1).owner(); }
                catch (ConflictException expected) { return null; }
            }));
            start.countDown();
            List<String> owners = new ArrayList<>();
            for (var future : futures) { var owner = future.get(10, TimeUnit.SECONDS); if (owner != null) owners.add(owner); }
            assertThat(owners).hasSize(1);
            coordinator.release(owners.getFirst());
        }
    }

    @Test void jobPublishesProgressAndSeparatesPartialFailureFromSuccess() {
        tool("invalid-owner", "SUCCEEDED");
        jdbc.update("update tool_invocation_events set agent_id=999999");
        var submitted = jobs.submit(10);
        jobs.processNext();
        var finished = jobs.get(submitted.id());
        assertThat(finished.status()).isEqualTo(EvaluationJobStatus.PARTIAL);
        assertThat(finished.phase()).isEqualTo("COMPLETE");
        assertThat(finished.processed()).isEqualTo(finished.total()).isEqualTo(1);
        assertThat(finished.failed()).isEqualTo(1);
    }

    private int countResults() { return jdbc.queryForObject("select count(*) from eap_evaluation_results", Integer.class); }
    private void tool(String key, String status) {
        jdbc.update("insert into tool_invocation_events (invocation_key, observation_source, tool_kind, tool_name, status, duration_ms, observed_at, created_at, updated_at) values (?, 'OTLP', 'TOOL', 'lookup', ?, 10, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", key, status);
    }
    private void span(String trace, String id, String attributes) {
        jdbc.update("insert into trace_span_observations (trace_id, span_id, endpoint_key, span_name, span_kind, status, duration_ms, attributes_json, observed_at, created_at) values (?, ?, 'test', 'step', 'AGENT', 'OK', 10, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", trace, id, attributes);
    }
}
