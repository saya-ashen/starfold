package dev.starfold.render;

import dev.starfold.api.FarViewDiagnostics;
import dev.starfold.geometry.CubeSurfaceTopology.Face;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded evidence from supplied inputs only; never requests terrain or cache entries. */
public final class GroundFarInputDiagnostics {
    public static final int MAX_COLUMNS = 16, MAX_RUNS = 64;

    private GroundFarInputDiagnostics() {}

    private static FarViewDiagnostics.Watch watch(long epoch, int planet, int face) {
        if (epoch == 0) return null;
        var w = FarViewDiagnostics.currentWatch(epoch);
        return w != null && w.planet() == planet && w.face() == face ? w : null;
    }

    private static boolean intersects(FarViewDiagnostics.Watch w, int x, int z, int step) {
        int minX = Math.max(0, w.x() - w.radius() * 16);
        int minZ = Math.max(0, w.z() - w.radius() * 16);
        int maxX = Math.min(2048, w.x() + w.radius() * 16 + 16);
        int maxZ = Math.min(2048, w.z() + w.radius() * 16 + 16);
        return step > 0 && x < maxX && z < maxZ && (long) x + step > minX && (long) z + step > minZ;
    }

    /** Copy only the optional, already captured original occupancy, including explicit air. */
    public static void original(
            long epoch, int planet, int face, int x, int z, GroundSurface image) {
        var w = watch(epoch, planet, face);
        if (w == null
                || (x & 15) != 0
                || (z & 15) != 0
                || x < 0
                || z < 0
                || x >= 2048
                || z >= 2048
                || !intersects(w, x, z, 16)
                || image == null) return;
        var voxels = image.voxels();
        if (voxels == null) return;
        var offsets = voxels.offsets();
        var ends = voxels.ends();
        var states = voxels.states();
        var columns = new ArrayList<Map<String, Object>>();
        int retained = 0;
        for (int c = 0; c < MAX_COLUMNS; c++) {
            var runs = new ArrayList<Map<String, Object>>();
            int bottom = voxels.floor();
            for (int i = offsets[c]; i < offsets[c + 1] && retained < MAX_RUNS; i++) {
                runs.add(run(bottom, ends[i], Block.BLOCK_STATE_REGISTRY.byId(states[i])));
                bottom = ends[i];
                retained++;
            }
            columns.add(column(x + c % 16, z + c / 16, 1, offsets[c + 1] - offsets[c], runs));
            if (retained == MAX_RUNS) break;
        }
        var fields = fields("ORIGINAL_VOXELS", 256, ends.length, columns, retained);
        fields.put("revision", image.revision());
        fields.put("floor", voxels.floor());
        fields.put("ceiling", voxels.ceiling());
        FarViewDiagnostics.record(epoch, planet, face, x, z, "ORIGINAL_VOXELS", fields);
    }

    /** Pass the returned field to the existing build, then call complete in its finally block. */
    public static GroundFarMesh.Field field(
            long epoch,
            int planet,
            int face,
            int x,
            int z,
            int step,
            GroundFarMesh.Field delegate) {
        return field(epoch, planet, face, x, z, step, Math.max(16, step), delegate);
    }

    /** Chart builds cover a full patch even when the watched tile is far from its origin. */
    public static GroundFarMesh.Field field(
            long epoch,
            int planet,
            int face,
            int x,
            int z,
            int step,
            int width,
            GroundFarMesh.Field delegate) {
        var w = watch(epoch, planet, face);
        if (w == null || !intersects(w, x, z, width)) return delegate;
        return new Capture(w, x, z, step, delegate);
    }

    /** Flush at most once, including after cancellation; stale epochs are discarded. */
    public static void complete(GroundFarMesh.Field field) {
        if (field instanceof Capture capture) capture.close();
    }

    private static Map<String, Object> run(int bottom, int top, BlockState state) {
        return Map.of(
                "bottom",
                bottom,
                "top",
                top,
                "block",
                state.toString(),
                "fluid",
                BuiltInRegistries.FLUID.getKey(state.getFluidState().getType()).toString(),
                "air",
                state.isAir());
    }

    private static Map<String, Object> column(
            int x, int z, int step, int total, List<Map<String, Object>> runs) {
        return Map.of(
                "x",
                x,
                "z",
                z,
                "step",
                step,
                "totalRuns",
                total,
                "sampledRunCount",
                runs.size(),
                "truncatedRuns",
                total - runs.size(),
                "runs",
                runs);
    }

    private static LinkedHashMap<String, Object> fields(
            String origin,
            long totalColumns,
            long totalRuns,
            List<Map<String, Object>> columns,
            int sampledRuns) {
        var result = new LinkedHashMap<String, Object>();
        result.put("origin", origin);
        result.put("samplePolicy", "PREFIX_OF_OBSERVED_COLUMNS_AND_RUNS");
        result.put("heightSpace", "ABSOLUTE_BLOCK_Y_TOP_EXCLUSIVE");
        result.put("totalColumns", totalColumns);
        result.put("sampledColumnCount", columns.size());
        result.put("truncatedColumns", totalColumns - columns.size());
        result.put("totalRuns", totalRuns);
        result.put("sampledRunCount", sampledRuns);
        result.put("truncatedRuns", totalRuns - sampledRuns);
        result.put("truncated", totalColumns > columns.size() || totalRuns > sampledRuns);
        result.put("columns", columns);
        return result;
    }

    /** Worker-confined; observes calls, not the full world or unsampled columns. */
    private static final class Capture implements GroundFarMesh.Field, AutoCloseable {
        private final FarViewDiagnostics.Watch watch;
        private final int x, z, step;
        private final GroundFarMesh.Field delegate;
        private final List<Map<String, Object>> columns = new ArrayList<>();
        private long totalColumns, totalRuns;
        private int sampledRuns;
        private boolean closed;

        Capture(
                FarViewDiagnostics.Watch watch,
                int x,
                int z,
                int step,
                GroundFarMesh.Field delegate) {
            this.watch = watch;
            this.x = x;
            this.z = z;
            this.step = step;
            this.delegate = delegate;
        }

        @Override
        public GroundFarMesh.Surface cell(Face face, int x, int z, int step) {
            var surface = delegate.cell(face, x, z, step);
            if (closed
                    || face.ordinal() != watch.face()
                    || FarViewDiagnostics.currentWatch(watch.epoch()) == null
                    || !intersects(watch, x, z, step)
                    || surface == null) return surface;
            var actual = surface.column();
            totalColumns++;
            totalRuns += actual.runs().size();
            if (columns.size() < MAX_COLUMNS && sampledRuns < MAX_RUNS) {
                var runs = new ArrayList<Map<String, Object>>();
                for (var r : actual.runs()) {
                    if (sampledRuns == MAX_RUNS) break;
                    runs.add(run(r.bottom(), r.top(), r.block()));
                    sampledRuns++;
                }
                columns.add(column(x, z, step, actual.runs().size(), runs));
            }
            return surface;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (FarViewDiagnostics.currentWatch(watch.epoch()) == null) return;
            var fields =
                    fields(
                            "DISPLAY_OR_SIMPLIFIED_RUNS",
                            totalColumns,
                            totalRuns,
                            columns,
                            sampledRuns);
            fields.put("step", step);
            fields.put("scope", "OBSERVED_DELEGATE_CALLS_INTERSECTING_WATCH_ONLY");
            fields.put("revision", "UNAVAILABLE_AT_FIELD");
            fields.put("buildOrigin", List.of(x, z));
            fields.put("completion", "FLUSHED_AFTER_BUILD_OR_CANCEL");
            int tileX =
                    Math.max(
                            Math.max(0, watch.x() - watch.radius() * 16),
                            Math.min(x & ~15, Math.min(2032, watch.x() + watch.radius() * 16)));
            int tileZ =
                    Math.max(
                            Math.max(0, watch.z() - watch.radius() * 16),
                            Math.min(z & ~15, Math.min(2032, watch.z() + watch.radius() * 16)));
            FarViewDiagnostics.record(
                    watch.epoch(),
                    watch.planet(),
                    watch.face(),
                    tileX,
                    tileZ,
                    "DISPLAY_OR_SIMPLIFIED_RUNS",
                    fields);
        }
    }
}
