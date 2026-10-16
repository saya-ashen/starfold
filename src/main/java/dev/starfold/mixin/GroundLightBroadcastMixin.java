package dev.starfold.mixin;

import dev.starfold.ground.ImageStream;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Image owners need light updates even before they become ticking chunks. */
@Mixin(ServerChunkCache.class)
public abstract class GroundLightBroadcastMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void starfold$nativeLight(LightLayer layer, SectionPos section, CallbackInfo ci) {
        if (PlanetLevels.is(level))
            // Light callbacks can arrive off-thread; the stream's queues belong to the server.
            level.getServer().execute(() -> ImageStream.lit(level, section.chunk()));
    }
}
