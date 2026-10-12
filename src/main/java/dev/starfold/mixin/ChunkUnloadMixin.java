package dev.starfold.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A revived holder no longer belongs to its old unload callback. Vanilla checks save readiness before
 * pendingUnloads identity, so a completed save future with new generation claims can requeue forever,
 * starving the main-thread work that releases those claims during shutdown. */
@Mixin(ChunkMap.class)
public abstract class ChunkUnloadMixin {
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> pendingUnloads;

    @Inject(method = "scheduleUnload", at = @At("HEAD"), cancellable = true)
    private void starfold$currentUnload(long key, ChunkHolder holder, CallbackInfo ci) {
        if (pendingUnloads.get(key) != holder) ci.cancel();
    }
}
