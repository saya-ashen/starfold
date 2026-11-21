package dev.starfold.devtools.mixin;

import dev.starfold.api.ChunkWorkDiagnostics;
import dev.starfold.planet.PlanetLevels;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class ClientChunkTimingMixin {
    @Shadow private ClientLevel level;

    @Inject(method = "enableChunkLight", at = @At("RETURN"))
    private void starfold$lightApplied(LevelChunk chunk, int x, int z, CallbackInfo ci) {
        var trace = ChunkWorkDiagnostics.listener();
        if (trace != null && PlanetLevels.is(level))
            trace.record(ChunkWorkDiagnostics.Stage.CLIENT_LIGHT_APPLIED, ChunkPos.asLong(x, z), System.nanoTime(), 0);
    }
}
