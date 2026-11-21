package dev.starfold.devtools.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.api.RenderWorkDiagnostics;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ShaderInstance.class)
public abstract class ShaderApplyMixin {
    @WrapMethod(method = "apply")
    private void sfprofile$apply(Operation<Void> original) {
        long start = RenderWorkDiagnostics.start();
        RenderWorkDiagnostics.add(RenderWorkDiagnostics.Metric.SHADER_APPLY_CALLS, 1);
        try { original.call(); }
        finally { RenderWorkDiagnostics.finish(RenderWorkDiagnostics.Metric.SHADER_APPLY_NS, start); }
    }
}
