package dev.starfold.biotech.client;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.starfold.biotech.StarfoldBiotech;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.joml.Vector3f;

@EventBusSubscriber(modid = "starfold_biotech", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BloodRendering {
    @SubscribeEvent
    public static void register(RegisterClientExtensionsEvent event) {
        event.registerFluidType(new IClientFluidTypeExtensions() {
            @Override public ResourceLocation getStillTexture() {
                return ResourceLocation.fromNamespaceAndPath("starfold_biotech", "block/blood_still");
            }
            @Override public ResourceLocation getFlowingTexture() {
                return ResourceLocation.fromNamespaceAndPath("starfold_biotech", "block/blood_flow");
            }
            @Override public int getTintColor() { return 0xFFFFFFFF; }
            @Override public Vector3f modifyFogColor(Camera camera, float partialTick, ClientLevel level,
                    int renderDistance, float darkenWorldAmount, Vector3f fluidFogColor) {
                return new Vector3f(0.17f, 0.015f, 0.025f);
            }
            @Override public void modifyFogRender(Camera camera, FogRenderer.FogMode mode, float renderDistance,
                    float partialTick, float nearDistance, float farDistance, FogShape shape) {
                RenderSystem.setShaderFogStart(0);
                RenderSystem.setShaderFogEnd(Math.min(8, renderDistance));
                RenderSystem.setShaderFogShape(FogShape.SPHERE);
            }
        }, StarfoldBiotech.BLOOD_TYPE.get());
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemBlockRenderTypes.setRenderLayer(StarfoldBiotech.BLOOD.get(), RenderType.translucent());
            ItemBlockRenderTypes.setRenderLayer(StarfoldBiotech.FLOWING_BLOOD.get(), RenderType.translucent());
        });
    }

    private BloodRendering() {}
}
