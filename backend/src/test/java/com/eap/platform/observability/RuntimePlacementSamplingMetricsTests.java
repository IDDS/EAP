package com.eap.platform.observability;

import com.eap.platform.deployment.RuntimePlacementService;
import com.eap.platform.registry.AgentRuntimeInstance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import static com.eap.platform.observability.RuntimePlacementSamplingMetrics.Event.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimePlacementSamplingMetricsTests {
    final RuntimePlacementEvidenceStore store = mock(RuntimePlacementEvidenceStore.class);
    final RuntimePlacementService positions = mock(RuntimePlacementService.class);
    final RuntimePlacementEvidenceStore.Phase phase = RuntimePlacementEvidenceStore.Phase.RECEIPT_RETURNED;
    RuntimePlacementEvidenceStore.Request request() {
        return new RuntimePlacementEvidenceStore.Request("PRIVATE_TASK", "PRIVATE_TRACE", 1L, 2L,
                "PRIVATE_INSTANCE", "PRIVATE_ENDPOINT", "PRIVATE_UID", "PRIVATE_TEMPLATE", phase, Instant.now());
    }
    RuntimePlacementService.Snapshot snapshot(String state) {
        return new RuntimePlacementService.Snapshot("PRIVATE_INSTANCE", state, "PRIVATE_REASON", null,
                Instant.now(), Instant.now(), "PRIVATE_UID", null, null, null);
    }

    @ParameterizedTest @ValueSource(strings = {"ASSIGNED", "UNASSIGNED", "UNKNOWN"})
    void onlyConfirmedWritesCountAsRecordedAndSnapshotsContainNoTaskIdentity(String state) throws Exception {
        var service = new RuntimePlacementEvidenceService(store, positions);
        try {
            var request = request(); var claim = new RuntimePlacementEvidenceStore.Claim(7, request);
            when(store.claim(request)).thenReturn(claim);
            when(positions.currentForBinding(any(), any(), any(), any())).thenReturn(snapshot(state));
            when(store.complete(eq(claim), any())).thenReturn(true);
            service.capture(request);
            var sampling = service.sampling();
            assertThat(sampling.phases().get(1).events()).containsEntry(
                    RuntimePlacementSamplingMetrics.Event.valueOf("RECORDED_" + state), 1L).containsEntry(QUERY_FAILED, 0L);
            assertThat(sampling.phases().getFirst().events().values()).allMatch(n -> n == 0);
            assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(sampling)).doesNotContain("PRIVATE_");
            verify(positions, times(1)).currentForBinding(any(), any(), any(), any());
            verify(store, times(1)).complete(eq(claim), any());
        } finally { service.close(); }
    }

    @Test void failedCommitAndNoopCompletionAreNotReportedAsSuccessfulOrRetried() {
        var service = new RuntimePlacementEvidenceService(store, positions);
        try {
            var request = request(); var claim = new RuntimePlacementEvidenceStore.Claim(7, request);
            when(store.claim(request)).thenReturn(claim);
            when(positions.currentForBinding(any(), any(), any(), any())).thenReturn(snapshot("ASSIGNED"));
            when(store.complete(eq(claim), any())).thenReturn(false).thenThrow(new IllegalStateException("PRIVATE_STORAGE"));
            service.capture(request); service.capture(request);
            assertThat(service.sampling().phases().get(1).events()).containsEntry(NOT_RECORDED, 1L)
                    .containsEntry(WRITE_UNCONFIRMED, 1L).containsEntry(RECORDED_ASSIGNED, 0L);
            verify(store, times(2)).complete(eq(claim), any());
            verify(positions, times(2)).currentForBinding(any(), any(), any(), any());
        } finally { service.close(); }
    }

    @Test void ineligibleRequestsAndRepeatedReadsDoNotQueryOrCreateSamplingWork() {
        var runtime = mock(AgentRuntimeInstance.class);
        when(runtime.getProtocol()).thenReturn("HTTP");
        var service = new RuntimePlacementEvidenceService(store, positions);
        try {
            service.request("private", "trace", runtime, phase);
            when(runtime.getProtocol()).thenReturn("SUBSTRATE");
            service.request("private", "trace", runtime, phase);
            when(runtime.hasBoundIdentity()).thenReturn(true);
            service.request("private", null, runtime, phase);
            for (int n = 0; n < 3; n++) {
                var sampling = service.sampling();
                assertThat(sampling.phases()).allSatisfy(p -> assertThat(p.events().values()).allMatch(v -> v == 0));
                assertThat(sampling.queueDepth()).isZero();
            }
            verifyNoInteractions(store, positions);
        } finally { service.close(); }
    }

    @Test void concurrentIncrementsAreNotLostAndRestartBeginsANewCountingPeriod() throws Exception {
        var metrics = new RuntimePlacementSamplingMetrics();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<Callable<Void>>();
            for (int n = 0; n < 4; n++) jobs.add(() -> {
                for (int i = 0; i < 1000; i++) metrics.record(phase, REJECTED);
                return null;
            });
            for (var result : pool.invokeAll(jobs)) result.get();
        }
        var original = metrics.snapshot(0, 0, 32, 2);
        var restarted = new RuntimePlacementSamplingMetrics().snapshot(0, 0, 32, 2);
        assertThat(original.phases().get(1).events()).containsEntry(REJECTED, 4000L);
        assertThat(restarted.processStartedAt()).isAfterOrEqualTo(original.processStartedAt());
        assertThat(restarted.phases()).allSatisfy(p -> assertThat(p.events().values()).allMatch(v -> v == 0));
        assertThatThrownBy(() -> original.phases().get(1).events().put(REJECTED, 0L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
