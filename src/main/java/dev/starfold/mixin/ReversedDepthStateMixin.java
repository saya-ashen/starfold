package dev.starfold.mixin;

import com.mojang.blaze3d.platform.GlStateManager;

import dev.starfold.render.ReversedDepth;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(GlStateManager.class)
public abstract class ReversedDepthStateMixin {
    @Redirect(
            method = "_depthFunc(I)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glDepthFunc(I)V", remap = false))
    private static void starfold$depthFunc(int function) {
        GL11.glDepthFunc(ReversedDepth.ACTIVE ? ReversedDepth.depthFunc(function) : function);
    }

    @Redirect(
            method = "_clearDepth(D)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glClearDepth(D)V", remap = false))
    private static void starfold$clearDepth(double depth) {
        GL11.glClearDepth(ReversedDepth.ACTIVE ? 1 - depth : depth);
    }


    @Redirect(
            method = "_polygonOffset(FF)V",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glPolygonOffset(FF)V", remap = false))
    private static void starfold$polygonOffset(float factor, float units) {
        GL11.glPolygonOffset(
                ReversedDepth.ACTIVE ? -factor : factor, ReversedDepth.ACTIVE ? -units : units);
    }
}
