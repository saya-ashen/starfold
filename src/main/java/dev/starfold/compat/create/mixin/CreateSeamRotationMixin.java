package dev.starfold.compat.create.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.simibubi.create.content.kinetics.base.DirectionalShaftHalvesBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.chainDrive.ChainDriveBlock;
import com.simibubi.create.content.kinetics.gearbox.GearboxBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;

import dev.starfold.geometry.ChartOrientation;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Give Create's original pair calculation one chart while its network keeps storage addresses. */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.RotationPropagator", remap = false)
public abstract class CreateSeamRotationMixin {
    @WrapOperation(method = "getRotationSpeedModifier", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;getBlockState()Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState starfold$targetState(KineticBlockEntity target, Operation<BlockState> original,
            KineticBlockEntity from, KineticBlockEntity to,
            @Share("target") LocalRef<PlanetAccess.LocalBlock> projection) {
        var state = original.call(target);
        if (from instanceof BeltBlockEntity && to instanceof BeltBlockEntity) return state;
        var local = starfold$projection((BlockEntity) (Object) from, ((BlockEntity) (Object) to).getBlockPos());
        projection.set(local);
        return local == null ? state : local.address().read(state);
    }

    @WrapOperation(method = "getRotationSpeedModifier", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/BlockPos;subtract(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/core/BlockPos;"))
    private static BlockPos starfold$relativePosition(BlockPos target, Vec3i source, Operation<BlockPos> original,
            @Share("target") LocalRef<PlanetAccess.LocalBlock> projection) {
        var local = projection.get();
        return original.call(local == null ? target : local.position(), source);
    }

    @WrapOperation(method = "getRotationSpeedModifier", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lcom/simibubi/create/content/kinetics/base/IRotate;hasShaftTowards(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z"))
    private static boolean starfold$targetPort(IRotate block, LevelReader level, BlockPos pos,
            BlockState state, Direction side, Operation<Boolean> original,
            @Share("target") LocalRef<PlanetAccess.LocalBlock> projection) {
        var local = projection.get();
        if (local == null) return original.call(block, level, pos, state, side);
        var address = local.address();
        return original.call(block, level, pos, address.write(state), address.ownerDirection(side));
    }

    @WrapOperation(method = "getRotationSpeedModifier", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lcom/simibubi/create/content/kinetics/RotationPropagator;getAxisModifier(Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;Lnet/minecraft/core/Direction;)F"))
    private static float starfold$targetAxis(KineticBlockEntity target, Direction side, Operation<Float> original,
            @Share("target") LocalRef<PlanetAccess.LocalBlock> projection,
            @Share("ownerSpeed") LocalBooleanRef ownerSpeed) {
        var local = projection.get();
        if (local == null) return original.call(target, side);
        var ownerSide = local.address().ownerDirection(side);
        ownerSpeed.set(true);
        return original.call(target, ownerSide) * ChartOrientation.axisSign(ownerSide.getAxis(), -local.address().turns());
    }

    @ModifyReturnValue(method = "getRotationSpeedModifier", at = @At("RETURN"))
    private static float starfold$targetSpeed(float speed, KineticBlockEntity from, KineticBlockEntity target,
            @Share("target") LocalRef<PlanetAccess.LocalBlock> projection,
            @Share("ownerSpeed") LocalBooleanRef ownerSpeed) {
        var local = projection.get();
        if (speed == 0 || local == null || ownerSpeed.get()) return speed;
        var state = ((BlockEntity) (Object) target).getBlockState();
        var block = state.getBlock();
        if ((block instanceof ICogWheel || block instanceof ChainDriveBlock) && block instanceof IRotate rotate)
            return speed * ChartOrientation.axisSign(rotate.getRotationAxis(state), -local.address().turns());
        return speed;
    }

    @WrapOperation(method = "getAxisModifier", at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/base/DirectionalShaftHalvesBlockEntity;getSourceFacing()Lnet/minecraft/core/Direction;"))
    private static Direction starfold$gearboxSource(DirectionalShaftHalvesBlockEntity entity,
            Operation<Direction> original) {
        if (entity instanceof GearboxBlockEntity && ((KineticBlockEntity) entity).source != null) {
            var box = (BlockEntity) (Object) entity;
            var local = starfold$projection(box, ((KineticBlockEntity) entity).source);
            if (local != null && box.getBlockPos().distManhattan(local.position()) == 1) {
                var delta = local.position().subtract(box.getBlockPos());
                return Direction.getNearest(delta.getX(), delta.getY(), delta.getZ());
            }
        }
        return original.call(entity);
    }

    @Unique
    private static PlanetAccess.LocalBlock starfold$projection(BlockEntity reference, BlockPos owner) {
        var level = reference.getLevel();
        var pos = reference.getBlockPos();
        if (!PlanetLevels.canonical(level) || pos.distManhattan(owner) <= 2) return null;
        var local = PlanetAccess.of(level).project(pos, owner);
        if (local == null || local.address().owner().equals(local.position())) return null;
        var delta = local.position().subtract(pos);
        return Math.max(Math.abs(delta.getX()), Math.max(Math.abs(delta.getY()), Math.abs(delta.getZ()))) == 1
                && pos.distManhattan(local.position()) <= 2 ? local : null;
    }

}
