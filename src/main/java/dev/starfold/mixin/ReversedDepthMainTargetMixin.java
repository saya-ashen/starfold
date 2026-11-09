package dev.starfold.mixin;

import com.mojang.blaze3d.pipeline.MainTarget;

import dev.starfold.render.ReversedDepth;

import org.lwjgl.opengl.GL30;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** MainTarget's first allocation bypasses RenderTarget.createBuffers. */
@Mixin(MainTarget.class)
public abstract class ReversedDepthMainTargetMixin {
    @ModifyArg(
            method = "allocateDepthAttachment(Lcom/mojang/blaze3d/pipeline/MainTarget$Dimension;)Z",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_texImage2D(IIIIIIIILjava/nio/IntBuffer;)V"),
            index = 2)
    private int starfold$depthFormat(int format) {
        return ReversedDepth.ACTIVE ? GL30.GL_DEPTH_COMPONENT32F : format;
    }
}
