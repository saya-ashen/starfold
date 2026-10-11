package dev.starfold.mixin;
import dev.starfold.*;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A night slept through on a planet is skipped by turning the planets on to dawn, as vanilla turns its clock. */
@Mixin(ServerLevel.class)
public abstract class NativeGroundNightSkipMixin {
    @Inject(method="tick",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerLevel;wakeUpAllPlayers()V"))
    private void starfold$dawn(java.util.function.BooleanSupplier more,CallbackInfo ci){NativeGroundDaylight.sleptThrough((ServerLevel)(Object)this);}
}
