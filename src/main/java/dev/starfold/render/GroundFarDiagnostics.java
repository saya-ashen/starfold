package dev.starfold.render;

import dev.starfold.api.FarViewDiagnostics;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.worldgen.lod.VoxelColumn;
import dev.starfold.worldgen.lod.VoxelColumns;
import dev.starfold.worldgen.lod.VoxelFluid;
import dev.starfold.worldgen.lod.VoxelMesher;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.*;

/** Read-only CPU evidence. No cache lookups, chunk loads, shader evaluation or GL reads. */
public final class GroundFarDiagnostics {
    public static final int MAX_SAMPLES = 64, MAX_RAW_RUNS = 1024;
    private static final java.util.concurrent.atomic.AtomicLong builds =
            new java.util.concurrent.atomic.AtomicLong();

    public enum Origin {
        ORIGINAL_VOXELS,
        DISPLAY_RUNS,
        SIMPLIFIED_RUNS
    }

    public record RunSnapshot(int bottom, int top, String block, String fluid, boolean air) {}

    public record ColumnSnapshot(
            Origin origin, int face, int x, int z, long revision, List<RunSnapshot> runs) {
        public ColumnSnapshot {
            runs = List.copyOf(runs);
        }
    }

    public record VoxelSnapshot(
            Origin origin,
            int face,
            int x,
            int z,
            long revision,
            int totalRuns,
            List<ColumnSnapshot> columns,
            boolean truncated) {
        public VoxelSnapshot {
            columns = List.copyOf(columns);
        }
    }

    public record FluidFinding(
            Origin origin,
            String material,
            String neighborKind,
            int axis,
            double bottom,
            double top,
            double plane,
            double lateralLow,
            double lateralHigh,
            String reason) {}

    public record FluidSideReport(List<FluidFinding> findings, int omitted) {
        public FluidSideReport {
            findings = List.copyOf(findings);
        }

        public boolean truncated() {
            return omitted > 0;
        }
    }

    private GroundFarDiagnostics() {}

    /** Only supplied immutable runs are copied; this method never requests missing terrain. */
    public static ColumnSnapshot snapshotColumn(
            VoxelColumn captured, Origin origin, int face, int x, int z, long revision) {
        Objects.requireNonNull(captured);
        runOrigin(origin);
        position(face, x, z);
        var runs = new ArrayList<RunSnapshot>();
        for (var r : captured.runs()) runs.add(run(r.bottom(), r.top(), r.block()));
        return new ColumnSnapshot(origin, face, x, z, revision, runs);
    }

    /** Snapshot one captured 16x16 voxel tile, including explicit air and an honest prefix cap. */
    public static VoxelSnapshot snapshotVoxels(
            VoxelColumns captured, int face, int x, int z, long revision) {
        Objects.requireNonNull(captured);
        position(face, x, z);
        if ((x & 15) != 0 || (z & 15) != 0) throw new IllegalArgumentException("Tile origin");
        int[] offsets = captured.offsets(), ends = captured.ends(), states = captured.states();
        var columns = new ArrayList<ColumnSnapshot>();
        int retained = 0;
        for (int c = 0; c < 256 && retained < MAX_RAW_RUNS; c++) {
            var runs = new ArrayList<RunSnapshot>();
            int low = captured.floor();
            for (int i = offsets[c]; i < offsets[c + 1] && retained < MAX_RAW_RUNS; i++) {
                runs.add(run(low, ends[i], Block.BLOCK_STATE_REGISTRY.byId(states[i])));
                low = ends[i];
                retained++;
            }
            columns.add(
                    new ColumnSnapshot(
                            Origin.ORIGINAL_VOXELS, face, x + c % 16, z + c / 16, revision, runs));
        }
        return new VoxelSnapshot(
                Origin.ORIGINAL_VOXELS,
                face,
                x,
                z,
                revision,
                ends.length,
                columns,
                retained < ends.length);
    }

    private static RunSnapshot run(int bottom, int top, BlockState state) {
        return new RunSnapshot(
                bottom, top, state.toString(), state.getFluidState().toString(), state.isAir());
    }

    private static void position(int face, int x, int z) {
        if (face < 0 || face > 5 || x < 0 || x >= 2048 || z < 0 || z >= 2048)
            throw new IllegalArgumentException("Chart position");
    }

    private static void runOrigin(Origin origin) {
        if (Objects.requireNonNull(origin) == Origin.ORIGINAL_VOXELS)
            throw new IllegalArgumentException("Display/simplified runs are not original voxels");
    }

    /** Compatibility view of the bounded findings. Use fluidSideReport for truncation evidence. */
    public static List<FluidFinding> missingFluidSides(
            VoxelColumn center,
            VoxelColumn neighbor,
            Origin origin,
            int axis,
            double plane,
            double lateralLow,
            double lateralHigh,
            List<VoxelMesher.Quad> generated) {
        return fluidSideReport(
                        center, neighbor, origin, axis, plane, lateralLow, lateralHigh, generated)
                .findings();
    }

    /**
     * Compare a captured run-column edge with raw boxes output (absolute Y, axes 0/1/4/5).
     * Occupancy is shared with boxes; coverage is checked independently in lateral stripes. Only a
     * free fluid surface is lowered, including wet plants and different same-family states.
     * Findings identify CPU geometry gaps, never GPU pixels. The retained prefix stays bounded.
     */
    public static FluidSideReport fluidSideReport(
            VoxelColumn center,
            VoxelColumn neighbor,
            Origin origin,
            int axis,
            double plane,
            double lateralLow,
            double lateralHigh,
            List<VoxelMesher.Quad> generated) {
        Objects.requireNonNull(center);
        Objects.requireNonNull(neighbor);
        Objects.requireNonNull(generated);
        runOrigin(origin);
        if ((axis != 0 && axis != 1 && axis != 4 && axis != 5)
                || !Double.isFinite(plane)
                || !Double.isFinite(lateralLow)
                || !Double.isFinite(lateralHigh)
                || lateralLow >= lateralHigh) throw new IllegalArgumentException("Fluid edge");
        var findings = new ArrayList<FluidFinding>();
        int omitted = 0;
        for (var r : center.runs()) {
            var fluid = r.block().getFluidState();
            if (fluid.isEmpty()) continue;
            double top = VoxelFluid.top(center, r);
            var material = fluid.createLegacyBlock();
            var covers = new ArrayList<double[]>();
            var edges = new TreeSet<Double>();
            edges.add(lateralLow);
            edges.add(lateralHigh);
            boolean same = false, lower = false;
            for (var n : neighbor.runs()) {
                if (VoxelFluid.same(fluid, n.block().getFluidState())) {
                    double neighborTop = VoxelFluid.top(neighbor, n);
                    same |= Math.abs(neighborTop - top) < 1e-6;
                    lower |= neighborTop < top && neighborTop > r.bottom();
                }
            }
            String kind = same ? "SAME_HEIGHT_FLUID" : lower ? "LOWER_FLUID" : "AIR_OR_SOLID";
            for (var cover :
                    VoxelFluid.occluders(
                            neighbor, fluid, axis, lateralLow, lateralHigh, r.bottom(), top)) {
                covers.add(new double[] {cover.bottom(), cover.top(), cover.low(), cover.high()});
                edges.add(cover.low());
                edges.add(cover.high());
            }
            for (var q : generated) {
                if (!q.volume()
                        || q.axis() != axis
                        || !q.material().equals(material)
                        || !outwardSide(q)) continue;
                double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
                double a = Double.POSITIVE_INFINITY, b = Double.NEGATIVE_INFINITY;
                boolean onPlane = true;
                for (var p : q.points()) {
                    onPlane &= Math.abs((axis < 2 ? p.x() : p.z()) - plane) < 1e-6;
                    double lateral = axis < 2 ? p.z() : p.x();
                    a = Math.min(a, lateral);
                    b = Math.max(b, lateral);
                    low = Math.min(low, p.y());
                    high = Math.max(high, p.y());
                }
                if (onPlane && b > lateralLow && a < lateralHigh) {
                    a = Math.max(a, lateralLow);
                    b = Math.min(b, lateralHigh);
                    covers.add(new double[] {low, high, a, b});
                    edges.add(a);
                    edges.add(b);
                }
            }
            Double prior = null;
            for (double edge : edges) {
                if (prior != null && edge > prior) {
                    var gaps = new ArrayList<double[]>();
                    gaps.add(new double[] {r.bottom(), top});
                    for (var cover : covers)
                        if (cover[2] <= prior && cover[3] >= edge)
                            subtract(gaps, cover[0], cover[1]);
                    gaps.sort(Comparator.comparingDouble(gap -> gap[0]));
                    for (var gap : gaps) {
                        if (findings.size() == MAX_SAMPLES) {
                            omitted++;
                        } else {
                            findings.add(
                                    new FluidFinding(
                                            origin,
                                            material.toString(),
                                            kind,
                                            axis,
                                            gap[0],
                                            gap[1],
                                            plane,
                                            prior,
                                            edge,
                                            "EXPOSED_FLUID_SIDE_MISSING_IN_CPU_QUADS"));
                        }
                    }
                }
                prior = edge;
            }
        }
        return new FluidSideReport(findings, omitted);
    }

    private static boolean outwardSide(VoxelMesher.Quad quad) {
        if (quad.points().size() != 4) return false;
        for (int i = 0; i < 4; i++) {
            var a = quad.points().get(i);
            var b = quad.points().get((i + 1) % 4);
            var c = quad.points().get((i + 2) % 4);
            double normal =
                    quad.axis() < 2
                            ? (b.y() - a.y()) * (c.z() - a.z()) - (b.z() - a.z()) * (c.y() - a.y())
                            : (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
            if (quad.axis() == 0 || quad.axis() == 4) normal = -normal;
            if (!(normal > 1e-12)) return false;
        }
        return true;
    }

    private static void subtract(List<double[]> intervals, double low, double high) {
        for (int i = intervals.size() - 1; i >= 0; i--) {
            var a = intervals.get(i);
            if (high <= a[0] + 1e-6 || low >= a[1] - 1e-6) continue;
            intervals.remove(i);
            if (low > a[0] + 1e-6) intervals.add(new double[] {a[0], low});
            if (high < a[1] - 1e-6) intervals.add(new double[] {high, a[1]});
        }
    }

    static Collector capture(int planet) {
        long epoch = FarViewDiagnostics.epoch(planet);
        var watch = epoch == 0 ? null : FarViewDiagnostics.currentWatch(epoch);
        return watch == null ? null : new Collector(watch);
    }

    static Collector captureCurrent() {
        if (!FarViewDiagnostics.active()) return null;
        var snapshot = FarViewDiagnostics.snapshot();
        return capture(snapshot.rendererPlanet());
    }

    /** One bounded prefix reservoir per intersected tile for this build/stage. */
    static final class Collector {
        private final FarViewDiagnostics.Watch watch;
        private final long build = builds.incrementAndGet(), capturedNanos = System.nanoTime();
        private final Tile[] tiles = new Tile[25];
        private boolean closed;

        Collector(FarViewDiagnostics.Watch watch) {
            this.watch = watch;
        }

        private Tile tile(int x, int z) {
            int index = ((z - watch.z()) / 16 + 2) * 5 + (x - watch.x()) / 16 + 2;
            var t = tiles[index];
            if (t == null) tiles[index] = t = new Tile(x, z);
            return t;
        }

        private boolean face(int face) {
            return !closed && face == watch.face();
        }

        void encoded(
                int face,
                RenderType layer,
                BlockState state,
                List<Point> points,
                int a,
                int b,
                int c,
                int d,
                boolean volume,
                int tag,
                int rgb,
                int albedo,
                boolean cut,
                String kind) {
            if (!face(face)) return;
            int[] indices = {a, b, c, d};
            double minX = Double.POSITIVE_INFINITY, minZ = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
            for (int i : indices) {
                var p = points.get(i);
                minX = Math.min(minX, p.x());
                maxX = Math.max(maxX, p.x());
                minZ = Math.min(minZ, p.z());
                maxZ = Math.max(maxZ, p.z());
            }
            for (int z = Math.max(0, watch.z() - watch.radius() * 16);
                    z <= Math.min(2032, watch.z() + watch.radius() * 16);
                    z += 16)
                for (int x = Math.max(0, watch.x() - watch.radius() * 16);
                        x <= Math.min(2032, watch.x() + watch.radius() * 16);
                        x += 16) {
                    if (!intersects(minX, minZ, maxX, maxZ, x, z)) continue;
                    var t = tile(x, z);
                    long index = t.total++;
                    if (t.samples.size() == MAX_SAMPLES) continue;
                    var sample = new LinkedHashMap<String, Object>();
                    sample.put("index", index);
                    sample.put("block", state.toString());
                    sample.put("fluid", state.getFluidState().toString());
                    sample.put("hasFluid", !state.getFluidState().isEmpty());
                    sample.put("kind", kind);
                    sample.put("cut", cut);
                    sample.put("volume", volume);
                    sample.put("layer", layer.toString());
                    sample.put("tag", tag & 255);
                    sample.put("rgb", rgb & 0xffffff);
                    sample.put("albedo", albedo & 0xffffff);
                    sample.put("packedLight", -1);
                    var p = new ArrayList<Object>(4);
                    for (int i : indices) {
                        var v = points.get(i);
                        p.add(List.of((float) v.x(), (float) v.y(), (float) v.z()));
                    }
                    sample.put("points", List.copyOf(p));
                    var normal =
                            points.get(b)
                                    .sub(points.get(a))
                                    .cross(points.get(c).sub(points.get(a)));
                    sample.put("normal", List.of(normal.x(), normal.y(), normal.z()));
                    t.samples.add(Collections.unmodifiableMap(sample));
                }
        }

        void packed(
                int face,
                RenderType layer,
                long lodKey,
                ByteBuffer source,
                int start,
                long packed,
                BlockPos lightPosition,
                int light) {
            if (!face(face)) return;
            double minX = Double.POSITIVE_INFINITY, minZ = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxZ = maxX;
            for (int i = 0; i < 4; i++) {
                double x = source.getFloat(start + i * 32), z = source.getFloat(start + i * 32 + 8);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
            for (int z = Math.max(0, watch.z() - watch.radius() * 16);
                    z <= Math.min(2032, watch.z() + watch.radius() * 16);
                    z += 16)
                for (int x = Math.max(0, watch.x() - watch.radius() * 16);
                        x <= Math.min(2032, watch.x() + watch.radius() * 16);
                        x += 16) {
                    if (!intersects(minX, minZ, maxX, maxZ, x, z)) continue;
                    var t = tile(x, z);
                    long index = t.total++;
                    if (t.samples.size() == MAX_SAMPLES) continue;
                    var vertices = new ArrayList<Object>(4);
                    for (int i = 0; i < 4; i++) {
                        int p = start + i * 32;
                        long v = packed + i * 12L;
                        int materialLow = unsigned(v + 6), tagAndHigh = unsigned(v + 8);
                        var vertex = new LinkedHashMap<String, Object>();
                        vertex.put(
                                "point",
                                List.of(
                                        source.getFloat(p),
                                        source.getFloat(p + 4),
                                        source.getFloat(p + 8)));
                        vertex.put(
                                "packedPosition",
                                List.of(unsigned(v), unsigned(v + 2), unsigned(v + 4)));
                        vertex.put("materialId", materialLow | (tagAndHigh >>> 8) << 16);
                        vertex.put("tag", tagAndHigh & 255);
                        vertex.put("packedLight", unsigned(v + 10));
                        vertex.put("rgbBytes", source.getInt(p + 12) & 0xffffff);
                        vertex.put("uv", List.of(source.getFloat(p + 16), source.getFloat(p + 20)));
                        vertex.put("uv2Bytes", source.getInt(p + 24));
                        vertex.put("normalBytes", source.getInt(p + 28) & 0xffffff);
                        vertices.add(Collections.unmodifiableMap(vertex));
                    }
                    t.samples.add(
                            Map.of(
                                    "index",
                                    index,
                                    "layer",
                                    layer.toString(),
                                    "lodKey",
                                    lodKey,
                                    "blockFluidCutProvenance",
                                    "UNAVAILABLE_AT_PACKING",
                                    "lightPosition",
                                    List.of(
                                            lightPosition.getX(),
                                            lightPosition.getY(),
                                            lightPosition.getZ()),
                                    "lightPositionSpace",
                                    "CHART_STORAGE_FACE_STRIDE_4096_Y_PLUS_128",
                                    "sampledLight",
                                    light,
                                    "vertices",
                                    List.copyOf(vertices)));
                }
        }

        void close(String stage, boolean complete) {
            if (closed) return;
            closed = true;
            for (var t : tiles) {
                if (t == null) continue;
                var fields = new LinkedHashMap<String, Object>();
                fields.put("totalQuads", t.total);
                fields.put("sampledQuads", List.copyOf(t.samples));
                fields.put("truncatedQuads", t.total - t.samples.size());
                fields.put("complete", complete);
                fields.put("completionKnown", stage.equals("PACKED_CPU"));
                fields.put("evidence", "CPU_ONLY");
                fields.put("intersection", "CONSERVATIVE_XZ_BOUNDS");
                fields.put("pointSpace", "CHART_XZ_RENDER_Y");
                fields.put("shaderRejection", "UNOBSERVED");
                fields.put("gpuOutput", "UNOBSERVED");
                fields.put("build", build);
                fields.put("capturedNanos", capturedNanos);
                FarViewDiagnostics.record(
                        watch.epoch(), watch.planet(), watch.face(), t.x, t.z, stage, fields);
            }
        }
    }

    private static boolean intersects(
            double minX, double minZ, double maxX, double maxZ, int x, int z) {
        // Inclusive boundaries retain seam walls on both incident selected tiles.
        return maxX >= x && minX <= x + 16 && maxZ >= z && minZ <= z + 16;
    }

    private static int unsigned(long address) {
        return Short.toUnsignedInt(MemoryUtil.memGetShort(address));
    }

    private static final class Tile {
        final int x, z;
        long total;
        final List<Map<String, Object>> samples = new ArrayList<>();

        Tile(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }
}
