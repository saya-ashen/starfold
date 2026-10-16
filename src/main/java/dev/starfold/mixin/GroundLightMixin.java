package dev.starfold.mixin;

import dev.starfold.flight.GroundFlightClient;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.render.GroundClient;
import dev.starfold.space.SpaceClient;

import net.minecraft.client.multiplayer.ClientLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native block/hand skylight uses the same physical Sun; vanilla block light stays intact. */
@Mixin(ClientLevel.class)
public abstract class GroundLightMixin {
    @Inject(method = "getSkyDarken", at = @At("HEAD"), cancellable = true)
    private void starfold$nativeSun(float partial, CallbackInfoReturnable<Float> ci) {
        if (SpaceClient.active()) {
            ci.setReturnValue((float) (.2 + .8 * SpaceClient.sunlight()));
            return;
        }
        if (PlanetLevels.is((ClientLevel) (Object) this) && GroundClient.ready())
            ci.setReturnValue(
                    (float)
                            (.2
                                    + .8
                                            * (GroundFlightClient.controlled()
                                                    ? GroundClient.sunlight()
                                                    : GroundClient.daylight())));
    }
}
