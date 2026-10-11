package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The scope excludes GUI and the first-person hand and closes even if rendering throws. */
@Mixin(GameRenderer.class)
public abstract class LocalCapRenderMixin {
    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
    private void starfold$capFrame(LevelRenderer renderer,DeltaTracker time,boolean outline,Camera camera,
            GameRenderer game,LightTexture light,Matrix4f view,Matrix4f projection,Operation<Void> original) {
        try {
            dev.starfold.NativeGroundClient.begin(camera,time.getGameTimeDeltaPartialTick(false),view);
            original.call(renderer,time,outline,camera,game,light,view,projection);
        } finally {
            dev.starfold.NativeGroundClient.end();
        }
    }
}
