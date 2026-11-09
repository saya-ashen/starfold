package dev.starfold.worlds.fixtures;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.mojang.serialization.*;

import dev.starfold.Sources;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.ground.GroundPackets;
import dev.starfold.ground.GroundServer;
import dev.starfold.render.PlanetRemotePackets;
import dev.starfold.render.PlanetRemoteView;
import dev.starfold.space.CelestialNavigation;
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.universe.PlanetType;
import dev.starfold.universe.PlanetTypeFixtures;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.universe.SystemDefinition;
import dev.starfold.universe.UniverseLayout;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetHeightCache;
import dev.starfold.worldgen.PlanetTint;

import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

/** Actual registry-aware codecs, not a second handwritten JSON schema. */
public class ExperienceFixtures {
    protected static final Path DATA = Path.of("worlds/src/main/resources/data/starfold_worlds");
    protected static HolderLookup.Provider lookup;
    private static RegistryAccess biomeAccess;

    public static HolderLookup.Provider registryLookup() {
        return lookup;
    }

    public static RegistryAccess biomeRegistryAccess() {
        return biomeAccess;
    }

    public static int previousExperienceSlot(int currentSlot) {
        return new int[] {0, 3, 1, 6, 5, 7, 2, 11, 8, 9, 4, 10}[currentSlot];
    }

    public static int currentExperienceSlot(int previousSlot) {
        return new int[] {0, 2, 6, 1, 10, 4, 3, 5, 8, 9, 11, 7}[previousSlot];
    }

    protected static JsonObject json(Path p) throws Exception {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    protected static void assertNoOrbitPeriods(JsonElement element) {
        if (element.isJsonObject()) {
            var object = element.getAsJsonObject();
            if (object.has("orbit")) assertFalse(object.getAsJsonObject("orbit").has("period"));
            object.asMap().values().forEach(ExperienceFixtures::assertNoOrbitPeriods);
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(ExperienceFixtures::assertNoOrbitPeriods);
        }
    }

    public static DynamicOps<JsonElement> ops() {
        return RegistryOps.create(JsonOps.INSTANCE, lookup);
    }

    static <T> void load(ResourceKey<? extends Registry<T>> key, Codec<T> codec, String dir)
            throws Exception {
        var registry = new MappedRegistry<T>(key, Lifecycle.stable());
        lookup.lookup(key)
                .ifPresent(
                        old ->
                                old.listElements()
                                        .forEach(
                                                h ->
                                                        Registry.register(
                                                                registry, h.key(), h.value())));
        try (var files = Files.list(DATA.resolve(dir))) {
            for (var p : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                var value = codec.parse(ops(), json(p)).getOrThrow();
                Registry.register(
                        registry,
                        ResourceLocation.fromNamespaceAndPath(
                                "starfold_worlds", p.getFileName().toString().replace(".json", "")),
                        value);
            }
        }
        var frozen = registry.freeze();
        if (key.equals(Registries.BIOME))
            biomeAccess = new RegistryAccess.ImmutableRegistryAccess(List.of(frozen));
        var old = lookup;
        lookup =
                HolderLookup.Provider.create(
                        java.util.stream.Stream.concat(
                                old.listRegistries()
                                        .filter(k -> !k.equals(key))
                                        .map(old::lookupOrThrow),
                                java.util.stream.Stream.of(frozen.asLookup())));
    }

    @BeforeAll
    public static void boot() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        load(
                Registries.CONFIGURED_FEATURE,
                ConfiguredFeature.DIRECT_CODEC,
                "worldgen/configured_feature");
        load(Registries.PLACED_FEATURE, PlacedFeature.DIRECT_CODEC, "worldgen/placed_feature");
        load(Registries.BIOME, Biome.DIRECT_CODEC, "worldgen/biome");
        load(PlanetType.KEY, PlanetType.CODEC, "starfold/planet_type");
        load(UniverseLayout.KEY, UniverseLayout.CODEC, "starfold/universe");
        load(SystemDefinition.KEY, SystemDefinition.CODEC, "starfold/system");
    }

    public static SystemChunkGenerator generator(String name) throws Exception {
        var dimensions =
                json(DATA.resolve("worldgen/world_preset/" + name + ".json"))
                        .getAsJsonObject("dimensions");
        for (var id : List.of("minecraft:overworld"))
            assertNotNull(
                    net.minecraft.world.level.dimension.LevelStem.CODEC
                            .parse(ops(), dimensions.get(id))
                            .getOrThrow());
        assertEquals(
                "starfold:native_deep",
                dimensions.getAsJsonObject("starfold:native_planet").get("type").getAsString());
        var generator =
                SystemChunkGenerator.CODEC
                        .codec()
                        .parse(
                                ops(),
                                dimensions
                                        .getAsJsonObject("starfold:native_planet")
                                        .get("generator"))
                        .getOrThrow();
        generator.materialize(42);
        return generator;
    }

}
