package dev.starfold.mixin;

import dev.starfold.sky.GroundDaylight;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.dimension.DimensionType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** A bed on a planet may be slept in, though the planets' dimension is not a natural one. */
@Mixin(ServerPlayer.class)
public abstract class GroundBedMixin {
    @Redirect(
            method = "lambda$startSleepInBed$13",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/dimension/DimensionType;natural()Z"))
    private boolean starfold$planetBed(DimensionType type) {
        var player = (ServerPlayer) (Object) this;
        return type.natural() || GroundDaylight.kept(player.level(), player.blockPosition());
    }
}
