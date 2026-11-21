package dev.starfold.worldgen.surface;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CoralBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Function;

/** Native survival checks in template coordinates: y=0 is the actual seabed, not the water surface. */
final class UnderwaterPlacement {
    private UnderwaterPlacement() {}

    static boolean water(BlockState state) {
        return state.is(Blocks.WATER)
                && state.getFluidState().is(FluidTags.WATER)
                && state.getFluidState().isSource();
    }

    static boolean plant(BlockState state) {
        var block = state.getBlock();
        return block instanceof net.minecraft.world.level.block.SeagrassBlock
                || block instanceof net.minecraft.world.level.block.TallSeagrassBlock
                || block instanceof net.minecraft.world.level.block.KelpBlock
                || block instanceof net.minecraft.world.level.block.KelpPlantBlock
                || block instanceof net.minecraft.world.level.block.SeaPickleBlock
                || block instanceof net.minecraft.world.level.block.BaseCoralPlantTypeBlock
                || block instanceof CoralBlock;
    }

    /** Whole objects fit or fail below a water cap. Only kelp may shorten, keeping its native head state. */
    static FeatureSandbox.Template fit(
            FeatureSandbox.Template source,
            Feature<?> feature,
            Function<BlockPos, BlockState> environment) {
        var blocks = new HashMap<BlockPos, BlockState>();
        source.forEach(blocks::put);
        if (feature == Feature.KELP) {
            int height = 0;
            for (int y = 1; y <= source.height(); y++) {
                if (!water(environment.apply(new BlockPos(0, y, 0)))
                        || !water(environment.apply(new BlockPos(0, y + 1, 0)))) break;
                height = y;
            }
            if (height == 0) return null;
            var head = source.block(0, source.height(), 0);
            if (!head.is(Blocks.KELP)) return null;
            blocks.clear();
            for (int y = 1; y < height; y++)
                blocks.put(new BlockPos(0, y, 0), Blocks.KELP_PLANT.defaultBlockState());
            blocks.put(new BlockPos(0, height, 0), head);
        }
        // Leave the actual water surface intact for both the chunk and the shared visible-column
        // path.
        if (blocks.isEmpty()
                || blocks.keySet().stream()
                        .anyMatch(
                                p ->
                                        !water(environment.apply(p))
                                                || !water(environment.apply(p.above()))))
            return null;
        Function<BlockPos, BlockState> read =
                p -> blocks.containsKey(p) ? blocks.get(p) : environment.apply(p);
        LevelReader world =
                (LevelReader)
                        Proxy.newProxyInstance(
                                LevelReader.class.getClassLoader(),
                                new Class<?>[] {LevelReader.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "getBlockState" -> read.apply((BlockPos) args[0]);
                                            case "getFluidState" ->
                                                    read.apply((BlockPos) args[0]).getFluidState();
                                            case "getBlockEntity" ->
                                                    args.length == 1 ? null : Optional.empty();
                                            case "getMinBuildHeight" -> -4096;
                                            case "getMaxBuildHeight" -> 4096;
                                            case "getHeight" -> 8192;
                                            case "toString" -> "UnderwaterSurfacePlacement";
                                            default ->
                                                    throw new UnsupportedOperationException(
                                                            "underwater survival method: "
                                                                    + method.getName());
                                        });
        // Coral blocks have no vanilla support predicate; at least one root must touch the real
        // seabed.
        // Pickles and grasses use their own native support checks (including partial collision
        // faces).
        boolean coral =
                feature == Feature.CORAL_TREE
                        || feature == Feature.CORAL_CLAW
                        || feature == Feature.CORAL_MUSHROOM;
        if (coral
                && blocks.entrySet().stream()
                        .noneMatch(
                                e ->
                                        e.getValue().getBlock() instanceof CoralBlock
                                                && environment
                                                        .apply(e.getKey().below())
                                                        .isFaceSturdy(
                                                                world,
                                                                e.getKey().below(),
                                                                Direction.UP))) return null;
        for (var e : blocks.entrySet()) {
            if (!e.getValue().canSurvive(world, e.getKey())) return null;
            if (e.getValue().getBlock() instanceof CoralBlock) {
                boolean wet = false;
                for (var direction : Direction.values())
                    wet |=
                            read.apply(e.getKey().relative(direction))
                                    .getFluidState()
                                    .is(FluidTags.WATER);
                if (!wet) return null;
            }
        }
        var fitted = new TreeMap<Integer, BlockState>();
        blocks.forEach(
                (p, state) -> fitted.put(FeatureSandbox.key(p.getX(), p.getY(), p.getZ()), state));
        return new FeatureSandbox.Template(fitted);
    }
}
