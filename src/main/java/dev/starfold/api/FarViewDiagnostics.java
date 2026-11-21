package dev.starfold.api;

import java.util.*;

/**
 * Opt-in far-view evidence for one planet and a square of chart tiles. Coordinates are
 * chart-face block coordinates (16-block origins), never storage chunks or projected world positions.
 * Consumers may serialize the immutable records with Gson. No renderer objects or GL reads escape.
 *
 * <p>All retained state is guarded by LOCK; disabled hot-path tests are volatile reads and allocate
 * nothing. Renderer callbacks carry a watch/reset epoch, so late jobs cannot contaminate a new watch.
 * Event cursors are non-destructive and independent. Caps: 25 tiles, 512 events, 75 geometry slots,
 * 64 quads/slot, 8 vertices/quad. Draw/cull events are sampled per tile/source/kind every 250ms.
 */
public final class FarViewDiagnostics {
    public static final int MAX_RADIUS = 2,
            MAX_EVENTS = 512,
            MAX_GEOMETRY = 75,
            MAX_QUADS = 64,
            MAX_POINTS = 8;
    public static final long SAMPLE_NANOS = 250_000_000L;
    private static final Object LOCK = new Object();
    private static volatile Watch watched;
    private static volatile int rendererPlanet = -1;
    private static volatile long rendererSession;
    private static long nextEpoch, sequence, droppedEvents, lastRegion;
    private static RegionState region;
    private static final ArrayDeque<Event> events = new ArrayDeque<>();
    private static final LinkedHashMap<String, Geometry> geometry = new LinkedHashMap<>();
    private static final ArrayDeque<CpuRecord> records = new ArrayDeque<>();
    private static long droppedRecords;
    public static final int MAX_RECORDS = 128;
    // One-shot GPU observations cannot be resampled after CPU packing evicts them.
    public static final int MAX_DRAW_RECORDS = 60;
    public static final String DRAW_GPU_STATE = "DRAW_GPU_STATE";
    private static int retainedDrawRecords;
    private static final long[] sampled =
            new long[25 * Source.values().length * Kind.values().length];

    public enum Source {
        RESIDENT,
        PARENT,
        CHART,
        STITCH,
        OWNERSHIP,
        SELECTION
    }

    public enum Kind {
        RESET,
        REQUEST,
        SKIP,
        CANCEL,
        STALE,
        UPLOAD,
        PUBLICATION,
        DRAW,
        CULL,
        RETIRE,
        SELECT
    }

    public record Watch(int planet, int face, int x, int z, int radius, long epoch) {}

    public record Versions(
            long surfaceRevision,
            long overlayVersion,
            long mipVersion,
            long selectionVersion,
            long coverEpoch,
            long stagedEpoch) {}

    public record Event(
            long sequence,
            long nanoTime,
            long epoch,
            int planet,
            int face,
            int x,
            int z,
            Source source,
            Kind kind,
            String reason,
            int step,
            Versions versions) {}

    public record TileState(
            int face,
            int x,
            int z,
            boolean nativeWindow,
            int requestedStep,
            int selectedStep,
            boolean meshPresent,
            boolean meshComplete,
            boolean previousMesh,
            boolean retiring,
            boolean fading,
            boolean queued,
            int jobs,
            int chartRequestedStep,
            int chartDrawnStep,
            boolean chartMeshPresent,
            boolean parentMeshPresent,
            boolean stagedMeshPresent,
            Versions versions,
            long meshMipVersion,
            long meshSurfaceRevision) {}

    /**
     * Latest completed AFTER_PARTICLES frame. Tile nativeWindow is final-frame submitted coverage,
     * not an opaque-stage observation; consumers may sample this snapshot at their own cadence.
     */
    public record RegionState(
            long nanoTime,
            long epoch,
            int planet,
            int nativeFace,
            boolean selectionActive,
            boolean coverageReady,
            boolean rendererReady,
            int queuedTotal,
            int jobsTotal,
            List<TileState> tiles) {
        public RegionState {
            tiles = List.copyOf(tiles);
        }
    }

    public record Point(double x, double y, double z) {}

    /** packedLight=-1: raw voxel quads precede vertex lighting and have no packed light value. */
    public record QuadSample(
            int index,
            String material,
            int axis,
            boolean volume,
            int rgb,
            int packedLight,
            List<Point> points) {
        public QuadSample {
            points = List.copyOf(points);
        }
    }

    public record Geometry(
            long epoch,
            int planet,
            int face,
            int x,
            int z,
            Source source,
            String stage,
            int step,
            long nanoTime,
            Versions versions,
            int totalQuads,
            List<QuadSample> sampledQuads,
            boolean truncated) {
        public Geometry {
            sampledQuads = List.copyOf(sampledQuads);
        }
    }

    public record CpuRecord(
            long epoch,
            int planet,
            int face,
            int x,
            int z,
            String stage,
            long nanoTime,
            Map<String, Object> fields) {}

    public record Snapshot(
            Watch watch,
            int rendererPlanet,
            long rendererSession,
            long epoch,
            RegionState region,
            List<Event> events,
            List<Geometry> geometry,
            long droppedEvents,
            List<CpuRecord> records,
            long droppedRecords) {
        public Snapshot {
            events = List.copyOf(events);
            geometry = List.copyOf(geometry);
            records = List.copyOf(records);
        }

        public Snapshot(
                Watch watch,
                int rendererPlanet,
                long rendererSession,
                long epoch,
                RegionState region,
                List<Event> events,
                List<Geometry> geometry,
                long droppedEvents) {
            this(
                    watch,
                    rendererPlanet,
                    rendererSession,
                    epoch,
                    region,
                    events,
                    geometry,
                    droppedEvents,
                    List.of(),
                    0);
        }
    }

    private FarViewDiagnostics() {}

    public static Watch watch(int planet, int face, int x, int z, int radius) {
        if (planet < 0
                || face < 0
                || face >= 6
                || x < 0
                || x >= 2048
                || z < 0
                || z >= 2048
                || radius < 0
                || radius > MAX_RADIUS)
            throw new IllegalArgumentException(
                    "Expected planet>=0, face 0..5, x/z 0..2047, radius 0..2");
        synchronized (LOCK) {
            clearRetained();
            watched = new Watch(planet, face, x & ~15, z & ~15, radius, ++nextEpoch);
            return watched;
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            watched = null;
            ++nextEpoch;
            clearRetained();
        }
    }

    public static boolean active() {
        return watched != null;
    }

    public static boolean matches(int face, int x, int z) {
        Watch w = watched;
        return w != null && rendererPlanet == w.planet() && contains(w, face, x, z);
    }

    public static boolean matches(int face, long key) {
        return key >= 0
                && key < 98304
                && face == key / 16384
                && matches(face, (int) (key % 128) * 16, (int) (key / 128 % 128) * 16);
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(
                    watched,
                    rendererPlanet,
                    rendererSession,
                    watched == null ? 0 : watched.epoch(),
                    region,
                    new ArrayList<>(events),
                    new ArrayList<>(geometry.values()),
                    droppedEvents,
                    new ArrayList<>(records),
                    droppedRecords);
        }
    }

    public static List<Event> eventsSince(long afterSequence) {
        synchronized (LOCK) {
            return events.stream().filter(e -> e.sequence() > afterSequence).toList();
        }
    }

    public static List<Geometry> geometry() {
        synchronized (LOCK) {
            return List.copyOf(geometry.values());
        }
    }

    // Renderer integration. No callback accepts a mutable render object.
    public static long epoch(int planet) {
        Watch w = watched;
        return w != null && w.planet() == planet && rendererPlanet == planet ? w.epoch() : 0;
    }

    public static Watch currentWatch(long epoch) {
        Watch w = watched;
        return w != null && w.epoch() == epoch && rendererPlanet == w.planet() ? w : null;
    }

    public static void observeContext(int planet, long session, boolean reset, String reason) {
        synchronized (LOCK) {
            boolean changed = rendererPlanet != planet || rendererSession != session;
            rendererPlanet = planet;
            rendererSession = session;
            Watch w = watched;
            if (w == null || !changed && !reset) return;
            clearRetained();
            watched = new Watch(w.planet(), w.face(), w.x(), w.z(), w.radius(), ++nextEpoch);
            // Reset belongs to the watch, but carries the actual renderer planet (possibly -1).
            events.add(
                    new Event(
                            ++sequence,
                            System.nanoTime(),
                            watched.epoch(),
                            planet,
                            w.face(),
                            w.x(),
                            w.z(),
                            Source.SELECTION,
                            Kind.RESET,
                            reason,
                            0,
                            null));
        }
    }

    public static boolean sampleDue(long epoch) {
        if (epoch == 0) return false;
        synchronized (LOCK) {
            if (!valid(epoch)) return false;
            long now = System.nanoTime();
            if (lastRegion != 0 && now - lastRegion < SAMPLE_NANOS) return false;
            lastRegion = now;
            return true;
        }
    }

    public static void observeRegion(RegionState state) {
        synchronized (LOCK) {
            if (valid(state.epoch())
                    && state.planet() == rendererPlanet
                    && state.tiles().size() <= 25) region = state;
        }
    }

    public static void observeEvent(
            long epoch,
            int face,
            int x,
            int z,
            Source source,
            Kind kind,
            String reason,
            int step,
            Versions versions) {
        if (epoch == 0) return;
        synchronized (LOCK) {
            if (!valid(epoch) || !contains(watched, face, x, z)) return;
            long now = System.nanoTime();
            if (kind == Kind.DRAW || kind == Kind.CULL) {
                int tile = ((z & ~15) - watched.z()) / 16 + 2;
                tile = tile * 5 + ((x & ~15) - watched.x()) / 16 + 2;
                int slot =
                        (tile * Source.values().length + source.ordinal()) * Kind.values().length
                                + kind.ordinal();
                if (sampled[slot] != 0 && now - sampled[slot] < SAMPLE_NANOS) return;
                sampled[slot] = now;
            }
            if (events.size() == MAX_EVENTS) {
                events.removeFirst();
                droppedEvents++;
            }
            events.addLast(
                    new Event(
                            ++sequence,
                            now,
                            epoch,
                            rendererPlanet,
                            face,
                            x & ~15,
                            z & ~15,
                            source,
                            kind,
                            reason,
                            step,
                            versions));
        }
    }

    public static void observeGeometry(Geometry value) {
        if (value.epoch() == 0) return;
        synchronized (LOCK) {
            if (!valid(value.epoch())
                    || value.planet() != rendererPlanet
                    || !contains(watched, value.face(), value.x(), value.z())
                    || value.sampledQuads().size() > MAX_QUADS
                    || value.sampledQuads().stream().anyMatch(q -> q.points().size() > MAX_POINTS))
                return;
            String key = value.face() + ":" + value.x() + ":" + value.z() + ":" + value.source();
            geometry.remove(key);
            if (geometry.size() == MAX_GEOMETRY)
                geometry.remove(geometry.keySet().iterator().next());
            geometry.put(key, value);
        }
    }

    /**
     * Bounded, deeply copied evidence. Unsupported values are rejected. The first MAX_DRAW_RECORDS
     * DRAW_GPU_STATE observations survive ordinary CPU-record eviction until watch/reset; all records
     * still share MAX_RECORDS and its loss counter. No GL queries occur in this API.
     */
    public static void record(
            long epoch,
            int planet,
            int face,
            int x,
            int z,
            String stage,
            Map<String, Object> fields) {
        if (epoch == 0) return;
        synchronized (LOCK) {
            if (!valid(epoch) || rendererPlanet != planet || !contains(watched, face, x, z)) return;
            if (stage == null || stage.length() > 64) throw new IllegalArgumentException("stage");
            boolean draw = DRAW_GPU_STATE.equals(stage);
            if (draw && retainedDrawRecords == MAX_DRAW_RECORDS) {
                droppedRecords++;
                return;
            }
            int[] remaining = {8192};
            @SuppressWarnings("unchecked")
            Map<String, Object> copy = (Map<String, Object>) immutable(fields, remaining, 0);
            if (records.size() == MAX_RECORDS) {
                var iterator = records.iterator();
                while (iterator.hasNext())
                    if (!DRAW_GPU_STATE.equals(iterator.next().stage())) {
                        iterator.remove();
                        break;
                    }
                droppedRecords++;
            }
            if (draw) retainedDrawRecords++;
            records.addLast(
                    new CpuRecord(
                            epoch, planet, face, x & ~15, z & ~15, stage, System.nanoTime(), copy));
        }
    }

    private static Object immutable(Object value, int[] remaining, int depth) {
        if (--remaining[0] < 0 || depth > 10)
            throw new IllegalArgumentException("CPU evidence exceeds bounded payload");
        if (value == null
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long) return value;
        if (value instanceof Float n && Float.isFinite(n)) return n;
        if (value instanceof Double n && Double.isFinite(n)) return n;
        if (value instanceof String s && s.length() <= 2048) return s;
        if (value instanceof Map<?, ?> map && map.size() <= 128) {
            var copy = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || key.length() > 128)
                    throw new IllegalArgumentException("CPU evidence map key");
                copy.put(key, immutable(entry.getValue(), remaining, depth + 1));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list && list.size() <= 256) {
            var copy = new ArrayList<Object>(list.size());
            for (var item : list) copy.add(immutable(item, remaining, depth + 1));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("CPU evidence must contain bounded JSON values");
    }

    private static boolean valid(long epoch) {
        return watched != null && watched.epoch() == epoch && watched.planet() == rendererPlanet;
    }

    private static boolean contains(Watch w, int face, int x, int z) {
        return face == w.face()
                && x >= 0
                && x < 2048
                && z >= 0
                && z < 2048
                && Math.abs((x & ~15) - w.x()) <= w.radius() * 16
                && Math.abs((z & ~15) - w.z()) <= w.radius() * 16;
    }

    private static void clearRetained() {
        events.clear();
        geometry.clear();
        records.clear();
        region = null;
        droppedEvents = 0;
        droppedRecords = 0;
        retainedDrawRecords = 0;
        lastRegion = 0;
        Arrays.fill(sampled, 0);
    }
}
