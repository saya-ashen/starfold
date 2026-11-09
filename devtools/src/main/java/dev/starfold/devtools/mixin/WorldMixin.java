package dev.starfold.devtools.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.starfold.devtools.Capture;
import dev.starfold.devtools.Profiler;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GameRenderer.class)
public abstract class WorldMixin {
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"), require = 1)
    private void sfprofile$level(LevelRenderer level, DeltaTracker delta, boolean outline, Camera camera,
            GameRenderer renderer, LightTexture light, Matrix4f view, Matrix4f projection, Operation<Void> original) {
        Profiler.begin(Capture.LEVEL);
        try { original.call(level, delta, outline, camera, renderer, light, view, projection); }
        finally { Profiler.end(Capture.LEVEL); }
    }
}
