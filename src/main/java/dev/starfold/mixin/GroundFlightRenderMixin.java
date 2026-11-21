package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;

import dev.starfold.flight.GroundFlightClient;
import dev.starfold.render.GroundClient;
import dev.starfold.render.GroundRenderer;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceView;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.*;
import net.minecraft.world.phys.BlockHitResult;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(GameRenderer.class)
public abstract class GroundFlightRenderMixin {
    @Unique private Matrix4f starfold$baseProjection;
    @Unique private Matrix4f starfold$walkingProjection;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void starfold$resetWalkingProjection(CallbackInfo ci) {
        starfold$baseProjection = starfold$walkingProjection = null;
        GroundRenderer.walkingProjection(null, null);
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"))
    private void starfold$captureWalkingProjection(GameRenderer renderer, PoseStack pose, float partial,
            Operation<Void> original, @Local(ordinal = 0) Matrix4f projection) {
        boolean walkingOnly = GroundClient.active() && !GroundFlightClient.controlled()
                && (pose.last().pose().properties() & org.joml.Matrix4fc.PROPERTY_IDENTITY) != 0;
        original.call(renderer, pose, partial);
        if (walkingOnly && GroundRenderer.boundedWalkingPose(pose.last().pose())) {
            starfold$baseProjection = new Matrix4f(projection);
            starfold$walkingProjection = new Matrix4f(projection).mul(pose.last().pose());
        }
    }

    @Inject(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;resetProjectionMatrix(Lorg/joml/Matrix4f;)V",
            shift = At.Shift.AFTER))
    private void starfold$publishWalkingProjection(CallbackInfo ci, @Local(ordinal = 0) Matrix4f projection) {
        if (projection.equals(starfold$walkingProjection))
            GroundRenderer.walkingProjection(starfold$baseProjection, starfold$walkingProjection);
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void starfold$nativeNoBob(PoseStack pose, float partial, CallbackInfo ci) {
        if (GroundFlightClient.controlled()) ci.cancel();
    }

    @Inject(method = "getDepthFar", at = @At("RETURN"), cancellable = true)
    private void starfold$nativeFar(CallbackInfoReturnable<Float> ci) {
        if (SpaceClient.active()) {
            ci.setReturnValue(Math.max(ci.getReturnValue(), SpaceView.FAR));
            return;
        }
        if (GroundClient.active()) {
            ci.setReturnValue(
                    Math.max(
                            ci.getReturnValue(),
                            SpaceView.chartFar(
                                    GroundClient.ready() ? GroundClient.eye().y() : 0,
                                    dev.starfold.render.GroundTransition.surfaceView(
                                            GroundClient.view()))));
            if (GroundClient.ready())
                ci.setReturnValue(
                        Math.max(
                                ci.getReturnValue(),
                                dev.starfold.render.GroundTransition.depthFar(
                                        GroundClient.eye().y(),
                                        dev.starfold.render.GroundTransition.surfaceView(
                                                GroundClient.view()),
                                        GroundClient.shape().radius())));
        }
    }

    @Inject(method = "pick(F)V", at = @At("HEAD"), cancellable = true)
    private void starfold$nativeNoBlockPick(float partial, CallbackInfo ci) {
        if (!GroundFlightClient.controlled()) return;
        var mc = Minecraft.getInstance();
        mc.crosshairPickEntity = null;
        mc.hitResult =
                BlockHitResult.miss(
                        mc.gameRenderer.getMainCamera().getPosition(), Direction.UP, BlockPos.ZERO);
        ci.cancel();
    }
}
