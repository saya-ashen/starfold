package dev.starfold.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dev.starfold.planet.PlanetLevels;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class GroundEntityPositionMixin {
    // NeoForge's added position load blocks flight proxies in deliberately unloaded native charts.
    // Restore vanilla there; the chunk result is discarded, and every other level keeps the
    // original load.
    @WrapOperation(
            method = "setPosRaw",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk starfold$nativePositionChunk(
            Level level, int x, int z, Operation<LevelChunk> original) {
        return PlanetLevels.is(level) ? null : original.call(level, x, z);
    }
}
