package dev.starfold.render;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/** Opt-in JFR evidence only: no clocks or per-job allocations without an enabled recording. */
@Name("starfold.LodBuildPhase")
@Label("LOD construction phase")
@Category({"Starfold", "LOD construction"})
@StackTrace(false)
@Threshold("0 ns")
final class GroundLodBuildTrace extends Event {
    private static final EventType TYPE = EventType.getEventType(GroundLodBuildTrace.class);
    // Render-thread only, used to aggregate nested plant work without one event per quad.
    static GroundLodBuildTrace packing;
    private long started;
    int node = -1, step;
    long version, session;
    String phase, outcome;
    long elapsedNanos, inputNanos, columnCalls, packingNanos, uploadNanos;
    long plantNanos, plantCalls, slices, bytes;
    long uploads, gpuBytes, detailBytes;
    int jobs, completed, staging, roots, demand, selected, missingRoots;
    double altitude;

    static boolean enabled() {
        return TYPE.isEnabled();
    }

    static GroundLodBuildTrace start(GroundQuadtreeScheduler.Ticket ticket, String phase) {
        if (!enabled()) return null;
        var event = new GroundLodBuildTrace();
        event.phase = phase;
        if (ticket != null) {
            event.node = ticket.node();
            event.step = ticket.step();
            event.version = ticket.version();
            event.session = ticket.session();
        }
        event.started = System.nanoTime();
        event.begin();
        return event;
    }

    static void finish(GroundLodBuildTrace event, String outcome, long bytes) {
        if (event == null) return;
        event.elapsedNanos = System.nanoTime() - event.started;
        event.outcome = outcome;
        event.bytes = bytes;
        event.end();
        event.commit();
    }
}
