package dev.starfold.mixin;

import dev.starfold.render.ReversedDepth;

import net.minecraft.client.renderer.GameRenderer;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;

/** Section layers, the sky and level-stage listeners take this matrix directly, not RenderSystem's. */
@Mixin(GameRenderer.class)
public abstract class ReversedDepthLevelMixin {
    @ModifyArg(
            method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"),
            index = 6)
    private Matrix4f starfold$levelProjection(Matrix4f projection) {
        return ReversedDepth.ACTIVE ? ReversedDepth.projection(projection) : projection;
    }

    @ModifyArg(
            method = "renderLevel",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/ClientHooks;dispatchRenderStage(Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent$Stage;Lnet/minecraft/client/renderer/LevelRenderer;Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;ILnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;)V"),
            index = 4)
    private Matrix4f starfold$afterLevelProjection(Matrix4f projection) {
        return ReversedDepth.ACTIVE ? ReversedDepth.projection(projection) : projection;
    }
}
