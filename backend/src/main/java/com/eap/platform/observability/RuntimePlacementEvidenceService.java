package com.eap.platform.observability;

import com.eap.platform.deployment.RuntimePlacementService;
import com.eap.platform.registry.AgentRuntimeInstance;
import com.eap.platform.substrate.SubstrateActorRef;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static com.eap.platform.observability.RuntimePlacementSamplingMetrics.Event.*;

/** Best-effort sampling never waits on the dispatch thread, retries a task, or repairs history. */
@Service
public class RuntimePlacementEvidenceService {
    private final RuntimePlacementEvidenceStore store;
    private final RuntimePlacementService positions;
    private static final int WORKERS = 2, QUEUE_CAPACITY = 32;
    private final RuntimePlacementSamplingMetrics metrics = new RuntimePlacementSamplingMetrics();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(WORKERS, WORKERS, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY), Thread.ofPlatform().daemon().name("runtime-placement-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());

    public RuntimePlacementEvidenceService(RuntimePlacementEvidenceStore store, RuntimePlacementService positions) {
        this.store = store;
        this.positions = positions;
    }

    public void request(String key, String rawTrace, AgentRuntimeInstance runtime, RuntimePlacementEvidenceStore.Phase phase) {
        try {
            if (phase == null || rawTrace == null || !SubstrateActorRef.isSubstrate(runtime.getProtocol()) || !runtime.hasBoundIdentity()) return;
            var request = new RuntimePlacementEvidenceStore.Request(key, rawTrace, runtime.getAgentId(), runtime.getId(),
                    runtime.getInstanceKey(), runtime.getEndpoint(), runtime.getBoundActorUid(), runtime.getBoundActorTemplate(),
                    phase, Instant.now());
            metrics.record(phase, REQUESTED);
            try {
                executor.execute(new CaptureTask(request));
                metrics.record(phase, ACCEPTED);
            } catch (RejectedExecutionException e) {
                metrics.record(phase, REJECTED);
            } catch (RuntimeException e) {
                metrics.record(phase, SCHEDULING_FAILED);
            }
        } catch (RuntimeException e) {
            // No key, trace, upstream text or exception cause in these low-cardinality diagnostics.
            LoggerFactory.getLogger(getClass()).warn("Runtime placement sample not scheduled");
        }
    }

    void capture(RuntimePlacementEvidenceStore.Request request) {
        var phase = request.phase();
        metrics.record(phase, STARTED);
        RuntimePlacementEvidenceStore.Claim claim;
        try {
            claim = store.claim(request);
        } catch (RuntimeException e) {
            metrics.record(phase, CLAIM_UNCONFIRMED);
            return;
        }
        if (claim == null) { metrics.record(phase, NOT_CLAIMED); return; }
        var started = Instant.now();
        RuntimePlacementService.Snapshot snapshot;
        try {
            snapshot = Objects.requireNonNull(positions.currentForBinding(
                    request.agentId(), request.instanceKey(), request.actorUid(), request.template()));
        } catch (RuntimeException e) {
            metrics.record(phase, QUERY_FAILED);
            snapshot = new RuntimePlacementService.Snapshot(request.instanceKey(), "UNKNOWN", "CAPTURE_FAILED", null,
                    started, Instant.now(), null, null, null, null);
        }
        try {
            if (!store.complete(claim, snapshot)) { metrics.record(phase, NOT_RECORDED); return; }
            metrics.record(phase, "ASSIGNED".equals(snapshot.status()) ? RECORDED_ASSIGNED
                    : "UNASSIGNED".equals(snapshot.status()) ? RECORDED_UNASSIGNED : RECORDED_UNKNOWN);
        } catch (RuntimeException e) {
            // A commit failure cannot prove whether the write reached storage.
            metrics.record(phase, WRITE_UNCONFIRMED);
        }
    }

    RuntimePlacementSamplingMetrics.Snapshot sampling() {
        return metrics.snapshot(executor.getQueue().size(), executor.getActiveCount(), QUEUE_CAPACITY, WORKERS);
    }

    private final class CaptureTask implements Runnable {
        private final RuntimePlacementEvidenceStore.Request request;
        private CaptureTask(RuntimePlacementEvidenceStore.Request request) { this.request = request; }
        @Override public void run() { capture(request); }
    }

    @PreDestroy public void close() {
        for (var task : executor.shutdownNow())
            if (task instanceof CaptureTask capture) metrics.record(capture.request.phase(), SHUTDOWN_DROPPED);
    }
}
