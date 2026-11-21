package dev.starfold.worldgen.surface;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Predicate;

/** Bounded, synchronous registry-only feature execution. No Level, server, chunks, ticks or entities. */
public final class FeatureSandbox {
    private FeatureSandbox() {}

    public static final int BASE = 127, RADIUS = 32, HEIGHT = 96;

    public static final class Template {
        private final int[] positions;
        private final short[] states;
        private final int radius, height;

        Template(SortedMap<Integer, BlockState> blocks) {
            positions = new int[blocks.size()];
            states = new short[blocks.size()];
            int i = 0, r = 0, h = 0;
            for (var e : blocks.entrySet()) {
                positions[i] = e.getKey();
                int id = net.minecraft.world.level.block.Block.getId(e.getValue());
                if (id < 0 || id > 65535)
                    throw new IllegalArgumentException("Template state ID overflow");
                states[i++] = (short) id;
                r =
                        Math.max(
                                r,
                                Math.max(
                                        Math.abs((e.getKey() >> 16) - 32),
                                        Math.abs((e.getKey() >> 8 & 255) - 32)));
                h = Math.max(h, e.getKey() & 255);
            }
            radius = r;
            height = h;
        }

        void forEach(java.util.function.BiConsumer<BlockPos, BlockState> action) {
            for (int i = 0; i < positions.length; i++) {
                int p = positions[i];
                action.accept(
                        new BlockPos((p >> 16) - 32, p & 255, (p >> 8 & 255) - 32),
                        net.minecraft.world.level.block.Block.stateById(
                                Short.toUnsignedInt(states[i])));
            }
        }

        void write(java.io.DataOutputStream out) throws java.io.IOException {
            out.writeInt(positions.length);
            for (int i = 0; i < positions.length; i++) {
                out.writeInt(positions[i]);
                out.writeShort(states[i]);
            }
        }

        static Template read(java.io.DataInputStream in) throws java.io.IOException {
            int count = in.readInt();
            if (count < 1 || count > 65 * 65 * 96) throw new java.io.IOException("Template count");
            var blocks = new TreeMap<Integer, BlockState>();
            int last = -1;
            for (int i = 0; i < count; i++) {
                int p = in.readInt(), id = in.readUnsignedShort();
                var state = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(id);
                if (p <= last
                        || (p >> 16) > 64
                        || (p >> 8 & 255) > 64
                        || (p & 255) < 1
                        || (p & 255) > 96
                        || state == null
                        || state.isAir()) throw new java.io.IOException("Template block");
                blocks.put(p, state);
                last = p;
            }
            return new Template(blocks);
        }

        int signature() {
            return 31 * Arrays.hashCode(positions) + Arrays.hashCode(states);
        }

        public int radius() {
            return radius;
        }

        public int height() {
            return height;
        }

        public int blocks() {
            return positions.length;
        }

        public BlockState block(int x, int y, int z) {
            int i = Arrays.binarySearch(positions, key(x, y, z));
            return i < 0
                    ? Blocks.AIR.defaultBlockState()
                    : net.minecraft.world.level.block.Block.stateById(
                            Short.toUnsignedInt(states[i]));
        }
    }

    static Template plant(ConfiguredFeature<?, ?> feature, ChunkGenerator generator, long seed) {
        if (feature.config()
                instanceof
                net.minecraft.world.level.levelgen.feature.configurations.RandomPatchConfiguration
                        patch) feature = patch.feature().value().feature().value();
        if (feature.config()
                instanceof
                net.minecraft.world.level.levelgen.feature.configurations.SimpleBlockConfiguration
                        simple) {
            var state = simple.toPlace().getState(RandomSource.create(seed), BlockPos.ZERO);
            var blocks = new TreeMap<Integer, BlockState>();
            blocks.put(key(0, 1, 0), state);
            if (state.getBlock() instanceof net.minecraft.world.level.block.DoublePlantBlock)
                blocks.put(
                        key(0, 2, 0),
                        state.setValue(
                                net.minecraft.world.level.block.DoublePlantBlock.HALF,
                                net.minecraft.world.level.block.state.properties.DoubleBlockHalf
                                        .UPPER));
            return new Template(blocks);
        }
        var template = build(feature, generator, Blocks.SAND.defaultBlockState(), seed);
        var blocks = new TreeMap<Integer, BlockState>();
        // Seagrass chooses an offset inside its native feature. Recenter that complete column.
        int[] center = {0, 0};
        if (feature.feature() == Feature.SEAGRASS)
            template.forEach(
                    (pos, state) -> {
                        center[0] = pos.getX();
                        center[1] = pos.getZ();
                    });
        for (int y = 1; y <= template.height(); y++) {
            var state = template.block(center[0], y, center[1]);
            if (!state.isAir()) blocks.put(key(0, y, 0), state);
        }
        return new Template(blocks);
    }

    static int key(int x, int y, int z) {
        return (x + 32) << 16 | (z + 32) << 8 | y;
    }

    @SuppressWarnings("unchecked")
    public static Template build(
            ConfiguredFeature<?, ?> feature, ChunkGenerator generator, BlockState soil, long seed) {
        var written = new HashMap<BlockPos, BlockState>();
        boolean iceberg = feature.feature() == Feature.ICEBERG;
        boolean aquatic = VisibleFeatures.aquatic(feature.feature());
        int waterTop = aquatic ? BASE + 24 : BASE;
        var random = RandomSource.create(seed);
        java.util.function.Function<BlockPos, BlockState> read =
                pos ->
                        written.getOrDefault(
                                pos,
                                pos.getY() <= BASE
                                        ? (iceberg && pos.getY() > BASE - 24
                                                ? Blocks.WATER.defaultBlockState()
                                                : soil)
                                        : aquatic && pos.getY() <= waterTop
                                                ? Blocks.WATER.defaultBlockState()
                                                : Blocks.AIR.defaultBlockState());
        WorldGenLevel world =
                (WorldGenLevel)
                        Proxy.newProxyInstance(
                                WorldGenLevel.class.getClassLoader(),
                                new Class<?>[] {WorldGenLevel.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "getBlockState" -> read.apply((BlockPos) args[0]);
                                            case "getFluidState" ->
                                                    read.apply((BlockPos) args[0]).getFluidState();
                                            case "isEmptyBlock" ->
                                                    read.apply((BlockPos) args[0]).isAir();
                                            case "isStateAtPosition" ->
                                                    ((Predicate<BlockState>) args[1])
                                                            .test(read.apply((BlockPos) args[0]));
                                            case "isFluidAtPosition" ->
                                                    ((Predicate<
                                                                            net.minecraft.world
                                                                                    .level.material
                                                                                    .FluidState>)
                                                                    args[1])
                                                            .test(
                                                                    read.apply((BlockPos) args[0])
                                                                            .getFluidState());
                                            case "setBlock" -> {
                                                var pos = ((BlockPos) args[0]).immutable();
                                                if (Math.abs(pos.getX()) > RADIUS
                                                        || Math.abs(pos.getZ()) > RADIUS
                                                        || pos.getY() > BASE + HEIGHT
                                                        || pos.getY() < -64)
                                                    throw new UnsupportedOperationException(
                                                            "feature exceeds 65x65x160 sandbox");
                                                written.put(pos, (BlockState) args[1]);
                                                yield true;
                                            }
                                            case "getMinBuildHeight" -> -64;
                                            case "getMaxBuildHeight" -> 320;
                                            case "getHeight" ->
                                                    args == null || args.length == 0
                                                            ? 384
                                                            : height(
                                                                    (net.minecraft.world.level
                                                                                    .levelgen
                                                                                    .Heightmap
                                                                                    .Types)
                                                                            args[0],
                                                                    aquatic,
                                                                    waterTop);
                                            case "getHeightmapPos" ->
                                                    new BlockPos(
                                                            ((BlockPos) args[1]).getX(),
                                                            height(
                                                                    (net.minecraft.world.level
                                                                                    .levelgen
                                                                                    .Heightmap
                                                                                    .Types)
                                                                            args[0],
                                                                    aquatic,
                                                                    waterTop),
                                                            ((BlockPos) args[1]).getZ());
                                            case "getSeed" -> seed;
                                            case "getRandom" -> random;
                                            case "ensureCanWrite",
                                                    "hasChunk",
                                                    "hasChunkAt",
                                                    "isLoaded",
                                                    "isAreaLoaded" ->
                                                    true;
                                            case "isOutsideBuildHeight" ->
                                                    args[0] instanceof BlockPos p
                                                            ? p.getY() < -64 || p.getY() >= 320
                                                            : (int) args[0] < -64
                                                                    || (int) args[0] >= 320;
                                            case "getBlockEntity" ->
                                                    args.length == 1 ? null : Optional.empty();
                                            case "scheduleTick",
                                                    "setCurrentlyGenerating",
                                                    "blockUpdated",
                                                    "neighborChanged" ->
                                                    null;
                                            case "toString" -> "SurfaceFeatureSandbox";
                                            default ->
                                                    throw new UnsupportedOperationException(
                                                            "sandbox method: " + method.getName());
                                        });
        var settings =
                new net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings(
                        Optional.empty(),
                        generator.getBiomeSource().possibleBiomes().iterator().next(),
                        List.of());
        var flatGenerator =
                new net.minecraft.world.level.levelgen.FlatLevelSource(settings) {
                    @Override
                    public int getSeaLevel() {
                        return waterTop + 1;
                    }
                };
        boolean success = feature.place(world, flatGenerator, random, new BlockPos(0, BASE + 1, 0));
        if (!success)
            throw new UnsupportedOperationException(
                    "configured feature rejected flat substrate " + soil);
        var blocks = new TreeMap<Integer, BlockState>();
        // Keep aquatic states (including their fluid/properties), but never copy the sandbox's
        // water volume.
        written.forEach(
                (pos, state) -> {
                    int y = pos.getY() - BASE;
                    if (y > 0
                            && !state.isAir()
                            && !(state.getBlock()
                                    instanceof net.minecraft.world.level.block.LiquidBlock)
                            && (aquatic || state.getFluidState().isEmpty()))
                        blocks.put(key(pos.getX(), y, pos.getZ()), state);
                });
        if (blocks.isEmpty()) throw new UnsupportedOperationException("no above-cap blocks");
        return new Template(blocks);
    }

    private static int height(
            net.minecraft.world.level.levelgen.Heightmap.Types type,
            boolean aquatic,
            int waterTop) {
        return aquatic
                        && type != net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR
                        && type != net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG
                ? waterTop + 1
                : BASE + 1;
    }
}
