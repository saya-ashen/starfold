package dev.starfold.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
@Mixin(ServerLevel.class)
public abstract class NativeGroundBlockEventMixin {
    @ModifyVariable(method="blockEvent",at=@At("HEAD"),argsOnly=true,ordinal=0)
    private BlockPos starfold$nativeEventOwner(BlockPos pos){var level=(ServerLevel)(Object)this;var access=NativePlanetAccess.of(level);return NativeGroundServer.is(level)&&access.inChart(pos)?access.address(pos).owner():pos;}
    @WrapMethod(method="doBlockEvent")
    private boolean starfold$nativeEvent(BlockEventData event,Operation<Boolean> original){boolean success=original.call(event);var level=(ServerLevel)(Object)this;if(success&&NativeGroundServer.is(level))NativeGroundViewServer.event(level,event);return success;}
}
