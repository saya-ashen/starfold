package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.starfold.dev.ArrivalProbe;

import net.minecraft.server.MinecraftServer;

import org.spongepowered.asm.mixin.Mixin;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class ArrivalTickMixin {
    @WrapMethod(method = "tickServer")
    private void starfold$arrivalTick(BooleanSupplier time, Operation<Void> original) {
        if (!ArrivalProbe.ENABLED) {
            long start = dev.starfold.dev.RelightProbe.ENABLED ? System.nanoTime() : 0;
            try {
                original.call(time);
            } finally {
                dev.starfold.dev.RelightProbe.tick(
                        ((MinecraftServer) (Object) this).getTickCount(), start);
            }
            return;
        }
        ArrivalProbe.tickStart();
        long start = ArrivalProbe.start();
        try {
            original.call(time);
        } finally {
            ArrivalProbe.tickEnd(((MinecraftServer) (Object) this).getTickCount(), start);
        }
    }
}
