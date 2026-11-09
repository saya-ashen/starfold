package dev.starfold.universe;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.*;
import dev.starfold.worldgen.plan.terrain.TerrainRecipe;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.biome.Biome;
/** A data-pack recipe plus registry-resolved biome references. */
public record PlanetTypeTerrain(TerrainRecipe recipe, List<Holder<Biome>> biomes) {
    public PlanetTypeTerrain {
        biomes = List.copyOf(biomes);
    }
    public static final Codec<PlanetTypeTerrain> CODEC = new Codec<>() {
        public <T> DataResult<Pair<PlanetTypeTerrain, T>> decode(DynamicOps<T> ops, T input) {
            return TerrainRecipe.CODEC.decode(ops, input).flatMap(pair -> {
                if (!(ops instanceof RegistryOps<?> registry))
                    return DataResult.error(() -> "Planet type requires biome registries");
                try {
                    var lookup = registry.getter(Registries.BIOME).orElseThrow();
                    var palette = pair.getFirst()
                                      .biomeIds()
                                      .stream()
                                      .sorted(Comparator.comparing(ResourceLocation::toString))
                                      .map(id
                                          -> (Holder<Biome>) lookup.getOrThrow(
                                              ResourceKey.create(Registries.BIOME, id)))
                                      .toList();
                    return DataResult.success(
                        Pair.of(new PlanetTypeTerrain(pair.getFirst(), palette), pair.getSecond()));
                } catch (RuntimeException e) {
                    return DataResult.error(() -> e.getMessage());
                }
            });
        }
        public <T> DataResult<T> encode(PlanetTypeTerrain value, DynamicOps<T> ops, T prefix) {
            return TerrainRecipe.CODEC.encode(value.recipe, ops, prefix);
        }
    };
}
