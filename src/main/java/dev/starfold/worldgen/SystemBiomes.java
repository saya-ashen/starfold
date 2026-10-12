package dev.starfold.worldgen;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.starfold.planet.PlanetStorage;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;

import java.util.stream.Stream;

/** Routes actual biome storage, not merely the sky tint, to the owning planet. */
public final class SystemBiomes extends BiomeSource {
    public static final MapCodec<SystemBiomes> CODEC =
            RecordCodecBuilder.mapCodec(
                    i ->
                            i.group(
                                            BiomeSource.CODEC
                                                    .listOf()
                                                    .fieldOf("planets")
                                                    .forGetter(b -> b.planets))
                                    .apply(i, SystemBiomes::new));
    private final java.util.List<BiomeSource> planets;

    public SystemBiomes(java.util.List<BiomeSource> planets) {
        if (planets.isEmpty()) throw new IllegalArgumentException("System biomes");
        this.planets = java.util.List.copyOf(planets);
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return planets.stream().flatMap(p -> p.possibleBiomes().stream()).distinct();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        long key = PlanetStorage.key(Math.floorDiv(x, 4), Math.floorDiv(z, 4));
        // Gaps and halos own nothing; they read as the first planet's, as they always have.
        int planet =
                key < 0
                        ? 0
                        : Math.min(
                                planets.size() - 1, (int) (key / PlanetStorage.CHUNKS_PER_PLANET));
        return planets.get(planet)
                .getNoiseBiome(
                        x - PlanetStorage.column(planet) * (PlanetStorage.PLANET_STRIDE / 4),
                        y,
                        z - PlanetStorage.row(planet) * (PlanetStorage.ROW_STRIDE / 4),
                        sampler);
    }
}
