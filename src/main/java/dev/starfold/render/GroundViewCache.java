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

/** Client derived views only. Detail eviction never erases a changed tile's coarse coverage. */
public final class GroundViewCache {
    public static final int DETAIL_LIMIT = 512;
    public static final long BYTE_LIMIT = 192L * 1024 * 1024;

    public record Surface(long revision, int[] heights, int[] states) {
        /** A column dug open to a deep planet's floor has its surface there: below any floor is the only height refused. */
        public static final int LOWEST = GroundCoordinates.FLOOR + (int) PlanetShape.LOWEST - 2;

        public Surface {
            if (revision < 0 || heights.length != 256 || states.length != 256)
                throw new IllegalArgumentException("Native surface image");
            heights = heights.clone();
            states = states.clone();
            for (int i = 0; i < 256; i++)
                if (heights[i] < LOWEST
                        || heights[i] >= GroundChunkSnapshot.MIN_Y + GroundChunkSnapshot.HEIGHT
                        || Block.BLOCK_STATE_REGISTRY.byId(states[i]) == null)
                    throw new IllegalArgumentException("Native surface state");
        }

        @Override
        public int[] heights() {
            return heights.clone();
        }

        @Override
        public int[] states() {
            return states.clone();
        }

        /** Content equality without the defensive copies of the public accessors. */
        public boolean sameAs(Surface other) {
            return other == this
                    || other != null
                            && revision == other.revision
                            && Arrays.equals(heights, other.heights)
                            && Arrays.equals(states, other.states);
        }

        public int height(int u, int v) {
            return heights[u + 16 * v];
        }

        public BlockState state(int u, int v) {
            return Block.BLOCK_STATE_REGISTRY.byId(states[u + 16 * v]);
        }
    }

    private final LinkedHashMap<Long, GroundChunkSnapshot> detail =
            new LinkedHashMap<>(16, .75f, true);
    private final Map<Long, Long> versions = new HashMap<>();
    private final Map<Long, String> fingerprints = new HashMap<>();
    private final Map<Long, Surface> coarse = new HashMap<>();
    private final LongConsumer invalidate;
    private long bytes;
    private final java.util.function.Supplier<PlanetShape> shape;

    public GroundViewCache(LongConsumer invalidate) {
        this(() -> PlanetShape.STANDARD, invalidate);
    }

    /** The view of whichever planet is being observed: its chunks are addressed by that planet's shape. */
    public GroundViewCache(
            java.util.function.Supplier<PlanetShape> shape, LongConsumer invalidate) {
        this.shape = Objects.requireNonNull(shape);
        this.invalidate = Objects.requireNonNull(invalidate);
    }

    public PlanetShape shape() {
        return shape.get();
    }

    public boolean accept(GroundChunkSnapshot image) {
        long key = image.key();
        var revision = versions.get(key);
        if (revision != null && image.revision() <= revision) return false;
        boolean changed =
                !detail.containsKey(key) || !image.fingerprint().equals(fingerprints.get(key));
        var surface = surface(image);
        if (surface == null) coarse.remove(key);
        else coarse.put(key, surface);
        versions.put(key, image.revision());
        fingerprints.put(key, image.fingerprint());
        var old = detail.put(key, image);
        if (old != null) bytes -= old.memoryBytes();
        bytes += image.memoryBytes();
        trim();
        if (changed) invalidate.accept(key);
        return true;
    }

    /** Requesting an evicted same-revision detail is valid; accepting stale changes is not. */
    public boolean refill(GroundChunkSnapshot image) {
        var revision = versions.get(image.key());
        if (revision == null
                || image.revision() != revision
                || !image.fingerprint().equals(fingerprints.get(image.key()))
                || detail.containsKey(image.key())) return false;
        detail.put(image.key(), image);
        bytes += image.memoryBytes();
        trim();
        invalidate.accept(image.key());
        return true;
    }

    private Set<Long> retained = Set.of();

    public void retain(Set<Long> keys) {
        if (!retained.equals(keys)) retained = Set.copyOf(keys);
        trim();
    }

    private boolean overLimit() {
        return detail.size() > DETAIL_LIMIT || bytes > BYTE_LIMIT;
    }

    private void trim() {
        var entries = detail.entrySet().iterator();
        while (overLimit() && entries.hasNext()) {
            var entry = entries.next();
            if (retained.contains(entry.getKey())) continue;
            bytes -= entry.getValue().memoryBytes();
            entries.remove();
        }
        // Pathological palettes still obey the hard cap; pins never permit unbounded memory.
        entries = detail.entrySet().iterator();
        while (overLimit() && entries.hasNext()) {
            var entry = entries.next();
            bytes -= entry.getValue().memoryBytes();
            entries.remove();
        }
    }

    public BlockState canonical(BlockPos canonical) {
        var c = GroundTopology.cell(canonical);
        if (!shape().topology().owned(c))
            throw new IllegalArgumentException("View query not canonical");
        long key = c.u() / 16 + 128L * (c.v() / 16 + 128L * c.face().ordinal());
        var chunk = detail.get(key);
        return chunk == null ? null : chunk.state(canonical);
    }

    public GroundChunkSnapshot image(long key) {
        return detail.get(key);
    }

    public Surface surface(long key) {
        return coarse.get(key);
    }

    public long version(long key) {
        return versions.getOrDefault(key, -1L);
    }

    public boolean detailed(long key) {
        return detail.containsKey(key);
    }

    public int detailCount() {
        return detail.size();
    }

    public long detailBytes() {
        return bytes;
    }

    public void clear() {
        retained = Set.of();
        detail.clear();
        versions.clear();
        fingerprints.clear();
        coarse.clear();
        bytes = 0;
    }

    private static Surface surface(GroundChunkSnapshot image) {
        var result = surfaceImage(image);
        int grass = Block.BLOCK_STATE_REGISTRY.getId(Blocks.GRASS_BLOCK.defaultBlockState());
        for (int i = 0; i < 256; i++)
            if (result.heights[i] != GroundCoordinates.FLOOR - 1 || result.states[i] != grass)
                return result;
        return null;
    }

    static Surface surfaceImage(GroundChunkSnapshot image) {
        var height = new int[256];
        var states = new int[256];
        boolean changed = false;
        int grass = Block.BLOCK_STATE_REGISTRY.getId(Blocks.GRASS_BLOCK.defaultBlockState()),
                air = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                int i = x + 16 * z;
                height[i] = GroundChunkSnapshot.MIN_Y - 1;
                states[i] = air;
                for (int y = GroundChunkSnapshot.MIN_Y + GroundChunkSnapshot.HEIGHT - 1;
                        y >= GroundChunkSnapshot.MIN_Y;
                        y--) {
                    var state = image.state(x, y, z);
                    if (!state.isAir()) {
                        height[i] = y;
                        states[i] = Block.BLOCK_STATE_REGISTRY.getId(state);
                        break;
                    }
                }
                changed |= height[i] != GroundCoordinates.FLOOR - 1 || states[i] != grass;
            }
        return new Surface(image.revision(), height, states);
    }
}
