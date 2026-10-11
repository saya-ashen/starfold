package dev.starfold;

import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;

@Mod(value="starfold",dist=Dist.CLIENT)
public final class LocalSimulationClient {
    public LocalSimulationClient(net.neoforged.bus.api.IEventBus modBus) {
        DevProbe.initialize();
        LocalCapClient.initialize();
        OrbitFlightControls.initialize(modBus);
        NativeGroundClient.initialize(modBus);
        NativeAirstairRenderer.initialize(modBus);NativeAircraftDockRenderer.initialize(modBus);
        modBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event)->event.registerBlockEntityRenderer(thrusterType(),context->new NativeThrusterRenderer()));
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent event)->event.registerSpriteSet(NativeSpaceDust.TYPE.get(),sprites->(type,level,x,y,z,dx,dy,dz)->new NativeSpaceDustParticle(level,x,y,z,sprites)));
    }

    @SuppressWarnings("unchecked")
    private static net.minecraft.world.level.block.entity.BlockEntityType<NativeThruster.Thruster> thrusterType(){return (net.minecraft.world.level.block.entity.BlockEntityType<NativeThruster.Thruster>)net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.get(net.minecraft.resources.ResourceLocation.parse("starfold:thruster"));}
}
