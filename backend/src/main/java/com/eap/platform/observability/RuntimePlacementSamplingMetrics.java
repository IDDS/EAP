package com.eap.platform.observability;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

/** Process-local, fixed-cardinality counters. No task identities or exception text. */
final class RuntimePlacementSamplingMetrics {
    enum Event {
        REQUESTED, ACCEPTED, REJECTED, SCHEDULING_FAILED, STARTED,
        NOT_CLAIMED, CLAIM_UNCONFIRMED, QUERY_FAILED, WRITE_UNCONFIRMED, NOT_RECORDED,
        RECORDED_ASSIGNED, RECORDED_UNASSIGNED, RECORDED_UNKNOWN, SHUTDOWN_DROPPED
    }
    private static final int EVENT_COUNT = Event.values().length;
    record PhaseCounters(RuntimePlacementEvidenceStore.Phase phase, Map<Event, Long> events) {}
    record Snapshot(Instant processStartedAt, Instant observedAt, int queueDepth, int activeWorkers,
                    int queueCapacity, int workerCapacity, List<PhaseCounters> phases) {}

    private final Instant started = Instant.now();
    private final AtomicLongArray counts = new AtomicLongArray(
            RuntimePlacementEvidenceStore.Phase.values().length * EVENT_COUNT);

    void record(RuntimePlacementEvidenceStore.Phase phase, Event event) {
        counts.incrementAndGet(phase.ordinal() * EVENT_COUNT + event.ordinal());
    }

    Snapshot snapshot(int queued, int active, int capacity, int workers) {
        var phases = Arrays.stream(RuntimePlacementEvidenceStore.Phase.values()).map(phase -> {
            var events = new EnumMap<Event, Long>(Event.class);
            for (var event : Event.values())
                events.put(event, counts.get(phase.ordinal() * EVENT_COUNT + event.ordinal()));
            return new PhaseCounters(phase, Collections.unmodifiableMap(events));
        }).toList();
        return new Snapshot(started, Instant.now(), queued, active, capacity, workers, phases);
    }
}
