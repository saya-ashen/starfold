package dev.starfold.mixin;
import dev.starfold.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Canonical scheduled position preserves vanilla priority/delay/deduplication and tick saves. */
@Mixin(LevelAccessor.class)
public interface NativeGroundTopologyTickMixin {
    @ModifyVariable(method={"createTick(Lnet/minecraft/core/BlockPos;Ljava/lang/Object;I)Lnet/minecraft/world/ticks/ScheduledTick;","createTick(Lnet/minecraft/core/BlockPos;Ljava/lang/Object;ILnet/minecraft/world/ticks/TickPriority;)Lnet/minecraft/world/ticks/ScheduledTick;"},at=@At("HEAD"),argsOnly=true,ordinal=0)
    private BlockPos starfold$nativeTick(BlockPos pos){return (Object)this instanceof Level level&&NativeGroundServer.canonical(level)&&NativePlanetAccess.of(level).inChart(pos)?NativePlanetAccess.of(level).address(pos).owner():pos;}
}
