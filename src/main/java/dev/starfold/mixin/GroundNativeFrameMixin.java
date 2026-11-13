package dev.starfold.mixin;

import dev.starfold.render.GroundNativeCoverage;

import net.minecraft.client.renderer.LevelRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame start is the one part every renderer keeps: Sodium overwrites the section draw call and
 * the visible-list setup, but still enters renderLevel, and its coverage window has to be ready
 * before the first surface query of the frame.
 */
@Mixin(LevelRenderer.class)
public abstract class GroundNativeFrameMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void starfold$beginNativeFrame(
            net.minecraft.client.DeltaTracker delta,
            boolean outline,
            net.minecraft.client.Camera camera,
            net.minecraft.client.renderer.GameRenderer renderer,
            net.minecraft.client.renderer.LightTexture light,
            org.joml.Matrix4f modelView,
            org.joml.Matrix4f projection,
            CallbackInfo ci) {
        GroundNativeCoverage.beginFrame();
    }
}
