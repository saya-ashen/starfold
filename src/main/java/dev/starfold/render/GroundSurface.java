package dev.starfold.render;

import dev.starfold.chart.PlanetShape;
import dev.starfold.planet.GroundCoordinates;
import dev.starfold.planet.GroundTopology;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.function.LongConsumer;

/** Immutable packed surface value shared by streaming, disk and rendering. */
public final class GroundSurface {
    private final long revision;
    private final byte[] columns;
    private final int[] offsets;
    private final dev.starfold.worldgen.lod.VoxelColumns voxels;

    public record Run(int top, int bottom, int state) {
        BlockState material() {
            return Block.BLOCK_STATE_REGISTRY.byId(state);
        }
    }

    GroundSurface(long revision, Run[][] runs) {
        if (revision < 0) throw new IllegalArgumentException("GroundSurface revision");
        this.revision = revision;
        voxels = null;
        columns = GroundSurfaceCodec.pack(runs);
        offsets = offsets(columns);
    }

    private static int[] offsets(byte[] columns) {
        int count = (columns[0] & 255) << 8 | columns[1] & 255;
        int at = 2 + count * 4, stride = count > 256 ? 6 : 5;
        var offsets = new int[256];
        for (int i = 0; i < 256; i++) {
            offsets[i] = at;
            at += 1 + columns[at] * stride;
        }
        return offsets;
    }

    public int runCount(int u, int v) {
        return columns[offsets[u + 16 * v]];
    }

    public Run run(int u, int v, int index) {
        int at = offsets[u + 16 * v];
        if (index < 0 || index >= columns[at]) throw new IllegalArgumentException("Run index");
        int count = (columns[0] & 255) << 8 | columns[1] & 255;
        at += 1 + index * (count > 256 ? 6 : 5);
        int top = (short) ((columns[at] & 255) << 8 | columns[at + 1] & 255);
        int bottom = (short) ((columns[at + 2] & 255) << 8 | columns[at + 3] & 255);
        int slot =
                count > 256
                        ? (columns[at + 4] & 255) << 8 | columns[at + 5] & 255
                        : columns[at + 4] & 255;
        int p = 2 + slot * 4;
        int state =
                (columns[p] & 255) << 24
                        | (columns[p + 1] & 255) << 16
                        | (columns[p + 2] & 255) << 8
                        | columns[p + 3] & 255;
        return new Run(top, bottom, state);
    }

    int memoryBytes() {
        return columns.length + 1024;
    }

    public GroundSurface(long revision, int[] heights, int[] states) {
        this(revision, heights, states, null);
    }

    /**
     * A column dug open to a deep planet's floor has its surface there: below any floor is the
     * only height refused.
     */
    public static final int LOWEST = GroundCoordinates.FLOOR + (int) PlanetShape.LOWEST - 2;

    public GroundSurface(
            long revision,
            int[] heights,
            int[] states,
            dev.starfold.worldgen.lod.VoxelColumns voxels) {
        if (voxels != null
                && (voxels.floor() < LOWEST + 1
                        || voxels.ceiling()
                                != GroundChunkSnapshot.MIN_Y + GroundChunkSnapshot.HEIGHT))
            throw new IllegalArgumentException("GroundSurface voxel height range");
        if (revision < 0 || heights.length != 256 || states.length != 256)
            throw new IllegalArgumentException("Native surface image");
        for (int i = 0; i < 256; i++)
            if (heights[i] < LOWEST
                    || heights[i] >= GroundChunkSnapshot.MIN_Y + GroundChunkSnapshot.HEIGHT
                    || Block.BLOCK_STATE_REGISTRY.byId(states[i]) == null)
                throw new IllegalArgumentException("Native surface state");
        if (voxels != null)
            for (int i = 0; i < 256; i++) {
                int x = i & 15, z = i >> 4;
                if (heights[i] != voxels.top(x, z)
                        || states[i]
                                != Block.BLOCK_STATE_REGISTRY.getId(
                                        voxels.state(x, heights[i], z)))
                    throw new IllegalArgumentException("GroundSurface and voxel revision disagree");
            }
        this.revision = revision;
        this.voxels = voxels;
        var runs = new Run[256][];
        for (int i = 0; i < 256; i++)
            runs[i] = new Run[] {new Run(heights[i], heights[i], states[i])};
        columns = GroundSurfaceCodec.pack(runs);
        offsets = offsets(columns);
    }

    public long revision() {
        return revision;
    }

    public dev.starfold.worldgen.lod.VoxelColumns voxels() {
        return voxels;
    }

    byte[] packed() {
        return columns.clone();
    }

    public int[] heights() {
        var result = new int[256];
        for (int i = 0; i < 256; i++) result[i] = height(i & 15, i >> 4);
        return result;
    }

    public int[] states() {
        var result = new int[256];
        for (int i = 0; i < 256; i++) result[i] = stateId(i);
        return result;
    }

    int stateId(int column) {
        return run(column & 15, column >> 4, 0).state();
    }

    /** Full sparse runs survive eviction of the optional near occupancy payload. */
    GroundSurface surfaceOnly() {
        if (voxels == null) return this;
        var runs = new Run[256][];
        for (int i = 0; i < 256; i++) {
            int x = i & 15, z = i >> 4;
            runs[i] =
                    GroundSurfaceCapture.scan(
                            height(x, z), voxels.floor(), y -> voxels.state(x, y, z));
        }
        return new GroundSurface(revision, runs);
    }

    /** Content equality without the defensive copies of the public accessors. */
    public boolean sameAs(GroundSurface other) {
        return other == this
                || other != null
                        && revision == other.revision
                        && Arrays.equals(columns, other.columns)
                        && sameVoxels(other);
    }

    /** Geometry and material inputs; a newer notification alone does not change the picture. */
    public boolean sameColumns(GroundSurface other) {
        return other == this
                || other != null && Arrays.equals(columns, other.columns) && sameVoxels(other);
    }

    private boolean sameVoxels(GroundSurface other) {
        return voxels == null ? other.voxels == null : voxels.sameAs(other.voxels);
    }

    public int height(int u, int v) {
        return run(u, v, 0).top();
    }

    public BlockState state(int u, int v) {
        return Block.BLOCK_STATE_REGISTRY.byId(stateId(u + 16 * v));
    }

    static GroundSurface from(GroundChunkSnapshot image) {
        var runs = new GroundSurface.Run[256][];
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                int u = x, v = z;
                runs[x + 16 * z] =
                        GroundSurfaceCapture.scan(
                                GroundChunkSnapshot.MIN_Y + GroundChunkSnapshot.HEIGHT - 1,
                                GroundChunkSnapshot.MIN_Y,
                                y -> image.state(u, y, v));
            }
        return new GroundSurface(image.revision(), runs);
    }
}
