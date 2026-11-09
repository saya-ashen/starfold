package dev.starfold.worldgen.lod;

import dev.starfold.worldgen.surface.SurfaceFunction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Immutable non-air display runs; coordinates are absolute block edges, top exclusive. */
public record VoxelColumn(List<Run> runs) {
    public static final int MAX_RUNS = 32;
    public record Run(int bottom, int top, BlockState block) {
        public Run {
            if (bottom >= top || bottom < -4096 || top > 4096 || block == null || block.isAir())
                throw new IllegalArgumentException("Voxel run");
        }
    }
    public VoxelColumn {
        runs = List.copyOf(runs);
        if (runs.size() > MAX_RUNS) throw new IllegalArgumentException("Voxel run cap");
        int end = -4096;
        for (var r : runs) {
            if (r.bottom() < end) throw new IllegalArgumentException("Overlapping voxel runs");
            end = r.top();
        }
    }
    public BlockState state(int y) {
        for (var r : runs) {
            if (y < r.bottom()) break;
            if (y < r.top()) return r.block();
        }
        return Blocks.AIR.defaultBlockState();
    }

    /** Use the deepest exposed cliff side, not an averaged surface or a new terrain sampler. */
    public static VoxelColumn visible(SurfaceFunction function,
            dev.starfold.geometry.CubeSurfaceTopology.Face face, int x, int z) {
        var terrainPlan = function.terrain().terrainPlan();
        var canonical = function.terrain().shape().topology().canonical(
            new dev.starfold.geometry.CubeSurfaceTopology.Cell(face, x, 0, z)).cell();
        if (terrainPlan.localContent(canonical.face(), canonical.u(), canonical.v())) {
            var states = function.blocks(face, x, z);
            var exact = new ArrayList<Run>();
            int floor = function.terrain().floor();
            for (int y = 0; y < states.length; y++)
                if (!states[y].isAir()) addShown(exact, floor + y, floor + y + 1, states[y]);
            return compose(exact);
        }
        var c = function.visible(face, x, z);
        var source = new ArrayList<Run>();
        var side = c.sides().stream().min(Comparator.comparingInt(SurfaceFunction.Side::bottom)).orElseThrow();
        int floor = function.terrain().floor();
        int low = Math.min(c.top() + 1, side.bottom());
        var terrain=function.terrain();
        int ground=terrain.groundY(face,x,z);
        boolean submerged=!c.block().getFluidState().isEmpty() && ground<c.top();
        if(submerged) {
            var bed=terrain.visibleBand(face,x,z,ground,ground);
            source.add(new Run(floor,ground+1,bed.state(ground)));
            source.add(new Run(ground+1,c.top()+1,c.block()));
        } else if (low > floor) source.add(new Run(floor, low, c.block()));
        if(!submerged) for (var r : side.runs()) addShown(source, r.bottom(), r.top() + 1, r.block());
        // A local maximum with no exposed side still needs its cap.
        if (source.isEmpty() || source.getLast().top() <= c.top())
            addShown(source, c.top(), c.top() + 1, c.block());
        for (var r : c.objects()) addShown(source, r.bottom(), r.top() + 1, r.block());
        return compose(source);
    }
    private static void addShown(List<Run> out, int bottom, int top, BlockState raw) {
        var state = VoxelMesher.plant(raw) ? raw : VoxelMesher.shown(raw);
        if (!state.isAir()) out.add(new Run(bottom, top, state));
    }
    public static VoxelColumn compose(List<Run> source) {
        var edges = new TreeSet<Integer>();
        for (var r : source) { edges.add(r.bottom()); edges.add(r.top()); }
        var out = new ArrayList<Run>();
        Integer previous = null;
        for (int edge : edges) {
            if (previous != null) {
                BlockState state = null;
                for (var r : source) if (r.bottom() <= previous && r.top() >= edge) state = r.block();
                if (state != null) append(out, previous, edge, state);
            }
            previous = edge;
        }
        return bounded(out);
    }
    private static void append(List<Run> out, int bottom, int top, BlockState block) {
        if (!out.isEmpty() && out.getLast().top() == bottom && out.getLast().block() == block) {
            var last = out.removeLast();
            bottom = last.bottom();
        }
        out.add(new Run(bottom, top, block));
    }
    /** Discard deepest material boundaries first; never fill an air gap to meet the cap. */
    private static VoxelColumn bounded(ArrayList<Run> out) {
        while (out.size() > MAX_RUNS) {
            int buried = -1;
            for (int i = 0; i + 1 < out.size(); i++)
                if (out.get(i).top() == out.get(i + 1).bottom()) { buried = i; break; }
            if (buried < 0) out.removeFirst();
            else {
                var a = out.remove(buried); var b = out.get(buried);
                out.set(buried, new Run(a.bottom(), b.top(), b.block()));
            }
        }
        return new VoxelColumn(out);
    }
    /** Four child columns with coarse material bands clipped to occupied child heights.
     * Prefer the highest exposed child cap, then exposed side area and material coverage. */
    public static VoxelColumn reduce(List<VoxelColumn> children, int step) {
        if (children.isEmpty() || children.size() > 4 || step < 2 || step > 64 || Integer.bitCount(step) != 1)
            throw new IllegalArgumentException("Voxel mip children/step");
        // Never snap a shallow seabed into the same vertical cell as the water surface.
        int vertical=children.stream().flatMap(c->c.runs().stream()).anyMatch(r->!r.block().getFluidState().isEmpty()) ? 1 : step;
        var edges = new TreeSet<Integer>();
        for (var child : children) for (var r : child.runs()) {
            edges.add(Math.floorDiv(r.bottom(), vertical) * vertical);
            edges.add(-Math.floorDiv(-r.top(), vertical) * vertical);
        }
        var out = new ArrayList<Run>();
        Integer previous = null;
        for (int end : edges) {
            if (previous == null) { previous = end; continue; }
            int y = previous; previous = end;
            Run best = null; int score = Integer.MIN_VALUE, bestCoverage = -1;
            int bottom = end, top = y;
            for (var child : children) for (var r : child.runs()) {
                if (r.bottom() >= end || r.top() <= y) continue;
                bottom = Math.min(bottom, Math.max(y, r.bottom()));
                top = Math.max(top, Math.min(end, r.top()));
                int exposed = 0, coverage = 0;
                for (var other : children) {
                    var state = other.state(Math.min(end, r.top()) - 1);
                    if (state.isAir()) exposed++;
                    if (state == r.block()) coverage++;
                }
                // A flower must not replace the solid ground in a shared coarse height band.
                int candidate = (r.top() <= end ? 10000 + r.top() : 0) + exposed * step
                        - (VoxelMesher.plant(r.block()) ? 100000 : 0);
                // Equal-height flecks must not win a whole tile just because their registry ID is lower.
                if (candidate > score || candidate == score && (coverage > bestCoverage
                        || coverage == bestCoverage && best != null
                        && Block.getId(r.block()) < Block.getId(best.block()))) {
                    best = r; score = candidate; bestCoverage = coverage;
                }
            }
            // Grid rounding may merge sub-cell gaps, but must never raise the silhouette.
            if (best != null) append(out, bottom, top, best.block());
        }
        return bounded(out);
    }
    public long[] packed() {
        var out = new long[runs.size()];
        for (int i = 0; i < out.length; i++) {
            var r = runs.get(i);
            out[i] = ((long)Block.getId(r.block()) << 32) | ((long)(r.bottom() & 65535) << 16) | (r.top() & 65535);
        }
        return out;
    }
    public static VoxelColumn unpack(long[] data, int from, int to) {
        var out = new ArrayList<Run>(to - from);
        for (int i = from; i < to; i++) {
            long p = data[i];
            out.add(new Run((short)(p >>> 16), (short)p, Block.stateById((int)(p >>> 32))));
        }
        return new VoxelColumn(out);
    }
}
