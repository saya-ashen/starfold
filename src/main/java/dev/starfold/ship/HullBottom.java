package dev.starfold.ship;

import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.UnaryOperator;

/** Lowest collision block per plot XZ column, paired with terrain only under its real bottom face. */
public final class HullBottom {
    // Values contain no body/level references, so discarded client and server hulls can be collected.
    private static final Map<SubLevel, List<AABB>> CACHE = new WeakHashMap<>();

    /** Called by the shared Sable plot mutation hooks, including same-mass shape replacements. */
    public static synchronized void invalidate(SubLevel body) {
        CACHE.remove(body);
    }

    /** Sable also emits Load when a full client packet replaces an already-existing plot chunk. */
    static synchronized void chunkLoaded(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        CACHE.keySet().removeIf(body -> body.getLevel() == event.getLevel()
                && body.getPlot().contains(event.getChunk().getPos()));
    }

    // Lift, fluid support and every gyro read the same pose within one physics substep.
    private record Measured(List<AABB> shapes, long tick, double[] pose, HullLanding.Ground ground) {}

    private static final Map<SubLevel, Measured> LAST = new WeakHashMap<>();

    /** One measurement per body, pose, collision bottom and game tick; terrain is reread each tick. */
    static HullLanding.Ground ground(SubLevel body, HullLanding.SurfaceColumn column) {
        var shapes = shapes(body);
        var p = body.logicalPose();
        var at = p.position();
        var q = p.orientation();
        var r = p.rotationPoint();
        var s = p.scale();
        double[] pose = {at.x(), at.y(), at.z(), q.x(), q.y(), q.z(), q.w(),
                r.x(), r.y(), r.z(), s.x(), s.y(), s.z()};
        long tick = body.getLevel().getGameTime();
        synchronized (LAST) {
            var last = LAST.get(body);
            if (last != null && last.shapes == shapes && last.tick == tick
                    && java.util.Arrays.equals(last.pose, pose)) return last.ground;
        }
        var ground = measure(shapes, p::transformPosition, column);
        synchronized (LAST) {
            LAST.put(body, new Measured(shapes, tick, pose, ground));
        }
        return ground;
    }

    static synchronized List<AABB> shapes(SubLevel body) {
        return CACHE.computeIfAbsent(body, HullBottom::scan);
    }

    private static List<AABB> scan(SubLevel body) {
        var plot = body.getPlot().getBoundingBox();
        var level = body.getLevel();
        var result = new ArrayList<AABB>();
        var pos = new BlockPos.MutableBlockPos();
        for (int x = plot.minX(); x <= plot.maxX(); x++)
            for (int z = plot.minZ(); z <= plot.maxZ(); z++)
                for (int y = plot.minY(); y <= plot.maxY(); y++) {
                    pos.set(x, y, z);
                    var boxes = level.getBlockState(pos).getCollisionShape(level, pos).toAabbs();
                    if (boxes.isEmpty()) continue;
                    for (var box : boxes) result.add(box.move(x, y, z));
                    break;
                }
        return List.copyOf(result);
    }

    /** Pure geometry shared by client/server. Transform is the current logical pose, never render bounds.
     * Unknown columns are skipped; solid wins an exact minimum-gap tie. No penetration clamp. */
    static HullLanding.Ground measure(
            List<AABB> shapes, UnaryOperator<Vector3d> transform, HullLanding.SurfaceColumn column) {
        var work = new Measurement(column, 4);
        var vertex = new Vector3d();
        for (var box : shapes) {
            work.put(0, transform.apply(vertex.set(box.minX, box.minY, box.minZ)));
            work.put(3, transform.apply(vertex.set(box.maxX, box.minY, box.minZ)));
            work.put(6, transform.apply(vertex.set(box.maxX, box.minY, box.maxZ)));
            work.put(9, transform.apply(vertex.set(box.minX, box.minY, box.maxZ)));
            work.face(4);
        }
        return work.result();
    }

    static HullLanding.Ground measureFaces(
            List<List<Vector3d>> faces, HullLanding.SurfaceColumn column) {
        int vertices = 4;
        for (var face : faces) vertices = Math.max(vertices, face.size());
        var work = new Measurement(column, vertices);
        for (var face : faces) {
            for (int i = 0; i < face.size(); i++) work.put(i * 3, face.get(i));
            work.face(face.size());
        }
        return work.result();
    }

    /** Invocation-local scratch: client/server (and nested measurements) cannot share mutable buffers.
     * Reuse the same two clipping arrays for every face/column. Terrain uses primitive keys rather
     * than boxed longs; unlike a bounding-rectangle array it stays bounded for sparse hulls. */
    private static final class Measurement {
        final HullLanding.SurfaceColumn column;
        final double[] face, a, b;
        long[] keys = new long[1024];
        HullLanding.Surface[] surfaces = new HullLanding.Surface[1024];
        int size;
        double gap = Double.POSITIVE_INFINITY;
        boolean fluid;

        Measurement(HullLanding.SurfaceColumn column, int vertices) {
            this.column = column;
            face = new double[vertices * 3];
            // A convex polygon gains at most one vertex per clipping plane.
            a = new double[(vertices + 4) * 3];
            b = new double[a.length];
        }

        void put(int i, Vector3d v) {
            face[i] = v.x; face[i + 1] = v.y; face[i + 2] = v.z;
        }

        HullLanding.Ground result() {
            return new HullLanding.Ground(Double.isFinite(gap) ? gap : Double.NaN, fluid);
        }

        void face(int vertices) {
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minZ = minX, maxZ = maxX;
            for (int i = 0; i < vertices * 3; i += 3) {
                minX = Math.min(minX, face[i]); maxX = Math.max(maxX, face[i]);
                minZ = Math.min(minZ, face[i + 2]); maxZ = Math.max(maxZ, face[i + 2]);
            }
            double endX = Math.floor(Math.nextDown(maxX)), endZ = Math.floor(Math.nextDown(maxZ));
            for (int x = (int) Math.floor(minX); x <= endX; x++)
                for (int z = (int) Math.floor(minZ); z <= endZ; z++) {
                    // A plane containing every original corner cannot clip this convex face.
                    // In particular, a face wholly inside one column needs no clipping copies.
                    double[] cut = face;
                    int n = vertices;
                    if (minX < x) {
                        n = clip(cut, n, a, 0, x, true);
                        cut = a;
                    }
                    if (maxX > x + 1.) {
                        double[] out = cut == a ? b : a;
                        n = clip(cut, n, out, 0, x + 1., false);
                        cut = out;
                    }
                    if (minZ < z) {
                        double[] out = cut == a ? b : a;
                        n = clip(cut, n, out, 2, z, true);
                        cut = out;
                    }
                    if (maxZ > z + 1.) {
                        double[] out = cut == a ? b : a;
                        n = clip(cut, n, out, 2, z + 1., false);
                        cut = out;
                    }
                    if (!hasArea(cut, n)) continue;
                    var surface = surface(x, z);
                    if (!Double.isFinite(surface.height())) continue;
                    double bottom = Double.POSITIVE_INFINITY;
                    for (int i = 1; i < n * 3; i += 3) bottom = Math.min(bottom, cut[i]);
                    double current = bottom - surface.height();
                    if (current < gap) { gap = current; fluid = surface.fluid(); }
                    else if (current == gap) fluid &= surface.fluid();
                }
        }

        private static int slot(long key, int mask) {
            key = (key ^ (key >>> 33)) * 0xff51afd7ed558ccdl;
            key = (key ^ (key >>> 33)) * 0xc4ceb9fe1a85ec53l;
            return (int) (key ^ (key >>> 33)) & mask;
        }

        private HullLanding.Surface surface(int x, int z) {
            long key = ((long) x << 32) ^ (z & 0xffffffffL);
            int mask = keys.length - 1, i = slot(key, mask);
            while (surfaces[i] != null) {
                if (keys[i] == key) return surfaces[i];
                i = (i + 1) & mask;
            }
            var value = column.surface(x, z);
            keys[i] = key;
            surfaces[i] = value;
            if (++size >= keys.length / 2) {
                long[] oldKeys = keys;
                var oldSurfaces = surfaces;
                keys = new long[oldKeys.length * 2];
                surfaces = new HullLanding.Surface[keys.length];
                mask = keys.length - 1;
                for (int j = 0; j < oldKeys.length; j++) {
                    if (oldSurfaces[j] == null) continue;
                    i = slot(oldKeys[j], mask);
                    while (surfaces[i] != null) i = (i + 1) & mask;
                    keys[i] = oldKeys[j];
                    surfaces[i] = oldSurfaces[j];
                }
            }
            return value;
        }
    }

    // Identical Sutherland-Hodgman order and arithmetic to the frozen test reference.
    private static int clip(double[] in, int n, double[] out, int axis, double edge, boolean greater) {
        if (n == 0) return 0;
        int count = 0, a = (n - 1) * 3;
        double da = (in[a + axis] - edge) * (greater ? 1 : -1);
        for (int b = 0; b < n * 3; b += 3) {
            double db = (in[b + axis] - edge) * (greater ? 1 : -1);
            if ((da >= 0) != (db >= 0)) {
                double t = da / (da - db);
                // Vector3d.lerp uses JOML's configurable fma, not necessarily java.lang.Math.fma.
                out[count++] = org.joml.Math.fma(in[b] - in[a], t, in[a]);
                out[count++] = org.joml.Math.fma(in[b + 1] - in[a + 1], t, in[a + 1]);
                out[count++] = org.joml.Math.fma(in[b + 2] - in[a + 2], t, in[a + 2]);
            }
            if (db >= 0) {
                out[count++] = in[b]; out[count++] = in[b + 1]; out[count++] = in[b + 2];
            }
            a = b; da = db;
        }
        return count / 3;
    }

    private static boolean hasArea(double[] polygon, int n) {
        if (n < 3) return false;
        double ox = polygon[0], oz = polygon[2], twiceArea = 0;
        int a = (n - 1) * 3;
        for (int b = 0; b < n * 3; b += 3) {
            twiceArea += (polygon[a] - ox) * (polygon[b + 2] - oz)
                    - (polygon[a + 2] - oz) * (polygon[b] - ox);
            a = b;
        }
        return Math.abs(twiceArea) > 1e-10;
    }

    private HullBottom() {}
}
