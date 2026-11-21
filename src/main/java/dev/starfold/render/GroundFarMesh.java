package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Cell;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.lod.*;
import dev.starfold.worldgen.lod.VoxelMesher.Quad;

import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Shared resident/far adapter to the voxel mesher. No interpolated terrain planes. */
final class GroundFarMesh {
    record Surface(
            double height,
            BlockState material,
            double floor,
            GroundSurface.Run canopy,
            GroundSurface.Run trunk,
            int rgb,
            dev.starfold.worldgen.surface.SurfaceFunction.Column detail,
            double canopyCoverage,
            VoxelColumn voxels) {
        Surface(
                double h,
                BlockState m,
                double f,
                GroundSurface.Run c,
                GroundSurface.Run t,
                int rgb,
                dev.starfold.worldgen.surface.SurfaceFunction.Column d,
                double coverage) {
            this(h, m, f, c, t, rgb, d, coverage, null);
        }

        Surface(
                double h,
                BlockState m,
                double f,
                GroundSurface.Run c,
                GroundSurface.Run t,
                int rgb,
                dev.starfold.worldgen.surface.SurfaceFunction.Column d) {
            this(h, m, f, c, t, rgb, d, 1);
        }

        Surface(
                double h,
                BlockState m,
                double f,
                GroundSurface.Run c,
                GroundSurface.Run t,
                int rgb) {
            this(h, m, f, c, t, rgb, null);
        }

        Surface(double h, BlockState m, double f, GroundSurface.Run c, GroundSurface.Run t) {
            this(h, m, f, c, t, -1);
        }

        Surface(double h, BlockState m, double f) {
            this(h, m, f, null, null);
        }

        Surface(double h, BlockState m) {
            this(h, m, h);
        }

        static Surface voxel(VoxelColumn column) {
            var runs = column.runs();
            var top = runs.isEmpty() ? null : runs.getLast();
            return new Surface(
                    top == null ? -128 : top.top() - 128.,
                    top == null
                            ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()
                            : top.block(),
                    runs.isEmpty() ? -128 : runs.getFirst().bottom() - 128.,
                    null,
                    null,
                    -1,
                    null,
                    0,
                    column);
        }

        VoxelColumn column() {
            if (voxels != null) return voxels;
            // Registry-only fixtures and the null-terrain startup shell use the same boxes.
            var runs = new ArrayList<VoxelColumn.Run>();
            int high = (int) Math.round(height + 128);
            if (!material.isAir())
                runs.add(
                        new VoxelColumn.Run(
                                Math.min(high - 1, (int) Math.floor(floor + 128) - 64),
                                high,
                                material));
            for (var r : new GroundSurface.Run[] {trunk, canopy})
                if (r != null && !r.material().isAir())
                    runs.add(new VoxelColumn.Run(r.bottom(), r.top() + 1, r.material()));
            return VoxelColumn.compose(runs);
        }
    }

    interface Field {
        Surface cell(Face face, int x, int z, int step);

        /** Null means transient/unknown inputs: do not cache finished geometry. Worker-only. */
        default String cacheInputs(int step) { return null; }

        /** Existing final data may be restored before reduced base columns finish loading. */
        default String cacheLookupInputs(int step) { return cacheInputs(step); }
    }

    static int level(double distance, int previous) {
        return level(distance, previous, 70, 720);
    }

    static int level(double distance, int previous, double fov, int height) {
        // Whole-planet fallback stays coarse until bounded resident tiles replace its pixels.
        return Math.max(16, VoxelLodPolicy.screenStep(distance, previous, fov, height));
    }

    /**
     * Remote planets have no resident detail layer: their visible colours need the screen-sized
     * mip.
     */
    static int remoteLevel(double distance, int previous, double fov, int height) {
        return Math.max(4, VoxelLodPolicy.screenStep(distance, previous, fov, height));
    }

    /**
     * Distance bands for a regional cover patch, following the DH-style logarithmic detail curve. A
     * 256-block patch is the regional unit here, so resident 16-block tiles own the near levels and
     * the patch starts at the first useful regional level, L4.
     *
     * <p>The distance unit is the medium-quality 64x64-column region (192 blocks). The next detail
     * level doubles the represented column width, with the first regional handoff at 384 blocks and
     * L4 at 768 blocks. This is deliberately separate from the resident screen-pixel policy and
     * from the L16 startup parent fallback.
     */
    static int regionalLevel(double distance, int previous, double fov, int height) {
        if (!Double.isFinite(distance) || distance < 0) return 64;
        double magnification = 70.0 / Math.clamp(fov, 1.0, 179.0);
        double effectiveDistance = distance / magnification;
        int level = 1;
        while (level < 64 && effectiveDistance >= 384.0 * level) level *= 2;
        if (previous > 0 && (previous & (previous - 1)) == 0) {
            double fineBoundary = 384.0 * previous;
            double coarseBoundary = 384.0 * previous * 2.0;
            if (effectiveDistance < fineBoundary * 1.1) level = Math.min(level, previous);
            if (effectiveDistance >= coarseBoundary / 1.1) level = Math.max(level, previous * 2);
        }
        return Math.clamp(Math.max(4, level), 4, 64);
    }

    /** First regional L4 handoff boundary for the medium DH distance curve. */
    static double residentHandoffDistance(double fov) {
        return 768.0 * Math.clamp(fov, 1.0, 179.0) / 70.0;
    }

    /** Resident tiles stop competing with the regional L4+ layer beyond this boundary. */
    static boolean residentNeeded(double distance, double fov, boolean edited) {
        return edited || Double.isFinite(distance) && distance < residentHandoffDistance(fov);
    }

    static double distance(PlanetShape shape, Point camera, int patch, double height) {
        return distance(
                shape,
                camera,
                Face.VALUES[GroundViewPolicy.face(patch)],
                GroundViewPolicy.x(patch),
                GroundViewPolicy.z(patch),
                256,
                height);
    }

    /** Closest point on the spherical face rectangle, including its edges. */
    static double distance(
            PlanetShape shape, Point camera, Face f, int x0, int z0, int width, double height) {
        double half = shape.half(), radius = shape.radius() + height, a = x0 - half, b = z0 - half;
        double endX = Math.min(a + width, half), endZ = Math.min(b + width, half);
        double n = camera.dot(f.normal),
                u = camera.dot(f.u),
                v = camera.dot(f.v),
                best = -Double.MAX_VALUE;
        if (n > 0) {
            double x = u * half / n, z = v * half / n;
            if (x >= a && x <= endX && z >= b && z <= endZ) best = camera.length();
        }
        for (double x : new double[] {a, endX}) {
            double denominator = n * half + u * x;
            double z =
                    denominator > 0
                            ? Math.clamp(v * (half * half + x * x) / denominator, b, endZ)
                            : b;
            best = Math.max(best, dot(n, u, v, half, x, z));
            best = Math.max(best, dot(n, u, v, half, x, endZ));
            best = Math.max(best, dot(n, u, v, half, x, b));
        }
        for (double z : new double[] {b, endZ}) {
            double denominator = n * half + v * z;
            double x =
                    denominator > 0
                            ? Math.clamp(u * (half * half + z * z) / denominator, a, endX)
                            : a;
            best = Math.max(best, dot(n, u, v, half, x, z));
        }
        return Math.sqrt(Math.max(0, camera.dot(camera) + radius * radius - 2 * radius * best));
    }

    private static double dot(double n, double u, double v, double half, double x, double z) {
        return (n * half + u * x + v * z) / Math.sqrt(half * half + x * x + z * z);
    }

    static List<Quad> build(PlanetShape shape, int patch, int step, Field field) {
        return build(
                shape,
                Face.VALUES[GroundViewPolicy.face(patch)],
                GroundViewPolicy.x(patch),
                GroundViewPolicy.z(patch),
                256,
                step,
                field);
    }

    static List<Quad> build(
            PlanetShape shape, Face face, int x, int z, int width, int step, Field field) {
        var cached = cached(shape, field);
        var quads =
                VoxelMesher.boxes(
                        x,
                        z,
                        Math.min(x + width, shape.size()),
                        Math.min(z + width, shape.size()),
                        step,
                        (u, v) -> cached.cell(face, u, v, step).column(),
                        step <= 16 ? 16 : 0,
                        Integer.MAX_VALUE);
        var out = new ArrayList<Quad>();
        for (var q : quads) {
            var shifted =
                    new Quad(
                            q.material(),
                            q.axis() / 2,
                            q.points().stream()
                                    .map(p -> new Point(p.x(), p.y() - 128, p.z()))
                                    .toList(),
                            q.volume(),
                            step >= 4 && q.material().is(net.minecraft.tags.BlockTags.LEAVES)
                                    ? -2
                                    : step >= 4
                                                    && q.axis() == 3
                                                    && q.material().getFluidState().isEmpty()
                                            ? -3
                                            : -1);
            out.add(shifted);
        }
        return List.copyOf(out);
    }

    static int side(Quad q) {
        if (!q.volume() && VoxelMesher.plant(q.material())) return q.axis() == 0 ? -2 : -3;
        if (q.axis() == 1) return -1;
        var p = q.points();
        var n = p.get(1).sub(p.getFirst()).cross(p.get(2).sub(p.getFirst()));
        return q.axis() == 0 ? (n.x() < 0 ? 0 : 1) : (n.z() < 0 ? 2 : 3);
    }

    static Field cached(PlanetShape shape, Field field) {
        var cells = new HashMap<Long, Surface>();
        return (f, x, z, s) -> {
            var c = shape.topology().canonical(new Cell(f, x, 0, z)).cell();
            int a = c.u() / s * s, b = c.v() / s * s;
            long key = ((c.face().ordinal() * 2048L + a) * 2048 + b) * 128 + s;
            return cells.computeIfAbsent(key, k -> field.cell(c.face(), a, b, s));
        };
    }

    static void wall(
            List<Quad> out,
            BlockState material,
            int x,
            int z,
            int right,
            int far,
            double high,
            double low,
            int side) {
        if (low >= high) return;
        int axis =
                switch (side) {
                    case 0 -> 0;
                    case 1 -> 1;
                    case 2 -> 4;
                    case 3 -> 5;
                    default -> throw new IllegalArgumentException("Wall side");
                };
        out.add(
                new Quad(
                        material, axis / 2, VoxelMesher.points(axis, x, low, z, right, high, far)));
    }

    private GroundFarMesh() {}
}
