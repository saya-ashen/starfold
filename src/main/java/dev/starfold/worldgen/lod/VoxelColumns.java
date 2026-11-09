package dev.starfold.worldgen.lod;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Optional;

/** Immutable display-only column runs. Air gaps are explicit; no inventory or ticking data. */
public final class VoxelColumns {
    public static final int WIDTH = 16, COLUMNS = WIDTH * WIDTH, MAX_RUNS = 16384;
    private final int floor, ceiling;
    private final int[] offsets, ends, states;

    /** All columns cover [floor, ceiling); ends are exclusive absolute Y coordinates. */
    public VoxelColumns(int floor, int ceiling, int[] offsets, int[] ends, int[] states) {
        if (floor < -4096
                || ceiling > 4096
                || ceiling <= floor
                || ceiling - floor > 4096
                || offsets == null
                || offsets.length != COLUMNS + 1
                || ends == null
                || states == null
                || ends.length < COLUMNS
                || ends.length > MAX_RUNS
                || ends.length != states.length)
            throw new IllegalArgumentException("Voxel column bounds");
        this.floor = floor;
        this.ceiling = ceiling;
        this.offsets = offsets.clone();
        this.ends = ends.clone();
        this.states = states.clone();
        if (this.offsets[0] != 0 || this.offsets[COLUMNS] != this.ends.length)
            throw new IllegalArgumentException("Voxel column offsets");
        for (int column = 0; column < COLUMNS; column++) {
            int from = this.offsets[column], to = this.offsets[column + 1];
            if (from < 0 || to <= from || to > this.ends.length)
                throw new IllegalArgumentException("Voxel column span");
            int previous = floor;
            for (int run = from; run < to; run++) {
                if (this.ends[run] <= previous
                        || this.ends[run] > ceiling
                        || this.states[run] < 0
                        || Block.BLOCK_STATE_REGISTRY.byId(this.states[run]) == null
                        || run > from && this.states[run] == this.states[run - 1])
                    throw new IllegalArgumentException("Voxel column run");
                previous = this.ends[run];
            }
            if (previous != ceiling) throw new IllegalArgumentException("Incomplete voxel column");
        }
    }

    public int floor() {
        return floor;
    }

    public int ceiling() {
        return ceiling;
    }

    public int[] offsets() {
        return offsets.clone();
    }

    public int[] ends() {
        return ends.clone();
    }

    public int[] states() {
        return states.clone();
    }

    public int memoryBytes() {
        return 4 * (offsets.length + ends.length + states.length);
    }

    public BlockState state(int x, int y, int z) {
        if (x < 0 || x >= WIDTH || z < 0 || z >= WIDTH)
            throw new IllegalArgumentException("Voxel column position");
        if (y < floor || y >= ceiling) return Blocks.AIR.defaultBlockState();
        int column = x + WIDTH * z;
        int low = offsets[column], high = offsets[column + 1];
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (ends[middle] <= y) low = middle + 1;
            else high = middle;
        }
        return Block.BLOCK_STATE_REGISTRY.byId(states[low]);
    }

    public int top(int x, int z) {
        if (x < 0 || x >= WIDTH || z < 0 || z >= WIDTH)
            throw new IllegalArgumentException("Voxel column position");
        int column = x + WIDTH * z;
        for (int run = offsets[column + 1] - 1; run >= offsets[column]; run--)
            if (!Block.BLOCK_STATE_REGISTRY.byId(states[run]).isAir()) return ends[run] - 1;
        return floor - 1;
    }

    public boolean sameAs(VoxelColumns other) {
        return other == this
                || other != null
                        && floor == other.floor
                        && ceiling == other.ceiling
                        && Arrays.equals(offsets, other.offsets)
                        && Arrays.equals(ends, other.ends)
                        && Arrays.equals(states, other.states);
    }

    @FunctionalInterface
    public interface Reader {
        BlockState state(int x, int y, int z);
    }

    /** Bounded capture, advanced by the owner thread; overflow is absence, never a truncated image. */
    public static final class Capture {
        private final int floor, ceiling;
        private final int[] offsets = new int[COLUMNS + 1];
        private final int[] ends = new int[MAX_RUNS], states = new int[MAX_RUNS];
        private int column, y, runs;
        private boolean overflow;
        private VoxelColumns result;

        public Capture(int floor, int ceiling) {
            if (floor < -4096 || ceiling > 4096 || ceiling <= floor || ceiling - floor > 4096)
                throw new IllegalArgumentException("Voxel capture bounds");
            this.floor = floor;
            this.ceiling = ceiling;
            y = floor;
        }

        /** Reader must describe one immutable revision for the complete capture. */
        public int step(Reader reader, int maxReads) {
            if (maxReads < 0) throw new IllegalArgumentException("Voxel capture budget");
            int reads = 0;
            while (!done() && reads < maxReads) {
                var state = reader.state(column % WIDTH, y, column / WIDTH);
                if (state == null) throw new IllegalArgumentException("Voxel capture state");
                int id = Block.BLOCK_STATE_REGISTRY.getId(state);
                if (id < 0) throw new IllegalArgumentException("Voxel capture state");
                reads++;
                if (y == floor || states[runs - 1] != id) {
                    if (runs == MAX_RUNS) {
                        overflow = true;
                        break;
                    }
                    states[runs] = id;
                    ends[runs++] = y + 1;
                } else ends[runs - 1] = y + 1;
                if (++y == ceiling) {
                    offsets[++column] = runs;
                    y = floor;
                }
            }
            return reads;
        }

        public boolean done() {
            return overflow || column == COLUMNS;
        }

        public boolean overflow() {
            return overflow;
        }

        public Optional<VoxelColumns> result() {
            if (!done()) throw new IllegalStateException("Incomplete voxel capture");
            if (overflow) return Optional.empty();
            if (result == null)
                result =
                        new VoxelColumns(
                                floor,
                                ceiling,
                                offsets,
                                Arrays.copyOf(ends, runs),
                                Arrays.copyOf(states, runs));
            return Optional.of(result);
        }
    }
}
