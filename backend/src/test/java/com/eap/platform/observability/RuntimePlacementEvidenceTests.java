package com.eap.platform.observability;

import com.eap.platform.deployment.RuntimePlacementService;
import com.eap.platform.registry.AgentRuntimeInstance;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimePlacementEvidenceTests {
    final RuntimePlacementEvidenceStore store = mock(RuntimePlacementEvidenceStore.class);
    final RuntimePlacementService positions = mock(RuntimePlacementService.class);
    RuntimePlacementEvidenceStore.Request request() {
        return new RuntimePlacementEvidenceStore.Request("task", "trace", 1L, 2L, "space/actor", "substrate://space/actor",
                "uid", "template", RuntimePlacementEvidenceStore.Phase.DISPATCH_REQUESTED, Instant.now());
    }
    @Test void upstreamFailureIsRecordedWithoutExceptionTextOrTaskMutation() {
        var service = new RuntimePlacementEvidenceService(store, positions);
        try {
            var request = request(); var claim = new RuntimePlacementEvidenceStore.Claim(7, request);
            when(store.claim(request)).thenReturn(claim);
            when(store.complete(eq(claim), any())).thenReturn(true);
            when(positions.currentForBinding(any(), any(), any(), any())).thenThrow(new IllegalStateException("private upstream"));
            service.capture(request);
            var value = org.mockito.ArgumentCaptor.forClass(RuntimePlacementService.Snapshot.class);
            verify(store).complete(eq(claim), value.capture());
            assertThat(value.getValue().status()).isEqualTo("UNKNOWN");
            assertThat(value.getValue().reason()).isEqualTo("CAPTURE_FAILED");
            assertThat(value.getValue().toString()).doesNotContain("private upstream");
            assertThat(value.getValue().placement()).isNull();
            assertThat(service.sampling().phases().getFirst().events())
                    .containsEntry(RuntimePlacementSamplingMetrics.Event.QUERY_FAILED, 1L)
                    .containsEntry(RuntimePlacementSamplingMetrics.Event.RECORDED_UNKNOWN, 1L);
        } finally { service.close(); }
    }
    @Test void duplicateAndStorageFailureNeverProbeOrEscapeToDispatch() {
        var service = new RuntimePlacementEvidenceService(store, positions);
        try {
            var request = request();
            service.capture(request); verifyNoInteractions(positions);
            when(store.claim(request)).thenThrow(new IllegalStateException("storage unavailable"));
            assertThatCode(() -> service.capture(request)).doesNotThrowAnyException();
            verifyNoInteractions(positions);
            assertThat(service.sampling().phases().getFirst().events())
                    .containsEntry(RuntimePlacementSamplingMetrics.Event.NOT_CLAIMED, 1L)
                    .containsEntry(RuntimePlacementSamplingMetrics.Event.CLAIM_UNCONFIRMED, 1L);
        } finally { service.close(); }
    }
    @Test void boundedExecutorDoesNotWaitForQueriesOrRunRejectedWorkOnCaller() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        when(store.claim(any())).thenAnswer(call -> { entered.countDown(); try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return null; });
        var runtime = mock(AgentRuntimeInstance.class);
        when(runtime.getProtocol()).thenReturn("SUBSTRATE"); when(runtime.hasBoundIdentity()).thenReturn(true);
        var service = new RuntimePlacementEvidenceService(store, positions);
        try {
            service.request("a", "trace", runtime, RuntimePlacementEvidenceStore.Phase.DISPATCH_REQUESTED);
            service.request("b", "trace", runtime, RuntimePlacementEvidenceStore.Phase.DISPATCH_REQUESTED);
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            for (int n=0;n<34;n++) service.request("queued-"+n, "trace", runtime, RuntimePlacementEvidenceStore.Phase.DISPATCH_REQUESTED);
            verify(store, times(2)).claim(any()); // 32 queued, excess rejected; no caller-runs fallback.
            verifyNoInteractions(positions);
            var snapshot = service.sampling();
            assertThat(snapshot.queueDepth()).isEqualTo(32);
            assertThat(snapshot.activeWorkers()).isEqualTo(2);
            assertThat(snapshot.phases().getFirst().events())
                    .containsEntry(RuntimePlacementSamplingMetrics.Event.REQUESTED, 36L)
                    .containsEntry(RuntimePlacementSamplingMetrics.Event.ACCEPTED, 34L)
                    .containsEntry(RuntimePlacementSamplingMetrics.Event.REJECTED, 2L)
                    .containsEntry(RuntimePlacementSamplingMetrics.Event.STARTED, 2L);
            service.close();
            assertThat(service.sampling().phases().getFirst().events())
                    .containsEntry(RuntimePlacementSamplingMetrics.Event.SHUTDOWN_DROPPED, 32L);
            service.request("after-close", "trace", runtime, RuntimePlacementEvidenceStore.Phase.RECEIPT_RETURNED);
            assertThat(service.sampling().phases().get(1).events())
                    .containsEntry(RuntimePlacementSamplingMetrics.Event.REJECTED, 1L)
                    .containsEntry(RuntimePlacementSamplingMetrics.Event.ACCEPTED, 0L);
        } finally { service.close(); release.countDown(); }
    }
}
