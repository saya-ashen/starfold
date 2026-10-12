package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.dev.ArrivalProbe;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.ground.GroundSeams;
import dev.starfold.ground.GroundServer;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.PlanetAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Virtual addresses alias native canonical chunks. No ghost blocks are saved or simulated. */
@Mixin(Level.class)
public abstract class GroundTopologyLevelMixin {
    @WrapMethod(method = "getBlockState")
    private BlockState starfold$nativeRead(BlockPos pos, Operation<BlockState> original) {
        var level = (Level) (Object) this;
        if (!GroundServer.is(level)) return original.call(pos);
        // A client world is laid out in the observer's continuous coordinates: read it where it is;
        // only the corner mask is virtual.
        if (level.isClientSide)
            return GroundSeams.clientMasked(level, pos)
                    ? net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState()
                    : original.call(pos);
        var access = PlanetAccess.of(level);
        if (!access.inChart(pos)) return original.call(pos);
        var address = access.address(pos);
        if (Anomaly.mask(
                access.shape(address.owner()),
                GroundFlightServer.mask(level),
                access.cell(address.owner())))
            return net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState();
        if (level.isClientSide
                && !level.getChunkSource()
                        .hasChunk(address.owner().getX() >> 4, address.owner().getZ() >> 4)) {
            var cached = GroundSeams.clientState(level, address.owner());
            if (cached != null) return address.read(cached);
            if (!pos.equals(address.owner()))
                return net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState();
        }
        if (ArrivalProbe.ENABLED)
            ArrivalProbe.missingRead(
                    (net.minecraft.server.level.ServerLevel) level, pos, address.owner());
        return address.read(original.call(address.owner()));
    }

    @WrapMethod(method = "getFluidState")
    private net.minecraft.world.level.material.FluidState starfold$nativeFluid(
            BlockPos pos, Operation<net.minecraft.world.level.material.FluidState> original) {
        var level = (Level) (Object) this;
        return GroundServer.is(level) && (level.isClientSide || PlanetAccess.of(level).inChart(pos))
                ? level.getBlockState(pos).getFluidState()
                : original.call(pos);
    }

    @WrapMethod(method = "getBlockEntity")
    private net.minecraft.world.level.block.entity.BlockEntity starfold$publicEntity(
            BlockPos pos, Operation<net.minecraft.world.level.block.entity.BlockEntity> original) {
        var level = (Level) (Object) this;
        var access = PlanetAccess.of(level);
        if (!GroundServer.canonical(level) || !access.inChart(pos)) return original.call(pos);
        var owner = access.address(pos).owner();
        var be = original.call(owner);
        return be != null || !level.isClientSide ? be : GroundSeams.clientBlockEntity(level, owner);
    }

    @ModifyVariable(method = "removeBlockEntity", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private BlockPos starfold$nativeEntity(BlockPos pos) {
        var level = (Level) (Object) this;
        var access = PlanetAccess.of(level);
        return GroundServer.canonical(level) && access.inChart(pos)
                ? access.address(pos).owner()
                : pos;
    }

    @WrapMethod(method = "getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I")
    private int starfold$nativeHeight(
            net.minecraft.world.level.levelgen.Heightmap.Types type,
            int x,
            int z,
            Operation<Integer> original) {
        var level = (Level) (Object) this;
        var access = PlanetAccess.of(level);
        if (!GroundServer.canonical(level) || !access.inChart(new BlockPos(x, 128, z)))
            return original.call(type, x, z);
        var p = access.address(new BlockPos(x, 128, z)).owner();
        return original.call(type, p.getX(), p.getZ());
    }

    @WrapMethod(
            method =
                    "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean starfold$nativeWrite(
            BlockPos pos, BlockState state, int flags, int depth, Operation<Boolean> original) {
        var level = (Level) (Object) this;
        if (GroundServer.is(level) && level.isClientSide)
            return !GroundSeams.clientMasked(level, pos) && original.call(pos, state, flags, depth);
        var access = PlanetAccess.of(level);
        if (!GroundServer.is(level) || !access.inChart(pos))
            return original.call(pos, state, flags, depth);
        var address = access.address(pos);
        if (Anomaly.mask(
                access.shape(address.owner()),
                GroundFlightServer.mask(level),
                access.cell(address.owner()))) return false;
        return original.call(address.owner(), address.write(state), flags, depth);
    }
}
