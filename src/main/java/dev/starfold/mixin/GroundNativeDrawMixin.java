package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.VertexBuffer;

import dev.starfold.render.GroundNativeCoverage;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
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

    @WrapMethod(method = "renderSectionLayer")
    private void starfold$sampleTerrain(RenderType layer, double x, double y, double z,
            org.joml.Matrix4f view, org.joml.Matrix4f projection, Operation<Void> original) {
        dev.starfold.render.GroundEdgeFilter.beginNative(layer);
        try {
            original.call(layer, x, y, z, view, projection);
        } catch (RuntimeException | Error failure) {
            dev.starfold.render.GroundEdgeFilter.endNative();
            throw failure;
        }
        // Opaque LOD must test against native per-sample depth before resolve at AFTER_ENTITIES.
    }

    @WrapOperation(
            method = "renderSectionLayer",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexBuffer;draw()V"))
    private void starfold$nativeDraw(
            VertexBuffer buffer,
            Operation<Void> original,
            @Local SectionRenderDispatcher.RenderSection section,
            @Local(argsOnly = true) RenderType layer) {
        original.call(buffer);
        GroundNativeCoverage.drawn(section, layer);
    }
}
