package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.flight.GroundFlightServer;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;

import org.spongepowered.asm.mixin.Mixin;

/** Capability providers receive the position, state and access side in their own chart. */
@Mixin(value = BlockCapability.class, remap = false)
public abstract class GroundBlockCapabilityMixin {
    @WrapMethod(method = "getCapability")
    private Object starfold$ownerCapability(Level level, BlockPos pos, BlockState state,
            BlockEntity entity, Object context, Operation<Object> original) {
        var capability = (BlockCapability<?, ?>) (Object) this;
        if (!PlanetLevels.canonical(level) || capability.contextClass() != Direction.class)
            return original.call(level, pos, state, entity, context);
        var access = PlanetAccess.of(level);
        var local = access.localBlock(pos);
        if (local == null) return original.call(level, pos, state, entity, context);
        var address = local.address();
        var owner = address.owner();
        if (Anomaly.mask(access.shape(owner), GroundFlightServer.mask(level), access.cell(owner)))
            return null;
        if (owner.equals(pos)) return original.call(level, pos, state, entity, context);
        if (!level.isLoaded(owner)) return null;
        var side = context instanceof Direction direction ? address.ownerDirection(direction) : context;
        return original.call(level, owner, null, null, side);
    }
}
