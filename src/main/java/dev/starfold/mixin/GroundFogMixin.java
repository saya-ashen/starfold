package dev.starfold.mixin;

import com.mojang.blaze3d.systems.RenderSystem;

import dev.starfold.chart.GroundSolar;
import dev.starfold.planet.PlanetLevels;
import dev.starfold.render.GroundAtmosphere;
import dev.starfold.render.GroundClient;
import dev.starfold.render.RendererGuard;
import dev.starfold.sky.AtmosphereSettings;
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
        if (!RendererGuard.pipelineSupported()
                || dev.starfold.render.RendererGuard.submerged(camera))
            return;
        if (PlanetLevels.is(world) && SpaceClient.present()) {
            var gas = dev.starfold.render.GasAtmosphereView.layer(camera);
            fogRed = gas == null ? 0 : (float) gas.color().x();
            fogGreen = gas == null ? 0 : (float) gas.color().y();
            fogBlue = gas == null ? 0 : (float) gas.color().z();
            RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0);
            return;
        }
        if (!PlanetLevels.is(world) || !GroundClient.ready()) return;
        var sky =
                GroundAtmosphere.enabled()
                        ? GroundAtmosphere.fog()
                        : GroundSolar.sky(GroundClient.elevation(), GroundClient.darkness());
        fogRed = (float) sky.x();
        fogGreen = (float) sky.y();
        fogBlue = (float) sky.z();
        RenderSystem.clearColor(fogRed, fogGreen, fogBlue, 0);
    }

    @Inject(method = "setupFog", at = @At("TAIL"))
    private static void starfold$atmosphereFog(
            Camera camera,
            FogRenderer.FogMode mode,
            float distance,
            boolean thick,
            float partial,
            CallbackInfo ci) {
        if (!RendererGuard.pipelineSupported()
                || dev.starfold.render.RendererGuard.submerged(camera)
                || thick) return;
        var entity = camera.getEntity();
        if (entity instanceof net.minecraft.world.entity.LivingEntity living
                && (living.hasEffect(net.minecraft.world.effect.MobEffects.BLINDNESS)
                        || living.hasEffect(net.minecraft.world.effect.MobEffects.DARKNESS)))
            return;
        var gas = dev.starfold.render.GasAtmosphereView.layer(camera);
        if (gas != null) {
            RenderSystem.setShaderFogStart(0);
            RenderSystem.setShaderFogEnd(gas.fogEnd());
            return;
        }
        // The LOD surface reaches beyond the simulated chunk window. Preserve water, lava,
        // status-effect and gas fog (handled above); only extend ordinary terrain air fog.
        if (mode == FogRenderer.FogMode.FOG_TERRAIN
                && GroundClient.ready()
                && !SpaceClient.present()) {
            float reach = dev.starfold.render.GroundTransition.surfaceView(GroundClient.view());
            RenderSystem.setShaderFogStart(Math.max(RenderSystem.getShaderFogStart(), reach * .75f));
            RenderSystem.setShaderFogEnd(Math.max(RenderSystem.getShaderFogEnd(), reach));
        }
        if (!GroundAtmosphere.enabled()) return;
        double strength = GroundAtmosphere.strength();
        if (strength < AtmosphereSettings.FOG_DENSITY_FLOOR) {
            RenderSystem.setShaderFogStart(1e9f);
            RenderSystem.setShaderFogEnd(2e9f);
        } else {
            float scale = (float) (1 / strength);
            RenderSystem.setShaderFogStart(RenderSystem.getShaderFogStart() * scale);
            RenderSystem.setShaderFogEnd(RenderSystem.getShaderFogEnd() * scale);
        }
    }
}
