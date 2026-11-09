package dev.starfold.mixin;

import dev.starfold.planet.PlanetLevels;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.worldgen.plan.PlanetPlanStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the loading screen up until home is ready, before any player can join. */
@Mixin(MinecraftServer.class)
public abstract class PlanetPrepareMixin {
    @Inject(method = "prepareLevels", at = @At("HEAD"))
    private void starfold$prepareHome(ChunkProgressListener progress, CallbackInfo ci) {
        var level = ((MinecraftServer) (Object) this).getLevel(PlanetLevels.PLANET);
        if (level != null && level.getChunkSource().getGenerator() instanceof SystemChunkGenerator system)
            PlanetPlanStore.prepareHome(system);
    }
}
