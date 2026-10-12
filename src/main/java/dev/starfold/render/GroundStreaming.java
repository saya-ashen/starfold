package dev.starfold.render;

import dev.starfold.ground.GroundCoordinates;
import dev.starfold.ground.GroundTopology;
import dev.starfold.planet.PlanetShape;

import net.minecraft.core.BlockPos;

import java.util.*;

/** Canonical, face-independent working set. Display replicas only; vanilla chunks own the world. */
public final class GroundStreaming {
    public static final int MAX_RADIUS = 8, RETAIN_MARGIN = 2;

    /** One request per REQUEST_TICKS: nearest missing volumes, then edited tiles, then a slow refresh rotation. */
    public static final int REQUEST_TILES = 64,
            REQUEST_MISSING = 48,
            REQUEST_STALE = 8,
            REQUEST_REFRESH = 4,
            REQUEST_TICKS = 10;

    /** Server capture work per viewer per tick; a tile already being captured is never abandoned. */
    public static final int SERVE_TILES = 6;

    public static final long SERVE_BUDGET_NS = 10_000_000;

    /** No snapshot for this long means the stream is idle; tiles still waiting for a neighbour may then build. */
    public static final int STALL_TICKS = 40;

    public static final long ESTIMATED_MESH_BYTES = 256 * 1024, MAX_MESH_BYTES = 8L * 1024 * 1024;
    private Set<Long> required = Set.of(), resident = Set.of();
    private GroundTopology.Pose last;
    private int lastRadius = -1;

    public static int radius(int viewDistance) {
        return Math.clamp(viewDistance, 2, MAX_RADIUS);
    }

    /** Nearest rings first, with canonical key tie-breaking (not source-face iteration order). */
    public static Set<Long> footprint(GroundTopology.Pose pose, int radius) {
        return footprint(PlanetShape.STANDARD, pose, radius);
    }

    public static Set<Long> footprint(PlanetShape shape, GroundTopology.Pose pose, int radius) {
        var feet = GroundTopology.feet(pose);
        int x = (int) Math.floor(feet.x() / 16), z = (int) Math.floor(feet.z() / 16);
        var result = new LinkedHashSet<Long>();
        for (int r = 0; r <= radius; r++) {
            var ring = new TreeSet<Long>();
            for (int a = -r; a <= r; a++)
                for (int b = -r; b <= r; b++)
                    if (Math.max(Math.abs(a), Math.abs(b)) == r) {
                        var owner =
                                GroundTopology.address(
                                                shape,
                                                new BlockPos(
                                                        (x + a) * 16 + 8, 128, (z + b) * 16 + 8))
                                        .owner();
                        long key = GroundSurfaceIndex.key(owner.getX() >> 4, owner.getZ() >> 4);
                        if (key >= 0) ring.add(key);
                    }
            result.addAll(ring);
        }
        return Collections.unmodifiableSet(result);
    }

    public void update(GroundTopology.Pose pose, int viewDistance) {
        update(PlanetShape.STANDARD, pose, viewDistance);
    }

    public void update(PlanetShape shape, GroundTopology.Pose pose, int viewDistance) {
        int r = radius(viewDistance);
        // Sub-block movement and camera turns cannot mutate residency.
        if (last != null
                && last.face() == pose.face()
                && lastRadius == r
                && (int) (last.point().x() / 16) == (int) (pose.point().x() / 16)
                && (int) (last.point().z() / 16) == (int) (pose.point().z() / 16)
                && (last.point().y() > 256) == (pose.point().y() > 256)) return;
        last = pose;
        lastRadius = r;
        if (pose.point().y() > 256) {
            required = resident = Set.of();
            return;
        }
        required = footprint(shape, pose, r);
        var outer = footprint(shape, pose, r + RETAIN_MARGIN);
        var next = new LinkedHashSet<>(required);
        for (long key : outer) if (resident.contains(key)) next.add(key);
        resident = Collections.unmodifiableSet(next);
    }

    public Set<Long> required() {
        return required;
    }

    public Set<Long> resident() {
        return resident;
    }

    public void clear() {
        required = resident = Set.of();
        last = null;
        lastRadius = -1;
    }

    /** Measured admission, not evict-and-rebuild. An unchanged over-budget tile is not retried. */
    public static Set<Long> admit(Collection<Long> ordered, Map<Long, Long> measured, long budget) {
        var result = new LinkedHashSet<Long>();
        long used = 0;
        for (long key : ordered) {
            long cost = measured.getOrDefault(key, ESTIMATED_MESH_BYTES);
            if (cost < 0) throw new IllegalArgumentException("Negative mesh size");
            if (cost > MAX_MESH_BYTES || cost > budget - used) continue;
            used += cost;
            result.add(key);
        }
        return Collections.unmodifiableSet(result);
    }

    /** Keep admitted meshes sticky under pressure; only the immediate 3x3 can preempt them.
     * Crossing the same edge repeatedly must not alternate two equally near budget victims. */
    public static Set<Long> admit(
            Collection<Long> ordered, Map<Long, Long> measured, long budget, Set<Long> previous) {
        var priority = new LinkedHashSet<Long>();
        int count = 0;
        for (long key : ordered) {
            if (count++ == 9) break;
            priority.add(key);
        }
        for (long key : ordered) if (previous.contains(key)) priority.add(key);
        priority.addAll(ordered);
        return admit(priority, measured, budget);
    }

    public static List<Long> request(
            Collection<Long> ordered, int cursor, java.util.function.LongPredicate detailed) {
        return request(ordered, cursor, detailed, key -> false);
    }

    /** Missing near owners first, then tiles whose edits are newer than their snapshot (light follows
     * the block change), then a rotation so unprompted light changes still refresh. */
    public static List<Long> request(
            Collection<Long> ordered,
            int cursor,
            java.util.function.LongPredicate detailed,
            java.util.function.LongPredicate stale) {
        var all = new ArrayList<>(ordered);
        var out = new LinkedHashSet<Long>();
        for (long key : all) {
            if (out.size() == REQUEST_MISSING) break;
            if (!detailed.test(key)) out.add(key);
        }
        int limit = out.size() + REQUEST_STALE;
        for (long key : all) {
            if (out.size() == limit) break;
            if (detailed.test(key) && stale.test(key)) out.add(key);
        }
        for (int i = 0; i < all.size() && i < REQUEST_REFRESH && out.size() < REQUEST_TILES; i++)
            out.add(all.get(Math.floorMod((long) cursor * REQUEST_REFRESH + i, all.size())));
        return List.copyOf(out);
    }

    /** The four canonical edge neighbours (-u,+u,-v,+v in the tile's own chart), transported over seams. */
    public static long[] neighbours(long key) {
        return neighbours(PlanetShape.STANDARD, key);
    }

    public static long[] neighbours(PlanetShape shape, long key) {
        if (key < 0 || key >= 98304) throw new IllegalArgumentException("Native tile");
        int face = (int) (key / 16384),
                x = face * GroundCoordinates.STRIDE + (int) (key % 128) * 16,
                z = (int) (key / 128 % 128) * 16;
        var out = new long[4];
        int i = 0;
        for (int[] p :
                new int[][] {{x - 1, z + 8}, {x + 16, z + 8}, {x + 8, z - 1}, {x + 8, z + 16}}) {
            var owner = GroundTopology.address(shape, new BlockPos(p[0], 128, p[1])).owner();
            out[i++] = GroundSurfaceIndex.key(owner.getX() >> 4, owner.getZ() >> 4);
        }
        return out;
    }

    /** Nearest tile to mesh next. A tile waits for the resident neighbours it will be culled and lit
     * against, so a neighbour arriving a moment later does not throw the finished mesh away.
     * Returns -1 when every candidate is still waiting and the stream has not stalled. */
    public static long next(
            Collection<Long> ordered,
            java.util.function.LongPredicate candidate,
            java.util.function.LongPredicate arrived,
            Set<Long> resident,
            boolean stalled) {
        return next(PlanetShape.STANDARD, ordered, candidate, arrived, resident, stalled);
    }

    public static long next(
            PlanetShape shape,
            Collection<Long> ordered,
            java.util.function.LongPredicate candidate,
            java.util.function.LongPredicate arrived,
            Set<Long> resident,
            boolean stalled) {
        long fallback = -1;
        for (long key : ordered) {
            if (!candidate.test(key)) continue;
            boolean ready = true;
            for (long neighbour : neighbours(shape, key))
                if (resident.contains(neighbour) && !arrived.test(neighbour)) {
                    ready = false;
                    break;
                }
            if (ready) return key;
            if (fallback < 0) fallback = key;
        }
        return stalled ? fallback : -1;
    }

    /** Canonical owners of the chart chunks vanilla draws around the observer: the same rounded
     * view-distance shape as the vanilla section graph, continued across face seams. */
    public static Set<Long> window(GroundTopology.Pose pose, int viewDistance) {
        return window(PlanetShape.STANDARD, pose, viewDistance);
    }

    public static Set<Long> window(PlanetShape shape, GroundTopology.Pose pose, int viewDistance) {
        var feet = GroundTopology.feet(pose);
        int x = (int) Math.floor(feet.x() / 16), z = (int) Math.floor(feet.z() / 16);
        var out = new HashSet<Long>();
        for (int a = -viewDistance; a <= viewDistance; a++)
            for (int b = -viewDistance; b <= viewDistance; b++) {
                long far = Math.max(0, Math.max(Math.abs(a), Math.abs(b)) - 1),
                        near = Math.max(0, Math.min(Math.abs(a), Math.abs(b)) - 1);
                if (near * near + far * far >= (long) viewDistance * viewDistance) continue;
                var owner =
                        GroundTopology.address(
                                        shape,
                                        new BlockPos((x + a) * 16 + 8, 128, (z + b) * 16 + 8))
                                .owner();
                long key = GroundSurfaceIndex.key(owner.getX() >> 4, owner.getZ() >> 4);
                if (key >= 0) out.add(key);
            }
        return Collections.unmodifiableSet(out);
    }

    public GroundStreaming() {}
}
