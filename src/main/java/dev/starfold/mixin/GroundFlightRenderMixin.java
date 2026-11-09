package dev.starfold.mixin;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.starfold.flight.GroundFlightClient;
import dev.starfold.render.GroundClient;
import dev.starfold.space.SpaceClient;
import dev.starfold.space.SpaceView;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.*;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(GameRenderer.class)
public abstract class GroundFlightRenderMixin {
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
                                    dev.starfold.render.GroundTransition.surfaceView(GroundClient.view()))));
            if (GroundClient.ready())
                ci.setReturnValue(Math.max(ci.getReturnValue(), dev.starfold.render.GroundTransition.depthFar(
                        GroundClient.eye().y(),
                        dev.starfold.render.GroundTransition.surfaceView(GroundClient.view()),
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
