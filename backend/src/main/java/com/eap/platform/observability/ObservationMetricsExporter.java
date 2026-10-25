package com.eap.platform.observability;

import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.InstrumentationLibraryMetrics;
import io.opentelemetry.proto.metrics.v1.Sum;
import io.opentelemetry.proto.metrics.v1.AggregationTemporality;
import io.opentelemetry.proto.resource.v1.Resource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Window gauges from original registrations. No operation identifiers or received-span denominators. */
@Component
@ConditionalOnProperty(prefix = "eap.observability.metrics-export", name = "enabled", havingValue = "true")
public class ObservationMetricsExporter {
    private static final Set<String> ENTRIES = Set.of("AX_ECHO", "AX_FAIL", "AX_LLM", "EXPERIMENT",
            "WORKFLOW_CONTROL", "RUNTIME_TASK", "CHAT", "LEGACY_EXTERNAL");
    private final ObservationMetricsSnapshotService snapshots;
    private final RuntimePlacementEvidenceService placements;
    private final URI endpoint;
    private final int windowMinutes;
    private final long intervalMs;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private ScheduledExecutorService worker;

    public ObservationMetricsExporter(ObservationMetricsSnapshotService snapshots, RuntimePlacementEvidenceService placements,
            @Value("${eap.observability.metrics-export.url:}") String url,
            @Value("${eap.observability.metrics-export.window-minutes:60}") int windowMinutes,
            @Value("${eap.observability.metrics-export.interval-ms:60000}") long intervalMs) {
        this.snapshots = snapshots;
        this.placements = placements;
        this.endpoint = internalEndpoint(url);
        if (windowMinutes < 1 || windowMinutes > 1440 || intervalMs < 10000 || intervalMs > 3600000)
            throw new IllegalArgumentException("指标窗口或导出间隔无效");
        this.windowMinutes = windowMinutes;
        this.intervalMs = intervalMs;
    }

    static URI internalEndpoint(String url) {
        try {
            URI uri = URI.create(url);
            String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
            boolean internal = host.endsWith(".svc") || host.endsWith(".svc.cluster.local")
                    || Set.of("localhost", "127.0.0.1", "[::1]").contains(host);
            if (Set.of("http", "https").contains(Objects.toString(uri.getScheme(), "")) && internal
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && "/v1/metrics".equals(uri.getRawPath()) && (uri.getPort() == -1 || uri.getPort() > 0)) return uri;
        } catch (RuntimeException ignored) { }
        throw new IllegalArgumentException("指标导出目标必须为集群服务或本机夹具的 /v1/metrics");
    }

    @PostConstruct
    void start() {
        worker = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon()
                .name("observation-metrics-export").factory());
        worker.scheduleWithFixedDelay(this::pushOnce, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (worker != null) worker.shutdownNow();
    }

    boolean pushOnce() {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            byte[] body = encode(snapshots.read(windowMinutes), placements.sampling()).toByteArray();
            if (body.length > 65536) throw new IllegalArgumentException("metric payload limit");
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/x-protobuf")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            pending = client.sendAsync(request, boundedBody());
            var response = pending.get(2, TimeUnit.SECONDS);
            if (response.statusCode() != 200) throw new IllegalStateException("export unconfirmed");
            if (rejectedPoints(response.body()))
                throw new IllegalStateException("partial export");
            return true;
        } catch (Exception failure) {
            if (pending != null) pending.cancel(true);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            // Do not log remote URLs, bodies, exception text or business data. No business retry occurs.
            LoggerFactory.getLogger(ObservationMetricsExporter.class)
                    .warn("observation_metrics outcome=EXPORT_UNCONFIRMED reason=SNAPSHOT_OR_RECEIVER_UNCONFIRMED");
            return false;
        }
    }

    static ExportMetricsServiceRequest encode(ObservationCoverageService.Report report) {
        if (report.windowMinutes() < 1 || report.windowMinutes() > 1440
                || report.windowStartMs() < 0 || report.observedMs() < report.windowStartMs()
                || report.metrics().size() != ENTRIES.size()) throw new IllegalArgumentException("invalid snapshot");
        long nanos = Math.multiplyExact(report.observedMs(), 1000000L);
        Map<String, Gauge.Builder> gauges = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (var metric : report.metrics()) {
            String entry = metric.entryKind();
            if (!ENTRIES.contains(entry) || !seen.add(entry)) throw new IllegalArgumentException("invalid entry");
            Map<String, String> labels = Map.of("entry_kind", entry);
            point(gauges, "eap_observation_denominator_known", nanos, metric.registered() == null ? 0 : 1, labels);
            if (metric.registered() == null) continue; // Unknown is never encoded as a zero count.
            long registered = metric.registered();
            long[] counts = {registered, metric.complete(), metric.pending(), metric.gaps(), metric.unconfirmed(),
                    metric.failed(), metric.unknown()};
            for (long count : counts) if (count < 0 || count > registered || count > 10000)
                throw new IllegalArgumentException("invalid counts");
            if (metric.complete() + metric.pending() + metric.gaps() + metric.unconfirmed() != registered
                    || metric.expectedAgentSpans() == null || metric.expectedModelEvents() == null
                    || metric.expectedAgentSpans() < 0 || metric.expectedModelEvents() < 0)
                throw new IllegalArgumentException("invalid denominator");
            point(gauges, "eap_observation_registered", nanos, registered, labels);
            for (var category : Map.of("COMPLETE", metric.complete(), "PENDING", metric.pending(),
                    "GAP", metric.gaps(), "UNCONFIRMED", metric.unconfirmed()).entrySet())
                point(gauges, "eap_observation_coverage", nanos, category.getValue(),
                        Map.of("entry_kind", entry, "state", category.getKey()));
            for (var outcome : Map.of("FAILED", metric.failed(), "UNKNOWN", metric.unknown()).entrySet())
                point(gauges, "eap_observation_outcome", nanos, outcome.getValue(),
                        Map.of("entry_kind", entry, "state", outcome.getKey()));
            point(gauges, "eap_observation_expected", nanos, metric.expectedAgentSpans(),
                    Map.of("entry_kind", entry, "evidence", "AGENT_SPAN"));
            point(gauges, "eap_observation_expected", nanos, metric.expectedModelEvents(),
                    Map.of("entry_kind", entry, "evidence", "GATEWAY_MODEL"));
        }
        point(gauges, "eap_observation_snapshot_time_seconds", nanos, report.observedMs() / 1000, Map.of());
        point(gauges, "eap_observation_window_start_seconds", nanos, report.windowStartMs() / 1000, Map.of());
        point(gauges, "eap_observation_window_minutes", nanos, report.windowMinutes(), Map.of());
        // The pinned 1.7.1-alpha library uses this name; its field 2 is current ScopeMetrics on the wire.
        var scope = InstrumentationLibraryMetrics.newBuilder();
        gauges.forEach((name, gauge) -> scope.addMetrics(Metric.newBuilder().setName(name).setGauge(gauge)));
        var resource = Resource.newBuilder().addAttributes(label("service.name", "eap-observation"));
        return ExportMetricsServiceRequest.newBuilder().addResourceMetrics(ResourceMetrics.newBuilder()
                .setResource(resource).addInstrumentationLibraryMetrics(scope)).build();
    }

    static ExportMetricsServiceRequest encode(ObservationCoverageService.Report report,
            RuntimePlacementSamplingMetrics.Snapshot sampling) {
        if (sampling.processStartedAt().isAfter(sampling.observedAt())
                || sampling.queueDepth() < 0 || sampling.queueDepth() > sampling.queueCapacity()
                || sampling.activeWorkers() < 0 || sampling.activeWorkers() > sampling.workerCapacity()
                || sampling.queueCapacity() != 32 || sampling.workerCapacity() != 2
                || sampling.phases().size() != RuntimePlacementEvidenceStore.Phase.values().length)
            throw new IllegalArgumentException("invalid sampling snapshot");
        long time = Math.multiplyExact(sampling.observedAt().toEpochMilli(), 1000000L);
        long start = Math.multiplyExact(sampling.processStartedAt().toEpochMilli(), 1000000L);
        var sum = Sum.newBuilder().setIsMonotonic(true)
                .setAggregationTemporality(AggregationTemporality.AGGREGATION_TEMPORALITY_CUMULATIVE);
        var seen = EnumSet.noneOf(RuntimePlacementEvidenceStore.Phase.class);
        for (var phase : sampling.phases()) {
            if (!seen.add(phase.phase()) || phase.events().size() != RuntimePlacementSamplingMetrics.Event.values().length)
                throw new IllegalArgumentException("invalid sampling phases");
            for (var event : RuntimePlacementSamplingMetrics.Event.values()) {
                Long count = phase.events().get(event);
                if (count == null || count < 0) throw new IllegalArgumentException("invalid sampling counter");
                sum.addDataPoints(NumberDataPoint.newBuilder().setStartTimeUnixNano(start).setTimeUnixNano(time)
                        .setAsInt(count).addAttributes(label("phase", phase.phase().name()))
                        .addAttributes(label("event", event.name())));
            }
        }
        String prefix = "eap_runtime_placement_sampling_";
        var gauges = new LinkedHashMap<String, Gauge.Builder>();
        point(gauges, prefix + "queue_depth", time, sampling.queueDepth(), Map.of());
        point(gauges, prefix + "active_workers", time, sampling.activeWorkers(), Map.of());
        point(gauges, prefix + "queue_capacity", time, sampling.queueCapacity(), Map.of());
        point(gauges, prefix + "worker_capacity", time, sampling.workerCapacity(), Map.of());
        point(gauges, prefix + "process_start_time_seconds", time, sampling.processStartedAt().getEpochSecond(), Map.of());
        point(gauges, prefix + "snapshot_time_seconds", time, sampling.observedAt().getEpochSecond(), Map.of());
        var scope = InstrumentationLibraryMetrics.newBuilder()
                .addMetrics(Metric.newBuilder().setName(prefix + "events").setSum(sum));
        gauges.forEach((name, gauge) -> scope.addMetrics(Metric.newBuilder().setName(name).setGauge(gauge)));
        var resource = Resource.newBuilder().addAttributes(label("service.name", "eap-placement-sampling"));
        return encode(report).toBuilder().addResourceMetrics(ResourceMetrics.newBuilder()
                .setResource(resource).addInstrumentationLibraryMetrics(scope)).build();
    }

    /** The pinned response predates partial_success. Decode its bounded unknown field instead of ignoring it. */
    static boolean rejectedPoints(byte[] body) throws java.io.IOException {
        var fields = ExportMetricsServiceResponse.parseFrom(body).getUnknownFields();
        if (!fields.hasField(1)) return false;
        var partial = fields.getField(1);
        if (partial.getLengthDelimitedList().size() != 1 || !partial.getVarintList().isEmpty()
                || !partial.getFixed32List().isEmpty() || !partial.getFixed64List().isEmpty()
                || !partial.getGroupList().isEmpty()) throw new java.io.IOException("invalid receipt");
        var input = partial.getLengthDelimitedList().getFirst().newCodedInput();
        boolean rejected = false;
        for (int tag; (tag = input.readTag()) != 0;) {
            if (tag == 8) rejected |= input.readInt64() != 0; // partial_success.rejected_data_points = 1
            else if (tag == 18) input.readStringRequireUtf8(); // error_message = 2, never logged
            else if (!input.skipField(tag)) throw new java.io.IOException("invalid receipt");
        }
        return rejected;
    }

    private static KeyValue label(String key, String value) {
        return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build();
    }

    private static void point(Map<String, Gauge.Builder> gauges, String name, long time, long value,
            Map<String, String> labels) {
        var point = NumberDataPoint.newBuilder().setTimeUnixNano(time).setAsInt(value);
        new TreeMap<>(labels).forEach((key, text) -> point.addAttributes(label(key, text)));
        gauges.computeIfAbsent(name, key -> Gauge.newBuilder()).addDataPoints(point);
    }

    private static HttpResponse.BodyHandler<byte[]> boundedBody() {
        return info -> new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<byte[]> result = new CompletableFuture<>();
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            private Flow.Subscription subscription;
            public CompletionStage<byte[]> getBody() { return result; }
            public void onSubscribe(Flow.Subscription next) { subscription = next; next.request(1); }
            public void onNext(List<ByteBuffer> parts) {
                try {
                    for (var part : parts) {
                        if (bytes.size() + part.remaining() > 4096) throw new IllegalArgumentException("response limit");
                        byte[] data = new byte[part.remaining()]; part.get(data); bytes.write(data);
                    }
                    subscription.request(1);
                } catch (Exception failure) { subscription.cancel(); result.completeExceptionally(failure); }
            }
            public void onError(Throwable failure) { result.completeExceptionally(failure); }
            public void onComplete() { result.complete(bytes.toByteArray()); }
        };
    }
}
