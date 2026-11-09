package dev.starfold.devtools.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.starfold.devtools.Capture;
import dev.starfold.devtools.Profiler;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelRenderer.class)
public abstract class LevelMixin {
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSectionLayer(Lnet/minecraft/client/renderer/RenderType;DDDLorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"), require = 7)
    private void sfprofile$chunks(LevelRenderer renderer, RenderType layer, double x, double y, double z,
            Matrix4f view, Matrix4f projection, Operation<Void> original) {
        Profiler.begin(Capture.CHUNKS);
        try { original.call(renderer, layer, x, y, z, view, projection); }
        finally { Profiler.end(Capture.CHUNKS); }
    }
}
