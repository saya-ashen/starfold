package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.probe.ArrivalClientState;
import dev.starfold.probe.ArrivalProbe;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;

@Mixin(Minecraft.class)
public abstract class ArrivalFrameMixin {
    @WrapMethod(method = "runTick")
    private void starfold$arrivalFrame(boolean render, Operation<Void> original) {
        if (!ArrivalProbe.ENABLED) {
            original.call(render);
            return;
        }
        long start = ArrivalProbe.start();
        ArrivalClientState.begin();
        try {
            original.call(render);
        } finally {
            ArrivalClientState.end(start);
        }
    }
}
