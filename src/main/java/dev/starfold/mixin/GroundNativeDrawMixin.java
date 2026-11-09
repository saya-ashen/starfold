package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexBuffer;

import dev.starfold.render.GroundNativeCoverage;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A visible-list entry is not a draw: EMPTY sections are in that list too. */
@Mixin(LevelRenderer.class)
public abstract class GroundNativeDrawMixin {
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

    @Inject(method = "setupRender", at = @At("HEAD"))
    private void starfold$nativeSetupRender(
            net.minecraft.client.Camera camera,
            net.minecraft.client.renderer.culling.Frustum frustum,
            boolean hasCapturedFrustum,
            boolean spectator,
            CallbackInfo ci) {
        GroundNativeCoverage.setupRender();
    }

    @WrapOperation(
            method = "renderSectionLayer",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V"))
    private void starfold$nativeDraw(
            VertexBuffer buffer,
            Operation<Void> original,
            @Local SectionRenderDispatcher.RenderSection section) {
        original.call(buffer);
        GroundNativeCoverage.drawn(section);
    }
}
