package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;

import org.spongepowered.asm.mixin.Mixin;

import java.util.function.BooleanSupplier;

/** Cache loadedness and invalidation follow the same owner as the provider lookup. */
@Mixin(value = BlockCapabilityCache.class, remap = false)
public abstract class GroundBlockCapabilityCacheMixin {
    @WrapMethod(method = "create(Lnet/neoforged/neoforge/capabilities/BlockCapability;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/lang/Object;Ljava/util/function/BooleanSupplier;Ljava/lang/Runnable;)Lnet/neoforged/neoforge/capabilities/BlockCapabilityCache;")
    private static BlockCapabilityCache<?, ?> starfold$ownerCache(BlockCapability<?, ?> capability,
            ServerLevel level, BlockPos pos, Object context, BooleanSupplier valid,
            Runnable invalidation, Operation<BlockCapabilityCache<?, ?>> original) {
        if (PlanetLevels.canonical(level) && capability.contextClass() == Direction.class) {
            var local = PlanetAccess.of(level).localBlock(pos);
            if (local != null && !local.address().owner().equals(pos)) {
                var address = local.address();
                var side = context instanceof Direction direction ? address.ownerDirection(direction) : context;
                return original.call(capability, level, address.owner(), side, valid, invalidation);
            }
        }
        return original.call(capability, level, pos, context, valid, invalidation);
    }
}
