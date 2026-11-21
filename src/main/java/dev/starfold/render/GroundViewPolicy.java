package dev.starfold.render;

import dev.starfold.chart.PlanetShape;

import java.util.*;

/** Work/LOD policy only. Never changes canonical world state, picking or collision. */
public final class GroundViewPolicy {
    public static final int PATCH_COUNT = 384;
    public static final int LIGHT_REFRESH_TICKS = 100;
    public static final long FRAME_BUDGET_NS = 2_000_000;
    public static final long DETAIL_BYTES = GroundGpuBudget.SELECTED.detailBytes;

    /** Recover another sibling batch as soon as previous visible work has drained. */
    static int recoveryGroups(boolean demandEmpty, int pendingUploads, int jobs, int capacity) {
        return demandEmpty && pendingUploads == 0 && jobs == 0 ? Math.max(0, capacity / 4) : 0;
    }

    public static int patch(long key) {
        if (key < 0 || key >= 98304) throw new IllegalArgumentException("Native tile");
        int face = (int) (key / 16384), u = (int) (key % 128), v = (int) (key / 128 % 128);
        return face * 64 + u / 16 + 8 * (v / 16);
    }

    public static int face(int patch) {
        return patch / 64;
    }

    public static int x(int patch) {
        return patch % 8 * 256;
    }

    public static int z(int patch) {
        return patch / 8 % 8 * 256;
    }

    /**
     * Does this patch lie on a planet of this shape? A smaller planet fills only the first patches
     * of each face.
     */
    public static boolean exists(PlanetShape shape, int patch) {
        return x(patch) < shape.size() && z(patch) < shape.size();
    }

    static List<Integer> nearestPatches(
            PlanetShape shape, dev.starfold.geometry.BentStripGeometry.Point camera) {
        var order = new ArrayList<Integer>();
        for (int p = 0; p < PATCH_COUNT; p++) if (exists(shape, p)) order.add(p);
        order.sort(Comparator.comparingDouble(p -> GroundFarMesh.distance(shape, camera, p, 0)));
        return order;
    }

    public static Set<Long> details(PlanetShape.Pose pose, boolean controlled) {
        return details(PlanetShape.STANDARD, pose, controlled);
    }

    public static Set<Long> details(PlanetShape shape, PlanetShape.Pose pose, boolean controlled) {
        return pose.point().y() > 256
                ? Set.of()
                : GroundStreaming.footprint(shape, pose, GroundStreaming.MAX_RADIUS);
    }

    public static boolean nearSeam(PlanetShape.Pose pose) {
        return nearSeam(PlanetShape.STANDARD, pose);
    }

    public static boolean nearSeam(PlanetShape shape, PlanetShape.Pose pose) {
        var p = pose.point();
        return Math.min(
                        Math.min(p.x(), shape.size() - p.x()),
                        Math.min(p.z(), shape.size() - p.z()))
                < 64;
    }

    public static List<Long> subscription(
            PlanetShape.Pose pose, boolean controlled, BitSet changed, int cursor) {
        var all = new ArrayList<>(details(pose, controlled));
        var out = new LinkedHashSet<Long>();
        for (int i = 0; i < Math.min(9, all.size()); i++) out.add(all.get(i));
        for (int i = 0; i < all.size() && out.size() < 14; i++)
            out.add(all.get(Math.floorMod((long) cursor * 5 + i, all.size())));
        int[] edited = changed.stream().toArray();
        for (int i = 0; i < edited.length && i < 2; i++)
            out.add((long) edited[Math.floorMod((long) cursor * 2 + i, edited.length)]);
        return List.copyOf(out);
    }

    /** Fill missing seam volumes first, but reserve refresh slots so edits/light cannot starve. */
    public static List<Long> subscription(
            PlanetShape.Pose pose,
            boolean controlled,
            BitSet changed,
            int cursor,
            java.util.function.LongPredicate detailed) {
        return subscription(PlanetShape.STANDARD, pose, controlled, changed, cursor, detailed);
    }

    public static List<Long> subscription(
            PlanetShape shape,
            PlanetShape.Pose pose,
            boolean controlled,
            BitSet changed,
            int cursor,
            java.util.function.LongPredicate detailed) {
        return GroundStreaming.request(details(shape, pose, controlled), cursor, detailed);
    }

    public static boolean captureNeeded(
            boolean resident, long currentVersion, long priorVersion, long now, long sampledAt) {
        return !resident
                || currentVersion != priorVersion
                || now < sampledAt
                || now - sampledAt >= LIGHT_REFRESH_TICKS;
    }

    /** Two bounded dirty queues keep arriving edits out of an immutable cover build. */
}
