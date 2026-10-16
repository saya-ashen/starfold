package dev.starfold.universe;

import dev.starfold.planet.PlanetLevels;

import net.minecraft.core.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;

/** Bake prefers datapack stems; keep explicit saved generator choices, never upgrade defaults. */
public final class PlanetWorldPreset {
    public static Registry<LevelStem> registryFor(
            WorldDimensions dimensions, Registry<LevelStem> original) {
        var key =
                ResourceKey.create(
                        net.minecraft.core.registries.Registries.LEVEL_STEM,
                        PlanetLevels.PLANET.location());
        var choice = dimensions.dimensions().get(key);
        if (choice == null
                || !(choice.generator() instanceof SystemChunkGenerator)
                || original.get(key) == choice) return original;
        var copy = new MappedRegistry<LevelStem>(original.key(), original.registryLifecycle());
        for (var entry : original.entrySet())
            Registry.register(
                    copy, entry.getKey(), entry.getKey().equals(key) ? choice : entry.getValue());
        if (!original.containsKey(key)) Registry.register(copy, key, choice);
        return copy.freeze();
    }

    private PlanetWorldPreset() {}
}
