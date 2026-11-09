package dev.starfold.ship;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.function.Predicate;

/** Shared fail-closed admission; never requests a chunk load or bypasses ordinary mutation callbacks. */
final class GasDamageAdmission {
    static boolean eligible(
            BlockState state, BlockGetter reader, BlockPos pos, boolean actualBlockEntity) {
        if (!simpleClass(state.getBlock().getClass())) return false;
        return !actualBlockEntity
                && !state.isAir()
                && !state.hasBlockEntity()
                && state.getFluidState().isEmpty()
                && state.getDestroySpeed(reader, pos) >= 0
                && (state.getBlock().getClass() == SlabBlock.class
                        || Block.isShapeFullBlock(state.getCollisionShape(reader, pos)));
    }

    /** Sable treats self mass at or below zero as an empty body; keep a margin for rounding. */
    static final double MIN_REMAINING_MASS = 1e-6;

    static boolean leavesMass(double hullMass, double blockMass) {
        return Double.isFinite(hullMass)
                && Double.isFinite(blockMass)
                && hullMass - blockMass > MIN_REMAINING_MASS;
    }

    static boolean simpleClass(Class<?> type) {
        return type == Block.class
                || type == SlabBlock.class
                || type == RotatedPillarBlock.class
                || type == HalfTransparentBlock.class
                || type == TransparentBlock.class
                || type == StainedGlassBlock.class;
    }

    /** Sable expandIfNecessary examines exactly these six offset-2 neighbors, including AIR writes. */
    static boolean loadedMargin(BlockPos pos, Predicate<BlockPos> ownedAndLoaded) {
        if (!ownedAndLoaded.test(pos)) return false;
        for (var direction : Direction.values()) {
            final BlockPos neighbor;
            try {
                neighbor =
                        new BlockPos(
                                Math.addExact(pos.getX(), direction.getStepX() * 2),
                                Math.addExact(pos.getY(), direction.getStepY() * 2),
                                Math.addExact(pos.getZ(), direction.getStepZ() * 2));
            } catch (ArithmeticException overflow) {
                return false;
            }
            if (!ownedAndLoaded.test(neighbor)) return false;
        }
        return true;
    }

    static LevelChunk checkedChunk(
            ServerLevel level,
            ServerSubLevelContainer container,
            ServerSubLevel hull,
            BlockPos pos) {
        if (level.captureBlockSnapshots
                || level.isOutsideBuildHeight(pos)
                || hull.isRemoved()
                || hull.getLevel() != level
                || hull.getPlot().getSubLevel() != hull
                || SubLevelContainer.getContainer(level) != container
                || container.getSubLevel(hull.getUniqueId()) != hull) return null;
        if (!loadedMargin(pos, p -> ownedChunk(level, hull, p) != null)) return null;
        return ownedChunk(level, hull, pos);
    }

    static LevelChunk ownedChunk(ServerLevel level, ServerSubLevel hull, BlockPos pos) {
        var plot = hull.getPlot();
        var chunkPos = new ChunkPos(pos);
        if (!plot.contains(chunkPos)) return null;
        var holder = plot.getChunkHolder(plot.toLocal(chunkPos));
        if (holder == null) return null;
        var chunk = holder.getChunk();
        return chunk != null && level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z) == chunk
                ? chunk
                : null;
    }

    private GasDamageAdmission() {}
}
