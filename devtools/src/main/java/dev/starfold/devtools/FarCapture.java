package dev.starfold.devtools;

import dev.starfold.api.FarViewDiagnostics.Event;
import dev.starfold.api.FarViewDiagnostics.RegionState;
import dev.starfold.api.FarViewDiagnostics.Snapshot;

import java.util.ArrayDeque;
import java.util.List;

/** Client-tick ledger. Retains region states, not repeated geometry/event copies per tick. */
final class FarCapture {
    static final int MAX_STATES = 128, MAX_EVENTS = 2048, MAX_TICKS = 2400;
    static final long MAX_NANOS = 120_000_000_000L;

    record State(long tick, RegionState region) {}

    private final ArrayDeque<State> states = new ArrayDeque<>();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    final long startedNanos;
    long ticks, lastSequence, missingEvents, discardedStates, discardedEvents;
    private long lastRegionTime = Long.MIN_VALUE, lastEpoch = Long.MIN_VALUE;
    Snapshot latest;
    String stoppedReason;

    FarCapture(long now) {
        startedNanos = now;
    }

    void accept(Snapshot snapshot) {
        if (latest == null) missingEvents += snapshot.droppedEvents();
        latest = snapshot;
        for (Event event : snapshot.events()) {
            if (event.sequence() <= lastSequence) continue;
            if (lastSequence != 0 && event.sequence() > lastSequence + 1)
                missingEvents += event.sequence() - lastSequence - 1;
            lastSequence = event.sequence();
            if (events.size() == MAX_EVENTS) {
                events.removeFirst();
                discardedEvents++;
            }
            events.addLast(event);
        }
        var region = snapshot.region();
        if (region != null
                && (region.nanoTime() != lastRegionTime || region.epoch() != lastEpoch)) {
            if (states.size() == MAX_STATES) {
                states.removeFirst();
                discardedStates++;
            }
            states.addLast(new State(ticks, region));
            lastRegionTime = region.nanoTime();
            lastEpoch = region.epoch();
        }
    }

    boolean expired(long now) {
        return ticks >= MAX_TICKS || now - startedNanos >= MAX_NANOS;
    }

    List<State> states() {
        return List.copyOf(states);
    }

    List<Event> events() {
        return List.copyOf(events);
    }

    boolean stopped() {
        return stoppedReason != null;
    }
}
