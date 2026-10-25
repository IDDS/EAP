package com.eap.platform.observability;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ObservationMetricsExporterTests {
    @Test void pinnedMessagesKeepCurrentMetricsWireFieldNumbers() {
        assertThat(io.opentelemetry.proto.metrics.v1.ResourceMetrics.getDescriptor()
                .findFieldByName("instrumentation_library_metrics").getNumber()).isEqualTo(2);
        assertThat(io.opentelemetry.proto.metrics.v1.InstrumentationLibraryMetrics.getDescriptor()
                .findFieldByName("metrics").getNumber()).isEqualTo(2);
        assertThat(io.opentelemetry.proto.metrics.v1.Metric.getDescriptor()
                .findFieldByName("gauge").getNumber()).isEqualTo(5);
        assertThat(NumberDataPoint.getDescriptor().findFieldByName("attributes").getNumber()).isEqualTo(7);
    }

    private byte[] modernReceipt(long rejected, String message) throws Exception {
        var nested = new java.io.ByteArrayOutputStream(); var fields = CodedOutputStream.newInstance(nested);
        fields.writeInt64(1, rejected); fields.writeString(2, message); fields.flush();
        var outer = new java.io.ByteArrayOutputStream(); var response = CodedOutputStream.newInstance(outer);
        response.writeBytes(1, ByteString.copyFrom(nested.toByteArray())); response.flush();
        return outer.toByteArray();
    }

    @Test void modernPartialSuccessIsNotSilentlyDiscardedByOldResponseSchema() throws Exception {
        assertThat(ObservationMetricsExporter.rejectedPoints(modernReceipt(1, "PRIVATE_REMOTE_SENTINEL"))).isTrue();
        assertThat(ObservationMetricsExporter.rejectedPoints(modernReceipt(0, "PRIVATE_WARNING_SENTINEL"))).isFalse();
        assertThat(ObservationMetricsExporter.rejectedPoints(new byte[0])).isFalse();
        assertThatThrownBy(() -> ObservationMetricsExporter.rejectedPoints(new byte[]{8, 1}))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> ObservationMetricsExporter.rejectedPoints(new byte[]{10, 1, 12}))
                .isInstanceOf(java.io.IOException.class);
    }

    private ObservationCoverageService.Report report() {
        var metrics = new ArrayList<ObservationCoverageService.Metric>();
        for (String entry : List.of("AX_ECHO", "AX_FAIL", "AX_LLM", "EXPERIMENT", "WORKFLOW_CONTROL"))
            metrics.add(new ObservationCoverageService.Metric(entry, 0L, 0, 0, 0, 0, 0, 0, 0L, 0L));
        metrics.add(new ObservationCoverageService.Metric("RUNTIME_TASK", 2L, 0, 1, 1, 0, 1, 0, 2L, 2L));
        metrics.add(new ObservationCoverageService.Metric("CHAT", 1L, 0, 0, 1, 0, 0, 1, 1L, 1L));
        metrics.add(new ObservationCoverageService.Metric("LEGACY_EXTERNAL", null, 0, 0, 0, 0, 0, 0, null, null));
        var detail = new ObservationCoverageService.Operation(999, "CHAT", "PRIVATE_OPERATION_SENTINEL", 888L,
                "PRIVATE_TRACE_SENTINEL", "PRIVATE_UID_SENTINEL", "EXECUTION_UNKNOWN", "MISSING_MODEL",
                1, 1, 1L, 0L, 0L, 1700000000000L, "PRIVATE_PATH_SENTINEL");
        return new ObservationCoverageService.Report(60, 1699996400000L, 1700000000000L,
                "ORIGINAL_SUBMISSION_V1", metrics, List.of(detail), 100, true);
    }

    private Map<Map<String, String>, Long> points(ExportMetricsServiceRequest request, String name) {
        var result = new LinkedHashMap<Map<String, String>, Long>();
        for (var resource : request.getResourceMetricsList()) for (var scope : resource.getInstrumentationLibraryMetricsList())
            for (var metric : scope.getMetricsList()) if (metric.getName().equals(name)) {
                assertThat(metric.hasGauge()).isTrue();
                for (NumberDataPoint point : metric.getGauge().getDataPointsList()) {
                    var labels = new TreeMap<String, String>();
                    point.getAttributesList().forEach(a -> labels.put(a.getKey(), a.getValue().getStringValue()));
                    assertThat(point.getTimeUnixNano()).isEqualTo(1700000000000000000L);
                    assertThat(result.put(labels, point.getAsInt())).isNull();
                }
            }
        return result;
    }

    @Test void unknownDenominatorsAreAvailabilityOnlyAndMovingWindowCountsAreGauges() {
        var request = ObservationMetricsExporter.encode(report());
        assertThat(points(request, "eap_observation_denominator_known"))
                .containsEntry(Map.of("entry_kind", "LEGACY_EXTERNAL"), 0L)
                .containsEntry(Map.of("entry_kind", "AX_ECHO"), 1L);
        assertThat(points(request, "eap_observation_registered"))
                .containsEntry(Map.of("entry_kind", "RUNTIME_TASK"), 2L)
                .containsEntry(Map.of("entry_kind", "CHAT"), 1L)
                .containsEntry(Map.of("entry_kind", "AX_ECHO"), 0L)
                .doesNotContainKey(Map.of("entry_kind", "LEGACY_EXTERNAL"));
        assertThat(points(request, "eap_observation_coverage"))
                .containsEntry(Map.of("entry_kind", "RUNTIME_TASK", "state", "PENDING"), 1L)
                .containsEntry(Map.of("entry_kind", "RUNTIME_TASK", "state", "GAP"), 1L);
        assertThat(points(request, "eap_observation_outcome"))
                .containsEntry(Map.of("entry_kind", "RUNTIME_TASK", "state", "FAILED"), 1L)
                .containsEntry(Map.of("entry_kind", "CHAT", "state", "UNKNOWN"), 1L);
        assertThat(points(request, "eap_observation_snapshot_time_seconds")).containsEntry(Map.of(), 1700000000L);
        assertThat(points(request, "eap_observation_window_minutes")).containsEntry(Map.of(), 60L);
    }

    @Test void payloadExcludesOperationTraceUidPathsAndUsesOnlyBoundedLabels() {
        var request = ObservationMetricsExporter.encode(report());
        assertThat(new String(request.toByteArray(), StandardCharsets.ISO_8859_1)).doesNotContain("PRIVATE_");
        assertThat(request.getSerializedSize()).isLessThan(65536);
        for (var resource : request.getResourceMetricsList()) for (var scope : resource.getInstrumentationLibraryMetricsList())
            for (var metric : scope.getMetricsList()) for (var point : metric.getGauge().getDataPointsList())
                assertThat(point.getAttributesList()).allSatisfy(a -> assertThat(a.getKey())
                        .isIn("entry_kind", "state", "evidence"));
    }

    @Test void rejectsDynamicLabelsIncompleteEntrySetAndInvalidPartitions() {
        var original = report();
        for (var invalid : List.of(
                new ObservationCoverageService.Metric("CUSTOMER_DYNAMIC", 1L, 1, 0, 0, 0, 0, 0, 1L, 1L),
                new ObservationCoverageService.Metric("CHAT", 1L, 1, 1, 0, 0, 0, 0, 1L, 1L),
                new ObservationCoverageService.Metric("CHAT", 10001L, 10001, 0, 0, 0, 0, 0, 10001L, 10001L))) {
            var metrics = new ArrayList<>(original.metrics()); metrics.set(6, invalid);
            var report = new ObservationCoverageService.Report(60, original.windowStartMs(), original.observedMs(),
                    original.denominatorContract(), metrics, List.of(), 100, false);
            assertThatThrownBy(() -> ObservationMetricsExporter.encode(report)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void onlyInternalCredentialFreeMetricsEndpointsAreAccepted() {
        assertThat(ObservationMetricsExporter.internalEndpoint("http://opentelemetry-collector.otel-system.svc:4318/v1/metrics").getPort()).isEqualTo(4318);
        for (String url : List.of("https://example.com/v1/metrics", "http://user:synthetic@localhost/v1/metrics",
                "http://localhost/v1/metrics?token=synthetic", "http://localhost/v1/metrics#fragment",
                "http://localhost/v1/traces", "file:///v1/metrics", ""))
            assertThatThrownBy(() -> ObservationMetricsExporter.internalEndpoint(url))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("synthetic");
    }

    @FunctionalInterface interface Handler { void handle(HttpExchange exchange) throws Exception; }
    static class Receiver implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
        Receiver(Handler handler) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try { handler.handle(exchange); } catch (Exception ignored) { }
                finally { exchange.close(); }
            });
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/metrics"; }
        public void close() { server.stop(0); executor.shutdownNow(); }
    }

    private ObservationMetricsExporter exporter(String url) {
        var snapshots = mock(ObservationMetricsSnapshotService.class);
        when(snapshots.read(60)).thenReturn(report());
        return new ObservationMetricsExporter(snapshots, placements(), url, 60, 10000);
    }

    private RuntimePlacementEvidenceService placements() {
        var service = mock(RuntimePlacementEvidenceService.class);
        var counters = new RuntimePlacementSamplingMetrics();
        counters.record(RuntimePlacementEvidenceStore.Phase.DISPATCH_REQUESTED, RuntimePlacementSamplingMetrics.Event.REJECTED);
        when(service.sampling()).thenReturn(counters.snapshot(32, 2, 32, 2));
        return service;
    }

    @Test void samplingUsesCumulativeSumsWithProcessStartAndFixedLabels() {
        var snapshot = placements().sampling();
        var request = ObservationMetricsExporter.encode(report(), snapshot);
        var metrics = request.getResourceMetrics(1).getInstrumentationLibraryMetrics(0).getMetricsList();
        assertThat(metrics).hasSize(7);
        var sum = metrics.getFirst().getSum();
        assertThat(sum.getIsMonotonic()).isTrue();
        assertThat(sum.getAggregationTemporality()).isEqualTo(
                io.opentelemetry.proto.metrics.v1.AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE);
        assertThat(sum.getDataPointsList()).hasSize(28).allSatisfy(point -> {
            assertThat(point.getStartTimeUnixNano()).isEqualTo(snapshot.processStartedAt().toEpochMilli() * 1000000L);
            assertThat(point.getTimeUnixNano()).isEqualTo(snapshot.observedAt().toEpochMilli() * 1000000L);
            assertThat(point.getAttributesList()).extracting(a -> a.getKey()).containsExactly("phase", "event");
        });
        assertThat(sum.getDataPointsList().stream().mapToLong(NumberDataPoint::getAsInt).sum()).isEqualTo(1);
        assertThat(request.getSerializedSize()).isLessThan(65536);
        assertThat(new String(request.toByteArray(), StandardCharsets.ISO_8859_1)).doesNotContain("PRIVATE_");
        assertThat(io.opentelemetry.proto.metrics.v1.Metric.getDescriptor().findFieldByName("sum").getNumber()).isEqualTo(7);
    }

    @Test void samplingSnapshotCannotInventMissingPhasesOrNegativeCounts() {
        var original = placements().sampling();
        var invalid = new RuntimePlacementSamplingMetrics.Snapshot(original.processStartedAt(), original.observedAt(),
                0, 0, 32, 2, List.of(original.phases().getFirst(), original.phases().getFirst()));
        assertThatThrownBy(() -> ObservationMetricsExporter.encode(report(), invalid)).isInstanceOf(IllegalArgumentException.class);
        var events = new EnumMap<>(original.phases().getFirst().events());
        events.put(RuntimePlacementSamplingMetrics.Event.REJECTED, -1L);
        var negative = new RuntimePlacementSamplingMetrics.Snapshot(original.processStartedAt(), original.observedAt(),
                0, 0, 32, 2, List.of(new RuntimePlacementSamplingMetrics.PhaseCounters(
                RuntimePlacementEvidenceStore.Phase.DISPATCH_REQUESTED, events), original.phases().get(1)));
        assertThatThrownBy(() -> ObservationMetricsExporter.encode(report(), negative)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sendsOnlyProtobufSnapshotToReceiverAndAcceptsOriginalReceipt() throws Exception {
        var captured = new AtomicReference<ExportMetricsServiceRequest>();
        var headers = new AtomicReference<com.sun.net.httpserver.Headers>();
        try (var receiver = new Receiver(exchange -> {
            headers.set(exchange.getRequestHeaders());
            captured.set(ExportMetricsServiceRequest.parseFrom(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(200, -1);
        })) {
            assertThat(exporter(receiver.url()).pushOnce()).isTrue();
            assertThat(points(captured.get(), "eap_observation_registered"))
                    .containsEntry(Map.of("entry_kind", "RUNTIME_TASK"), 2L);
            assertThat(headers.get().getFirst("Content-Type")).isEqualTo("application/x-protobuf");
            assertThat(headers.get().getFirst("Authorization")).isNull();
            assertThat(captured.get().getResourceMetrics(1).getInstrumentationLibraryMetrics(0)
                    .getMetrics(0).getSum().getDataPointsCount()).isEqualTo(28);
        }
    }

    @Test void redirectNeverReceivesSnapshotAndOriginalPostIsNotRetried() throws Exception {
        var originalRequests = new AtomicInteger(); var redirectedRequests = new AtomicInteger();
        try (var target = new Receiver(exchange -> { redirectedRequests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); });
             var receiver = new Receiver(exchange -> { originalRequests.incrementAndGet();
                 exchange.getResponseHeaders().set("Location", target.url()); exchange.sendResponseHeaders(307, -1); })) {
            assertThat(exporter(receiver.url()).pushOnce()).isFalse();
            assertThat(originalRequests).hasValue(1); assertThat(redirectedRequests).hasValue(0);
        }
    }

    @Test void partialRejectionOversizedReceiptAndServerFailureRemainUnconfirmed() throws Exception {
        byte[] partial = modernReceipt(1, "PRIVATE_REMOTE_SENTINEL");
        for (byte[] body : List.of(partial, new byte[4097])) try (var receiver = new Receiver(exchange -> {
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
        })) { assertThat(exporter(receiver.url()).pushOnce()).isFalse(); }
        try (var receiver = new Receiver(exchange -> exchange.sendResponseHeaders(503, -1))) {
            assertThat(exporter(receiver.url()).pushOnce()).isFalse();
        }
    }

    @Test void stalledBodyKeepsWholeResponseDeadline() throws Exception {
        try (var receiver = new Receiver(exchange -> {
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write(0); exchange.getResponseBody().flush();
            Thread.sleep(5000); exchange.getResponseBody().write(0);
        })) {
            long start = System.nanoTime(); assertThat(exporter(receiver.url()).pushOnce()).isFalse();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        }
    }

    @Test void failedSnapshotDoesNotSendInventedZeroMetrics() throws Exception {
        var requests = new AtomicInteger();
        try (var receiver = new Receiver(exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); })) {
            var snapshots = mock(ObservationMetricsSnapshotService.class);
            when(snapshots.read(60)).thenThrow(new IllegalStateException("PRIVATE_DATABASE_SENTINEL"));
            assertThat(new ObservationMetricsExporter(snapshots, placements(), receiver.url(), 60, 10000).pushOnce()).isFalse();
            assertThat(requests).hasValue(0);
        }
    }
}
