package dev.starfold.dev;

import dev.starfold.api.FarViewDiagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded evidence ledger. Command labels alone never establish motion or renderer coverage. */
final class FarTraceSession {
    static final int MAX_TICKS = 3600, MAX_SAMPLES = 2048, MAX_EVENTS = 8192, MAX_MARKS = 32;
    static final long MAX_SAMPLE_GAP_NANOS = 1_000_000_000L;

    record Pose(int planet, int face, double x, double y, double z, double yaw) {}

    record Sample(String phase, FarViewDiagnostics.RegionState state, Pose pose) {}

    record Mark(String label, int tick) {}

    record Assertion(String name, String status, String detail) {}

    record Result(String status, List<Assertion> assertions, boolean residentRestoreAttempted) {}

    record Report(
            int schemaVersion,
            String evidenceScope,
            FarViewDiagnostics.Watch watch,
            String termination,
            int ticks,
            List<Mark> marks,
            List<Sample> samples,
            List<FarViewDiagnostics.Event> events,
            FarViewDiagnostics.Snapshot finalSnapshot,
            Result result) {}

    final FarViewDiagnostics.Watch watch;
    final List<Sample> samples = new ArrayList<>();
    final List<FarViewDiagnostics.Event> events = new ArrayList<>();
    final List<Mark> marks = new ArrayList<>();
    private String phase = "unmarked", gap;
    private long lastSequence, lastStateTime = Long.MIN_VALUE, lastDropped;
    private int ticks;
    private FarViewDiagnostics.Snapshot latest;

    FarTraceSession(FarViewDiagnostics.Watch watch) {
        this.watch = watch;
    }

    void mark(String label) {
        if (label.isBlank() || label.length() > 64)
            throw new IllegalArgumentException("fartrace label: 1..64 characters");
        if (marks.size() == MAX_MARKS) {
            gap = "mark limit reached";
            throw new IllegalStateException(gap);
        }
        phase = label;
        marks.add(new Mark(label, ticks));
    }

    boolean tick(FarViewDiagnostics.Snapshot snapshot, Pose pose) {
        collect(snapshot, pose);
        if (++ticks >= MAX_TICKS) incomplete("tick limit reached");
        return gap != null;
    }

    void incomplete(String reason) {
        if (gap == null) gap = reason;
    }

    void collect(FarViewDiagnostics.Snapshot snapshot, Pose pose) {
        latest = snapshot;
        if (snapshot.epoch() != watch.epoch()) gap = "watch/renderer epoch changed";
        var history = snapshot.events();
        // Overwrites are harmless when we already copied the overwritten entries. An entirely
        // replaced ring after our previous read cannot establish continuous lifecycle evidence.
        if (snapshot.droppedEvents() > lastDropped
                && !history.isEmpty()
                && (lastSequence == 0 || history.getFirst().sequence() > lastSequence + 1))
            gap = "core event history lost between reads";
        lastDropped = snapshot.droppedEvents();
        for (var event : history) {
            if (event.sequence() <= lastSequence) continue;
            if (events.size() == MAX_EVENTS) {
                gap = "event limit reached";
                break;
            }
            if (lastSequence != 0 && event.sequence() != lastSequence + 1)
                incomplete("core event sequence gap");
            events.add(event);
            lastSequence = event.sequence();
        }
        var state = snapshot.region();
        if (state == null && lastStateTime != Long.MIN_VALUE)
            incomplete("region sampling interrupted");
        if (state != null && !eligible(state)) incomplete("region epoch/planet changed");
        if (state != null && state.nanoTime() != lastStateTime) {
            if (lastStateTime != Long.MIN_VALUE
                    && (state.nanoTime() <= lastStateTime
                            || state.nanoTime() - lastStateTime > MAX_SAMPLE_GAP_NANOS))
                incomplete("region sample time discontinuity");
            if (samples.size() == MAX_SAMPLES) gap = "sample limit reached";
            else {
                samples.add(new Sample(phase, state, pose));
                lastStateTime = state.nanoTime();
            }
        }
    }

    Result check() {
        var checks = new ArrayList<Assertion>();
        checks.add(
                assertion(
                        "continuousCapture",
                        gap == null,
                        gap == null ? "bounded history retained" : gap));
        boolean identity =
                samples.stream()
                        .filter(s -> eligible(s.state()))
                        .flatMap(s -> s.state().tiles().stream())
                        .allMatch(
                                t ->
                                        t.face() == watch.face()
                                                && t.x() >= 0
                                                && t.x() < 2048
                                                && t.z() >= 0
                                                && t.z() < 2048
                                                && (t.x() & 15) == 0
                                                && (t.z() & 15) == 0
                                                && Math.abs(t.x() - watch.x())
                                                        <= watch.radius() * 16
                                                && Math.abs(t.z() - watch.z())
                                                        <= watch.radius() * 16);
        checks.add(
                new Assertion(
                        "tileIdentity",
                        identity ? "PASS" : "FAIL",
                        "core tiles must belong to the watched region"));
        boolean sampled =
                samples.stream().anyMatch(s -> eligible(s.state()) && !s.state().tiles().isEmpty());
        checks.add(assertion("sampling", sampled, "requires eligible core region samples"));

        // Only adjacent samples establish native transitions. Ownership publications can also
        // describe within-frame changes, so they never drive this state machine.
        Map<String, List<FarViewDiagnostics.Event>> residentEvents = new LinkedHashMap<>();
        for (var event : events)
            if (event.epoch() == watch.epoch()
                    && event.planet() == watch.planet()
                    && event.source() == FarViewDiagnostics.Source.RESIDENT)
                residentEvents
                        .computeIfAbsent(
                                key(event.face(), event.x(), event.z()), k -> new ArrayList<>())
                        .add(event);
        Map<String, Recovery> recoveries = new LinkedHashMap<>();
        boolean entered = false, exited = false, attempted = false;
        for (var sample : samples) {
            var state = sample.state();
            if (!eligible(state)) {
                recoveries.clear();
                continue;
            }
            Map<String, Recovery> next = new LinkedHashMap<>();
            for (var tile : state.tiles()) {
                String key = key(tile.face(), tile.x(), tile.z());
                var recovery = recoveries.getOrDefault(key, new Recovery());
                recovery.observe(
                        state.nanoTime(), tile, residentEvents.getOrDefault(key, List.of()));
                entered |= recovery.stage >= 1;
                exited |= recovery.stage == 2;
                attempted |= recovery.attempted;
                next.put(key, recovery);
            }
            recoveries = next; // A missing tile breaks continuity even if it later reappears.
        }
        for (var entry : recoveries.entrySet()) {
            // Events are immediate; the last RegionState can still be up to 250 ms older.
            entry.getValue().afterSample(residentEvents.getOrDefault(entry.getKey(), List.of()));
            attempted |= entry.getValue().attempted;
        }
        boolean restored = gap == null && recoveries.values().stream().anyMatch(r -> r.complete);
        checks.add(
                assertion(
                        "nativeEnter",
                        gap == null && entered,
                        "same tile: consecutive samples outside -> inside native window"));
        checks.add(
                assertion(
                        "nativeExit",
                        gap == null && exited,
                        "same tile and epoch: consecutive samples inside -> outside native window"));
        checks.add(
                assertion(
                        "residentRestore",
                        restored,
                        "after sampled native exit: installed publication and settled resident,"
                                + " or continuously retained valid resident; attempts alone do not"
                                + " complete recovery; selectedStep is last evaluated, not live demand"));
        routeChecks(checks);
        String status =
                checks.stream().anyMatch(a -> a.status().equals("FAIL"))
                        ? "FAIL"
                        : checks.stream().allMatch(a -> a.status().equals("PASS"))
                                ? "PASS"
                                : "INCOMPLETE";
        return new Result(status, List.copyOf(checks), attempted);
    }

    private static String key(int face, int x, int z) {
        return face + ":" + x + ":" + z;
    }

    /** Evidence for the latest uninterrupted native exit of one tile. */
    private static final class Recovery {
        private FarViewDiagnostics.TileState previous;
        private long previousTime, exitTime, invalidatedTime = Long.MIN_VALUE;
        private int stage = -1, retainedStep;
        private boolean retained, attempted, complete;

        void observe(
                long time,
                FarViewDiagnostics.TileState tile,
                List<FarViewDiagnostics.Event> events) {
            if (previous != null
                    && (time <= previousTime || time - previousTime > MAX_SAMPLE_GAP_NANOS)) {
                previous = null;
                stage = -1;
                retained = attempted = false;
            }
            complete = false;
            if (stage == -1 && !tile.nativeWindow()) stage = 0;
            else if (stage == 0 && tile.nativeWindow()) stage = 1;
            else if (stage == 1 && !tile.nativeWindow()) {
                stage = 2;
                exitTime = time;
                invalidatedTime = Long.MIN_VALUE;
                attempted = false;
            } else if (stage == 2 && tile.nativeWindow()) {
                stage = 1;
                attempted = false;
            }

            boolean settled = settled(tile, time, events);
            boolean sameResident =
                    previous != null
                            && sameMeshInputs(previous, tile)
                            && completeResident(tile)
                            && (tile.jobs() > 0 || tile.requestedStep() == retainedStep);
            for (var event : events)
                if (event.nanoTime() > previousTime
                        && event.nanoTime() <= time
                        && (installed(event) || event.kind() == FarViewDiagnostics.Kind.RETIRE))
                    sameResident = false;
            if (stage == 1) {
                if (tile.jobs() == 0) {
                    retained = completeResident(tile) && tile.requestedStep() > 0;
                    retainedStep = tile.requestedStep();
                } else retained &= sameResident;
            }
            if (stage == 2) {
                // A running job obscures requestedStep, but need not replace the resident.
                // Keep its known step until a later unambiguous sample can check it again.
                retained &= sameResident;
                if (!tile.meshPresent() || tile.retiring()) invalidatedTime = time;
                FarViewDiagnostics.Event installation = null;
                for (var event : events) {
                    if (event.nanoTime() > time) continue;
                    boolean installed = installed(event);
                    if (event.nanoTime() < exitTime) continue;
                    if (event.kind() == FarViewDiagnostics.Kind.REQUEST) attempted = true;
                    if (installed && event.nanoTime() > invalidatedTime) installation = event;
                    else if (event.kind() == FarViewDiagnostics.Kind.RETIRE) installation = null;
                }
                attempted |= tile.queued() || tile.jobs() > 0;
                complete =
                        tile.selectedStep() > 0
                                && settled
                                && (retained || matchesMesh(installation, tile));
            }
            previous = tile;
            previousTime = time;
        }

        void afterSample(List<FarViewDiagnostics.Event> events) {
            if (stage != 2) return;
            for (var event : events) {
                if (event.nanoTime() <= previousTime) continue;
                if (installed(event) || event.kind() == FarViewDiagnostics.Kind.RETIRE)
                    complete = false;
                if (event.kind() == FarViewDiagnostics.Kind.REQUEST) attempted = true;
            }
        }
    }

    private static boolean installed(FarViewDiagnostics.Event event) {
        return event.kind() == FarViewDiagnostics.Kind.PUBLICATION
                && event.reason().equals("resident-installed-fading");
    }

    private static boolean sameMeshInputs(
            FarViewDiagnostics.TileState a, FarViewDiagnostics.TileState b) {
        return a.meshMipVersion() == b.meshMipVersion()
                && a.meshSurfaceRevision() == b.meshSurfaceRevision();
    }

    private static boolean matchesMesh(
            FarViewDiagnostics.Event publication, FarViewDiagnostics.TileState tile) {
        return publication != null
                && publication.versions() != null
                && publication.step() == tile.requestedStep()
                && publication.versions().mipVersion() == tile.meshMipVersion()
                && publication.versions().surfaceRevision() == tile.meshSurfaceRevision();
    }

    private static boolean completeResident(FarViewDiagnostics.TileState tile) {
        return tile.meshPresent()
                && tile.meshComplete()
                && !tile.retiring()
                && !tile.fading()
                && !tile.previousMesh();
    }

    private static boolean settled(
            FarViewDiagnostics.TileState tile, long time, List<FarViewDiagnostics.Event> events) {
        // requestedStep is overwritten with the in-flight job's step in RegionState. Without
        // a separate resident step we cannot correlate that sample with a published mesh.
        if (!completeResident(tile)
                || tile.jobs() != 0
                || tile.requestedStep() <= 0
                || tile.versions() == null
                || tile.meshMipVersion() < 0
                || tile.meshMipVersion() != tile.versions().mipVersion()) return false;
        if (tile.meshSurfaceRevision() == tile.versions().surfaceRevision()) return true;
        // Core accepts a new surface revision with sameColumns and does not rebuild it.
        // Only its explicit inputs-unchanged decision can establish this equivalence here.
        long changed = Long.MIN_VALUE;
        for (var event : events)
            if (event.nanoTime() <= time
                    && (installed(event) || event.kind() == FarViewDiagnostics.Kind.RETIRE))
                changed = Math.max(changed, event.nanoTime());
        long lastMeshChange = changed;
        return events.stream()
                .anyMatch(
                        e ->
                                e.nanoTime() > lastMeshChange
                                        && e.nanoTime() <= time
                                        && e.kind() == FarViewDiagnostics.Kind.SKIP
                                        && e.reason().equals("inputs-unchanged")
                                        && e.step() == tile.requestedStep()
                                        && e.versions() != null
                                        && e.versions().surfaceRevision()
                                                == tile.versions().surfaceRevision()
                                        && e.versions().mipVersion() == tile.meshMipVersion());
    }

    private boolean eligible(FarViewDiagnostics.RegionState state) {
        return state.epoch() == watch.epoch() && state.planet() == watch.planet();
    }

    private void routeChecks(List<Assertion> checks) {
        var poses = new LinkedHashMap<String, Pose>();
        for (var sample : samples) {
            var p = sample.pose();
            if (eligible(sample.state())
                    && p != null
                    && p.planet() == watch.planet()
                    && p.face() == watch.face()
                    && finite(p)) poses.putIfAbsent(sample.phase(), p);
        }
        Pose far = poses.get("baseline"),
                near = poses.get("approach"),
                retreat = poses.get("retreat");
        Pose height = poses.get("height"), turn = poses.get("turn");
        boolean order = ordered("baseline", "approach", "retreat", "height", "turn");
        checks.add(
                assertion(
                        "routePhases",
                        order,
                        "ordered baseline/approach/retreat/height/turn with core samples"));
        checks.add(
                assertion(
                        "approachMotion",
                        order
                                && far != null
                                && near != null
                                && distance(far) - distance(near) >= 32,
                        "observed chart distance to watch decreases >=32 blocks"));
        checks.add(
                assertion(
                        "retreatMotion",
                        order
                                && near != null
                                && retreat != null
                                && distance(retreat) - distance(near) >= 32,
                        "observed chart distance to watch increases >=32 blocks"));
        checks.add(
                assertion(
                        "heightMotion",
                        order
                                && retreat != null
                                && height != null
                                && Math.abs(height.y() - retreat.y()) >= 32,
                        "observed chart height change >=32 blocks"));
        checks.add(
                assertion(
                        "turnMotion",
                        order
                                && height != null
                                && turn != null
                                && yawDelta(height.yaw(), turn.yaw()) >= 45,
                        "observed yaw change >=45 degrees"));
    }

    private boolean ordered(String... labels) {
        int prior = -1;
        for (String label : labels) {
            int found = -1;
            for (int i = 0; i < samples.size(); i++)
                if (samples.get(i).phase().equals(label) && eligible(samples.get(i).state())) {
                    found = i;
                    break;
                }
            if (found <= prior) return false;
            prior = found;
        }
        return true;
    }

    private static boolean finite(Pose p) {
        return Double.isFinite(p.x())
                && Double.isFinite(p.y())
                && Double.isFinite(p.z())
                && Double.isFinite(p.yaw());
    }

    private double distance(Pose p) {
        return Math.hypot(p.x() - (watch.x() + 8), p.z() - (watch.z() + 8));
    }

    private static double yawDelta(double a, double b) {
        return Math.abs(Math.IEEEremainder(b - a, 360));
    }

    private static Assertion assertion(String name, boolean met, String detail) {
        return new Assertion(name, met ? "PASS" : "INCOMPLETE", detail);
    }

    Report report(String termination) {
        return new Report(
                1,
                "CPU lifecycle and sampled player pose; no pixel visibility claim",
                watch,
                termination,
                ticks,
                List.copyOf(marks),
                List.copyOf(samples),
                List.copyOf(events),
                latest,
                check());
    }
}
