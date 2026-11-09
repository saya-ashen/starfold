package dev.starfold.mixin;

import net.minecraft.client.renderer.culling.Frustum;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bounded camera-cube back-off ({@link dev.starfold.render.FrustumOffset}); a miss keeps the frustum and logs it once. */
@Mixin(Frustum.class)
public abstract class FrustumOffsetGuardMixin {
    @Shadow @Final private FrustumIntersection intersection;
    @Shadow @Final private Matrix4f matrix;
    @Shadow private Vector4f viewVector;
    @Shadow private double camX;
    @Shadow private double camY;
    @Shadow private double camZ;
    @Unique private static boolean starfold$reported;

    @Inject(method = "offsetToFullyIncludeCameraCube", at = @At("HEAD"), cancellable = true)
    private void starfold$boundedOffset(int step, CallbackInfoReturnable<Frustum> ci) {
        var camera = dev.starfold.render.FrustumOffset.offset(intersection, viewVector, camX, camY, camZ, step);
        if (camera != null) {
            camX = camera[0]; camY = camera[1]; camZ = camera[2];
        } else if (!starfold$reported) {
            starfold$reported = true;
            org.slf4j.LoggerFactory.getLogger("starfold-frustum").warn(
                    "Camera cube never fit the frustum; offset skipped. camera=({}, {}, {}) view={} matrix={}",
                    camX, camY, camZ, viewVector, matrix);
        }
        ci.setReturnValue((Frustum) (Object) this);
    }
}
