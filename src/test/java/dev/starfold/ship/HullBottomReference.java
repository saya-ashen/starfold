package dev.starfold.ship;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.UnaryOperator;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;

/** Frozen 1e4eb709 geometry oracle. Intentionally allocation-heavy; test source only. */
final class HullBottomReference {
    /** Pure geometry shared by client/server. Transform is the current logical pose, never render bounds.
     * Unknown columns are skipped; solid wins an exact minimum-gap tie. No penetration clamp. */
    static HullLanding.Ground measure(
            List<AABB> shapes, UnaryOperator<Vector3d> transform, HullLanding.SurfaceColumn column) {
        var faces = new ArrayList<List<Vector3d>>(shapes.size());
        for (var box : shapes)
            faces.add(List.of(
                    transform.apply(new Vector3d(box.minX, box.minY, box.minZ)),
                    transform.apply(new Vector3d(box.maxX, box.minY, box.minZ)),
                    transform.apply(new Vector3d(box.maxX, box.minY, box.maxZ)),
                    transform.apply(new Vector3d(box.minX, box.minY, box.maxZ))));
        return measureFaces(faces, column);
    }

    static HullLanding.Ground measureFaces(
            List<List<Vector3d>> faces, HullLanding.SurfaceColumn column) {
        double gap = Double.POSITIVE_INFINITY;
        boolean fluid = false;
        // A terrain column can support several hull parts. Read it once per measurement, not per part.
        var terrain = new HashMap<Long, HullLanding.Surface>();
        for (var face : faces) {
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minZ = minX, maxZ = maxX;
            for (var v : face) {
                minX = Math.min(minX, v.x); maxX = Math.max(maxX, v.x);
                minZ = Math.min(minZ, v.z); maxZ = Math.max(maxZ, v.z);
            }
            for (int x = (int) Math.floor(minX); x <= Math.floor(Math.nextDown(maxX)); x++)
                for (int z = (int) Math.floor(minZ); z <= Math.floor(Math.nextDown(maxZ)); z++) {
                    var cut = clip(clip(clip(clip(face, 0, x, true), 0, x + 1., false),
                            2, z, true), 2, z + 1., false);
                    if (!hasArea(cut)) continue; // touching a grid edge is not covering its neighbour
                    long key = ((long) x << 32) ^ (z & 0xffffffffL);
                    var surface = terrain.get(key);
                    if (surface == null) {
                        surface = column.surface(x, z);
                        terrain.put(key, surface);
                    }
                    if (!Double.isFinite(surface.height())) continue;
                    double bottom = Double.POSITIVE_INFINITY;
                    for (var v : cut) bottom = Math.min(bottom, v.y);
                    double current = bottom - surface.height();
                    if (current < gap) { gap = current; fluid = surface.fluid(); }
                    else if (current == gap) fluid &= surface.fluid();
                }
        }
        return new HullLanding.Ground(Double.isFinite(gap) ? gap : Double.NaN, fluid);
    }

    // Sutherland-Hodgman clipping in XZ; interpolate Y on the actual tilted plane as well.
    private static List<Vector3d> clip(List<Vector3d> polygon, int axis, double edge, boolean greater) {
        var result = new ArrayList<Vector3d>(8);
        if (polygon.isEmpty()) return result;
        var a = polygon.getLast();
        double da = (a.get(axis) - edge) * (greater ? 1 : -1);
        for (var b : polygon) {
            double db = (b.get(axis) - edge) * (greater ? 1 : -1);
            if ((da >= 0) != (db >= 0)) result.add(new Vector3d(a).lerp(b, da / (da - db)));
            if (db >= 0) result.add(b);
            a = b; da = db;
        }
        return result;
    }

    private static boolean hasArea(List<Vector3d> polygon) {
        if (polygon.size() < 3) return false;
        // Translate before cross products: chart coordinates can be large.
        var origin = polygon.getFirst();
        double twiceArea = 0;
        var a = polygon.getLast();
        for (var b : polygon) {
            twiceArea += (a.x - origin.x) * (b.z - origin.z) - (a.z - origin.z) * (b.x - origin.x);
            a = b;
        }
        return Math.abs(twiceArea) > 1e-10;
    }

    private HullBottomReference() {}
}
