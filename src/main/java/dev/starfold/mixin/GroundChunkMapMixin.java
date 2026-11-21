package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.starfold.api.ChunkWorkDiagnostics;
import dev.starfold.flight.GroundFlightServer;
import dev.starfold.ground.GroundSpawning;
import dev.starfold.planet.PlanetLevels;

import net.minecraft.server.level.*;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.ChunkPos;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ChunkMap.class)
public abstract class GroundChunkMapMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private BlockableEventLoop<Runnable> mainThreadExecutor;
    @Shadow @Final private it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> entityMap;

    @WrapMethod(method = "lambda$prepareAccessibleChunk$32")
    private void starfold$dispatchAccessibleChunk(ChunkHolder holder, Runnable callback, Operation<Void> original) {
        if (!PlanetLevels.is(level)) {
            original.call(holder, callback);
            return;
        }
        // The center and its neighbors are ready. This callback only publishes their result.
        var trace = ChunkWorkDiagnostics.listener();
        if (trace != null)
            trace.record(ChunkWorkDiagnostics.Stage.FULL_DIRECT_SUBMITTED, holder.getPos().toLong(), System.nanoTime(), 0);
        mainThreadExecutor.tell(callback);
    }

    @Inject(method = "tick()V", at = @At("TAIL"))
    private void starfold$refresh(CallbackInfo ci) {
        if (PlanetLevels.is(level) && level.getGameTime() % 5 == 0)
            for (var tracker : entityMap.values())
                ((TrackedAccess) tracker).starfold$updateObservers(level.players());
    }

    /** A vessel far above a planet loads none of it: its place over the ground sweeps whole faces as it passes, and what
     *  is seen from there is drawn from the far view. Chunks load again as it comes down to land. */
    @Inject(method = "skipPlayer", at = @At("HEAD"), cancellable = true)
    private void starfold$aloft(ServerPlayer player, CallbackInfoReturnable<Boolean> ci) {
        if (PlanetLevels.is(level) && GroundFlightServer.aloft(player)) ci.setReturnValue(true);
    }

    @Inject(method = "anyPlayerCloseEnoughForSpawning", at = @At("HEAD"), cancellable = true)
    private void starfold$aliasTick(ChunkPos pos, CallbackInfoReturnable<Boolean> ci) {
        if (PlanetLevels.canonical(level))
            ci.setReturnValue(
                    level.players().stream()
                            .anyMatch(player -> starfold$spawnObserver(player, pos)));
    }

    // LocalMobCapCalculator needs the same observer list as the chunk ticking gate. The vanilla
    // distance-manager index only knows storage coordinates, so adjacent faces otherwise get no
    // cap.
    @Inject(method = "getPlayersCloseForSpawning", at = @At("HEAD"), cancellable = true)
    private void starfold$spawnObservers(
            ChunkPos pos, CallbackInfoReturnable<java.util.List<ServerPlayer>> ci) {
        if (PlanetLevels.canonical(level))
            ci.setReturnValue(
                    level.players().stream()
                            .filter(player -> starfold$spawnObserver(player, pos))
                            .toList());
    }

    @Unique
    private boolean starfold$spawnObserver(ServerPlayer player, ChunkPos pos) {
        return !player.isSpectator()
                && !GroundFlightServer.aloft(player)
                && GroundSpawning.close(
                        dev.starfold.planet.PlanetAccess.of(level),
                        player.getX(),
                        player.getZ(),
                        pos);
    }
}
