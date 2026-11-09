package dev.starfold.render;

import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.lod.VoxelMesher;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;

/** One column image, recursively downscaled. Heights remain block Y until mesh lookup. */
final class GroundSurfaceMip {
    record Column(
            int y,
            BlockState material,
            GroundSurface.Run canopy,
            GroundSurface.Run trunk) {
        Column(int y, BlockState material) {
            this(y, material, null, null);
        }
        GroundFarMesh.Surface surface() { return new GroundFarMesh.Surface(y+1-128.,material,y+1-128.,canopy,trunk); }
    }

    private final int width, depth;
    private final int minimumY;
    private final int[][] heights, materials;
    private final int[][][] layers;

    GroundSurfaceMip(int width, int depth, BiFunction<Integer, Integer, Column> source) {
        this(width, depth, 1, source);
    }

    /** Sparse terrain is only a startup placeholder; authoritative mips still start at step 1. */
    static GroundSurfaceMip initial(
            int width, int depth, BiFunction<Integer, Integer, Column> source) {
        return new GroundSurfaceMip(width, depth, 64, source);
    }

    private GroundSurfaceMip(
            int width, int depth, int initialStep, BiFunction<Integer, Integer, Column> source) {
        if (width < 1 || depth < 1 || width > 256 || depth > 256)
            throw new IllegalArgumentException("Mip dimensions");
        this.width = width;
        this.depth = depth;
        heights = new int[7][];
        materials = new int[7][];
        layers = new int[7][6][];
        int first = Integer.numberOfTrailingZeros(initialStep);
        int across = across(width, initialStep), down = across(depth, initialStep);
        heights[first] = new int[across * down];
        materials[first] = new int[across * down];
        for (int z = 0; z < depth; z += initialStep) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            for (int x = 0; x < width; x += initialStep) {
                var c =
                        source.apply(
                                Math.min(width - 1, x + initialStep / 2),
                                Math.min(depth - 1, z + initialStep / 2));
                int i = x / initialStep + z / initialStep * across;
                put(first, i, c);
            }
        }
        minimumY = Arrays.stream(heights[first]).min().orElseThrow();
        for (int level = first + 1; level < 7; level++) {
            int step = 1 << level;
            across = across(width, step);
            down = across(depth, step);
            heights[level] = new int[across * down];
            materials[level] = new int[across * down];
            for (int z = 0; z < down; z++)
                for (int x = 0; x < across; x++) {
                    var children = new ArrayList<Column>(4);
                    for (int dz = 0; dz < 2; dz++)
                        for (int dx = 0; dx < 2; dx++) {
                            int a = (2 * x + dx) * (step / 2), b = (2 * z + dz) * (step / 2);
                            if (a < width && b < depth) children.add(column(a, b, step / 2));
                        }
                    var c = reduce(children);
                    put(level, x + z * across, c);
                }
        }
    }

    private GroundSurfaceMip(GroundSurfaceMip source, int minimum) {
        width = source.width;
        depth = source.depth;
        minimumY = source.minimumY;
        heights = source.heights.clone();
        materials = source.materials.clone();
        layers = source.layers.clone();
        for (int level = 0; (1 << level) < minimum; level++) {
            heights[level] = null;
            materials[level] = null;
            layers[level] = new int[6][];
        }
    }

    GroundSurfaceMip coarse() {
        return new GroundSurfaceMip(this, 16);
    }

    double floor() {
        return minimumY + 1 - 128.0;
    }

    boolean fine() {
        return heights[0] != null;
    }

    long bytes() {
        long bytes = 0;
        for (int level = 0; level < 7; level++) {
            if (heights[level] != null) bytes += heights[level].length * 8L;
            for (var layer : layers[level]) if (layer != null) bytes += layer.length * 4L;
        }
        return bytes;
    }

    static int across(int length, int step) {
        return (length + step - 1) / step;
    }

    Column column(int x, int z, int step) {
        if (step < 1 || step > 64 || Integer.bitCount(step) != 1)
            throw new IllegalArgumentException("Mip step");
        int level = Integer.numberOfTrailingZeros(step);
        // A requested fine level stays covered by the nearest available ancestor.
        while (heights[level] == null) {
            level++;
            step *= 2;
        }
        int i = x / step + across(width, step) * (z / step);
        return new Column(
                heights[level][i],
                Block.BLOCK_STATE_REGISTRY.byId(materials[level][i]),
                layer(level, i, 0),
                layer(level, i, 3));
    }

    GroundFarMesh.Surface cell(int x, int z, int step) {
        var c = column(x, z, step);
        return new GroundFarMesh.Surface(
                c.y + 1 - 128.0, c.material, c.y + 1 - 128.0, c.canopy, c.trunk);
    }

    private void put(int level, int i, Column c) {
        heights[level][i] = c.y;
        materials[level][i] = Block.getId(c.material);
        putLayer(level, i, 0, c.canopy);
        if (level == 0) putLayer(level, i, 3, c.trunk);
    }

    private void putLayer(int level, int i, int offset, GroundSurface.Run run) {
        if (run == null) return;
        if (layers[level][offset] == null) {
            for (int j = 0; j < 3; j++) layers[level][offset + j] = new int[heights[level].length];
            Arrays.fill(layers[level][offset], Integer.MIN_VALUE);
        }
        layers[level][offset][i] = run.top();
        layers[level][offset + 1][i] = run.bottom();
        layers[level][offset + 2][i] = run.state();
    }

    private GroundSurface.Run layer(int level, int i, int offset) {
        if (layers[level][offset] == null || layers[level][offset][i] == Integer.MIN_VALUE)
            return null;
        return new GroundSurface.Run(
                layers[level][offset][i],
                layers[level][offset + 1][i],
                layers[level][offset + 2][i]);
    }

    static Column reduce(List<Column> children) {
        var ground = reduceGround(children);
        int count = 0;
        long top = 0, bottom = 0;
        GroundSurface.Run chosen = null;
        int best = 0;
        for (var c : children)
            if (c.canopy != null) {
                count++;
                top += c.canopy.top();
                bottom += c.canopy.bottom();
                int votes = 0;
                for (var other : children)
                    if (other.canopy != null && other.canopy.state() == c.canopy.state()) votes++;
                if (votes > best) {
                    best = votes;
                    chosen = c.canopy;
                }
            }
        return new Column(
                ground.y,
                ground.material,
                count < 2
                        ? null
                        : new GroundSurface.Run(
                                (int) Math.round((double) top / count),
                                (int) Math.round((double) bottom / count),
                                chosen.state()),
                null);
    }

    private static Column reduceGround(List<Column> children) {
        if (children.isEmpty() || children.size() > 4)
            throw new IllegalArgumentException("Mip children");
        long sum = 0;
        int fluids = 0;
        for (var c : children) {
            sum += c.y;
            if (!c.material.getFluidState().isEmpty()) fluids++;
        }
        int mean = (int) Math.round((double) sum / children.size());
        int nearest = Integer.MAX_VALUE;
        for (var c : children) nearest = Math.min(nearest, Math.abs(c.y - mean));
        Column chosen = null;
        int bestCount = 0;
        // At most four children: counting directly avoids a map/stream graph for every mip cell.
        for (var c : children) {
            if (fluids >= 2
                    ? c.material.getFluidState().isEmpty()
                    : Math.abs(c.y - mean) != nearest) continue;
            int count = 0;
            for (var other : children) if (c.material.equals(other.material)) count++;
            if (count > bestCount) {
                bestCount = count;
                chosen = c;
            }
        }
        if (fluids >= 2) {
            var material = chosen.material;
            for (var c : children)
                if (c.material.equals(material) && Math.abs(c.y - mean) < Math.abs(chosen.y - mean))
                    chosen = c;
            return new Column(chosen.y, chosen.material);
        }
        return new Column(mean, chosen.material);
    }

    /**
     * Captures are already plant-filtered by Surface.surfaceOnly / surfaceImage on the render
     * thread.
     */
    static Column sample(
            PlanetTerrain terrain,
            Face face,
            int x,
            int z,
            GroundSurface capture,
            dev.starfold.worldgen.surface.SurfaceFunction function) {
        if (capture != null) {
            int u = x & 15, v = z & 15, count = capture.runCount(u, v);
            var ground = capture.run(u, v, count - 1);
            return new Column(
                    ground.top(),
                    VoxelMesher.shown(ground.material()),
                    count > 1 ? capture.run(u, v, 0) : null,
                    count > 2 ? capture.run(u, v, 1) : null);
        }
        if (terrain == null) return new Column(127,Blocks.GRASS_BLOCK.defaultBlockState());
        return fromVisible(function == null ? new dev.starfold.worldgen.surface.SurfaceFunction(terrain) : function,face,x,z);
    }

    static Column fromVisible(dev.starfold.worldgen.surface.SurfaceFunction function,Face face,int u,int v) {
        var c=function.visible(face,u,v);
        int top=c.top();
        for(var r:c.objects()) if(!VoxelMesher.shown(r.block()).isAir()) top=Math.max(top,r.top());
        final int high=top;
        var runs=GroundSurfaceCapture.scan(high,function.terrain().floor(),y->{
            for(var r:c.objects()) if(y>=r.bottom() && y<=r.top()) return r.block();
            return y<=c.top() ? c.block() : Blocks.AIR.defaultBlockState();
        });
        var ground=runs[runs.length-1];
        return new Column(ground.top(),ground.material(),runs.length>1 ? runs[0] : null,runs.length>2 ? runs[1] : null);
    }
}
