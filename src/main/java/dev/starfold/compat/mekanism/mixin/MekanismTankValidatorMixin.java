package dev.starfold.compat.mekanism.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.starfold.compat.mekanism.MekanismTankChart;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import mekanism.common.content.tank.TankValidator;
import mekanism.common.lib.math.voxel.VoxelCuboid;
import mekanism.common.lib.multiblock.CuboidStructureValidator;
import mekanism.common.lib.multiblock.IMultiblockBase;
import mekanism.common.lib.multiblock.IValveHandler.ValveData;
import mekanism.common.lib.multiblock.Structure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;
import java.util.Set;

@Pseudo
@Mixin(targets = "mekanism.common.lib.multiblock.CuboidStructureValidator", remap = false)
public abstract class MekanismTankValidatorMixin implements MekanismTankChart.View {
    @Shadow protected Level world;
    @Unique private MekanismTankChart starfold$tankChart;

    @Override
    public MekanismTankChart starfold$tankChart() {
        return starfold$tankChart;
    }

    @WrapOperation(method = "precheck", at = @At(value = "INVOKE", target =
            "Lmekanism/common/lib/multiblock/StructureHelper;fetchCuboid(Lmekanism/common/lib/multiblock/Structure;Lmekanism/common/lib/math/voxel/VoxelCuboid;Lmekanism/common/lib/math/voxel/VoxelCuboid;)Lmekanism/common/lib/math/voxel/VoxelCuboid;"))
    private VoxelCuboid starfold$findTankBounds(Structure structure, VoxelCuboid min,
            VoxelCuboid max, Operation<VoxelCuboid> original) {
        var nativeBounds = original.call(structure, min, max);
        starfold$tankChart = null;
        if (nativeBounds != null || !((Object) this instanceof TankValidator)
                || !PlanetLevels.canonical(world) || structure.getController() == null) return nativeBounds;
        var members = ((MekanismStructureNodes) structure).starfold$nodes().keySet();
        starfold$tankChart = MekanismTankChart.find(structure.getController().getBlockPos(), members, min, max);
        return starfold$tankChart == null ? null : starfold$tankChart.bounds();
    }

    @WrapOperation(method = "validateNode", at = @At(value = "INVOKE", target =
            "Lmekanism/common/util/WorldUtils;getBlockState(Lnet/minecraft/world/level/LevelAccessor;Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;Lnet/minecraft/core/BlockPos;)Ljava/util/Optional;"))
    private Optional<BlockState> starfold$readTankCell(LevelAccessor level,
            Long2ObjectMap<ChunkAccess> chunks, BlockPos local, Operation<Optional<BlockState>> original) {
        if (starfold$tankChart == null) return original.call(level, chunks, local);
        return PlanetAccess.SHARED.inChart(local) && world.isLoaded(local)
                ? Optional.of(world.getBlockState(local)) : Optional.empty();
    }

    @WrapOperation(method = "validateFrame", at = @At(value = "INVOKE", target =
            "Lmekanism/common/lib/multiblock/Structure;getTile(Lnet/minecraft/core/BlockPos;)Lmekanism/common/lib/multiblock/IMultiblockBase;"))
    private IMultiblockBase starfold$findTankMember(Structure structure, BlockPos local,
            Operation<IMultiblockBase> original) {
        return original.call(structure, starfold$tankChart == null ? local : PlanetAccess.SHARED.address(local).owner());
    }

    @WrapOperation(method = "validateNode", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/core/BlockPos;immutable()Lnet/minecraft/core/BlockPos;"))
    private BlockPos starfold$storeTankInterior(BlockPos local, Operation<BlockPos> original) {
        return starfold$tankChart == null ? original.call(local) : PlanetAccess.SHARED.address(local).owner();
    }

    @WrapOperation(method = "validateFrame", at = @At(value = "INVOKE", target =
            "Ljava/util/Set;add(Ljava/lang/Object;)Z", ordinal = 0))
    private boolean starfold$storeTankWall(Set<BlockPos> locations, Object local, Operation<Boolean> original) {
        return original.call(locations, starfold$tankChart == null
                ? local : PlanetAccess.SHARED.address((BlockPos) local).owner());
    }

    @WrapOperation(method = "validateFrame", at = @At(value = "INVOKE", target =
            "Lmekanism/common/lib/multiblock/CuboidStructureValidator;getSide(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Direction;"))
    private Direction starfold$tankValveDirection(CuboidStructureValidator<?> validator, BlockPos local,
            Operation<Direction> original) {
        var direction = original.call(validator, local);
        return starfold$tankChart == null || direction == null ? direction
                : PlanetAccess.SHARED.address(local).ownerDirection(direction);
    }

    @WrapOperation(method = "validateFrame", at = @At(value = "NEW", target =
            "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Lmekanism/common/lib/multiblock/IValveHandler$ValveData;"))
    private ValveData starfold$tankValvePosition(BlockPos local, Direction direction, Operation<ValveData> original) {
        return original.call(starfold$tankChart == null ? local : PlanetAccess.SHARED.address(local).owner(), direction);
    }
}
