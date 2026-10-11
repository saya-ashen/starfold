package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.NativeArrivalProbe;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class NativeArrivalTickMixin {
    @WrapMethod(method="tickServer")
    private void starfold$arrivalTick(BooleanSupplier time,Operation<Void> original){if(!NativeArrivalProbe.ENABLED){long start=dev.starfold.NativeRelightProbe.ENABLED?System.nanoTime():0;try{original.call(time);}finally{dev.starfold.NativeRelightProbe.tick(((MinecraftServer)(Object)this).getTickCount(),start);}return;}NativeArrivalProbe.tickStart();long start=NativeArrivalProbe.start();try{original.call(time);}finally{NativeArrivalProbe.tickEnd(((MinecraftServer)(Object)this).getTickCount(),start);}}
}
