package dev.starfold.mixin;

import dev.starfold.ground.GroundServer;
import dev.starfold.planet.PlanetAccess;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Canonical scheduled position preserves vanilla priority/delay/deduplication and tick saves. */
@Mixin(LevelAccessor.class)
public interface GroundTopologyTickMixin {
    @ModifyVariable(
            method = {
                "createTick(Lnet/minecraft/core/BlockPos;Ljava/lang/Object;I)Lnet/minecraft/world/ticks/ScheduledTick;",
                "createTick(Lnet/minecraft/core/BlockPos;Ljava/lang/Object;ILnet/minecraft/world/ticks/TickPriority;)Lnet/minecraft/world/ticks/ScheduledTick;"
            },
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0)
    private BlockPos starfold$nativeTick(BlockPos pos) {
        return (Object) this instanceof Level level
                        && GroundServer.canonical(level)
                        && PlanetAccess.of(level).inChart(pos)
                ? PlanetAccess.of(level).address(pos).owner()
                : pos;
    }
}
