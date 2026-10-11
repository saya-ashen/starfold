package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.*;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Minecraft.class)
public abstract class NativeArrivalFrameMixin {
    @WrapMethod(method="runTick")
    private void starfold$arrivalFrame(boolean render,Operation<Void> original){if(!NativeArrivalProbe.ENABLED){original.call(render);return;}long start=NativeArrivalProbe.start();NativeArrivalClientProbe.begin();try{original.call(render);}finally{NativeArrivalClientProbe.end(start);}}
}
