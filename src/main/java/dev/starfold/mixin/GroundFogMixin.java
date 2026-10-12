package dev.starfold.mixin;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.starfold.ground.GroundServer;
import dev.starfold.render.GroundClient;
import dev.starfold.sky.GroundSolar;
import dev.starfold.space.SpaceClient;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FogRenderer.class)
public abstract class GroundFogMixin {
    @Shadow private static float fogRed;
    @Shadow private static float fogGreen;
    @Shadow private static float fogBlue;

    @Inject(method = "setupColor", at = @At("TAIL"))
    private static void starfold$nativeSky(
            Camera camera,
            float partial,
            ClientLevel world,
            int distance,
            float darken,
            CallbackInfo ci) {
        if (GroundServer.is(world) && SpaceClient.present()) {
            fogRed = fogGreen = fogBlue = 0;
            RenderSystem.clearColor(0, 0, 0, 0);
            return;
        }
        if (!GroundServer.is(world) || !GroundClient.ready()) return;
        var sky = GroundSolar.sky(GroundClient.elevation(), GroundClient.darkness());
        fogRed = (float) sky.x();
        fogGreen = (float) sky.y();
        fogBlue = (float) sky.z();
        RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0);
    }
}
