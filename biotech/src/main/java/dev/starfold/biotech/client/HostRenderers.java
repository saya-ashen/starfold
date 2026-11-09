package dev.starfold.biotech.client;

import dev.starfold.biotech.host.HostEntities;
import dev.starfold.biotech.host.HostEntity;
import dev.starfold.biotech.host.HostRules;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.layer.AutoGlowingGeoLayer;

@EventBusSubscriber(modid = "starfold_biotech", bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class HostRenderers {
    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        // Geometry is authored in pixels; GeckoLib already converts 16 pixels to one block.
        event.registerEntityRenderer(HostEntities.HOST.get(), context -> {
            var renderer = new GeoEntityRenderer<HostEntity>(context, new Model<>("host"));
            renderer.withScale(HostRules.SCALE);
            renderer.addRenderLayer(new AutoGlowingGeoLayer<>(renderer));
            return renderer;
        });
        event.registerEntityRenderer(HostEntities.SAC.get(), context -> new GeoEntityRenderer<>(context, new Model<>("host_sac")));
        event.registerEntityRenderer(HostEntities.DRONE.get(), context -> new GeoEntityRenderer<>(context, new Model<>("host_drone")));
        event.registerEntityRenderer(HostEntities.ACID.get(), net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
    }

    private static final class Model<T extends Mob & GeoEntity> extends GeoModel<T> {
        private final String name;
        Model(String name) { this.name = name; }
        private ResourceLocation path(String path) { return ResourceLocation.fromNamespaceAndPath("starfold_biotech", path); }
        @Override public ResourceLocation getModelResource(T entity) { return path("geo/" + name + ".geo.json"); }
        @Override public ResourceLocation getTextureResource(T entity) { return path("textures/entity/" + name + ".png"); }
        @Override public ResourceLocation getAnimationResource(T entity) { return path("animations/" + name + ".animation.json"); }
        @Override public void setCustomAnimations(T entity, long id, AnimationState<T> state) {
            super.setCustomAnimations(entity, id, state);
            if (entity instanceof HostEntity host) {
                getBone("shell").ifPresent(bone -> bone.setHidden(host.phase() >= HostEntity.TAKEOFF));
                getBone("roots").ifPresent(bone -> bone.setHidden(host.phase() >= HostEntity.TAKEOFF));
                getBone("hanging_roots").ifPresent(bone -> bone.setHidden(host.phase() < HostEntity.TAKEOFF));
                getBone("thrusters").ifPresent(bone -> bone.setHidden(host.phase() < HostEntity.TAKEOFF));
                for (String side : new String[]{"l", "r"}) {
                    getBone("blaze_" + side).ifPresent(bone -> bone.setHidden(host.phase() < HostEntity.TAKEOFF));
                }
            }
        }
    }
    private HostRenderers() {}
}
