package dev.starfold;

import dev.starfold.dev.DevProbe;
import dev.starfold.flight.OrbitFlightControls;
import dev.starfold.render.GroundClient;
import dev.starfold.render.RendererGuard;
import dev.starfold.ship.AircraftDockRenderer;
import dev.starfold.ship.AirstairRenderer;
import dev.starfold.ship.ThrusterRenderer;
import dev.starfold.ship.Thrusters;
import dev.starfold.space.SpaceDust;
import dev.starfold.space.SpaceDustParticle;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = "starfold", dist = Dist.CLIENT)
public final class StarfoldClient {
    public StarfoldClient(net.neoforged.bus.api.IEventBus modBus) {
        DevProbe.initialize();
        RendererGuard.initialize();
        OrbitFlightControls.initialize(modBus);
        GroundClient.initialize(modBus);
        AirstairRenderer.initialize(modBus);
        AircraftDockRenderer.initialize(modBus);
        modBus.addListener(
                (net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers
                                event) ->
                        event.registerBlockEntityRenderer(
                                thrusterType(), context -> new ThrusterRenderer()));
        modBus.addListener(
                (net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent event) ->
                        event.registerSpriteSet(
                                SpaceDust.TYPE.get(),
                                sprites ->
                                        (type, level, x, y, z, dx, dy, dz) ->
                                                new SpaceDustParticle(level, x, y, z, sprites)));
    }

    @SuppressWarnings("unchecked")
    private static net.minecraft.world.level.block.entity.BlockEntityType<Thrusters.Thruster>
            thrusterType() {
        return (net.minecraft.world.level.block.entity.BlockEntityType<Thrusters.Thruster>)
                net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.get(
                        net.minecraft.resources.ResourceLocation.parse("starfold:thruster"));
    }
}
