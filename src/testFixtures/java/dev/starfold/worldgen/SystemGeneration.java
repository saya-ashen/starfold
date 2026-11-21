package dev.starfold.worldgen;

import dev.starfold.planet.PlanetStorage;
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.SystemChunkGenerator;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Test fixtures must use the registered generator even when they hold one planet. */
public final class SystemGeneration {
    public static CompletableFuture<ChunkAccess> fill(ChunkGenerator generator, ChunkAccess chunk) {
        var system =
                generator instanceof PlanetChunkGenerator planet
                        ? new SystemChunkGenerator(PlanetStorage.LAYOUT, List.of(planet))
                        : generator;
        return system.fillFromNoise(null, null, null, chunk);
    }

    private SystemGeneration() {}
}
