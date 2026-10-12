package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.dev.ArrivalClientProbe;
import dev.starfold.dev.ArrivalProbe;

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
        ArrivalClientProbe.begin();
        try {
            original.call(render);
        } finally {
            ArrivalClientProbe.end(start);
        }
    }
}
