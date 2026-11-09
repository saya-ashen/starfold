package dev.starfold.universe;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.starfold.Sources;
import dev.starfold.chart.GroundSolar;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.Anomaly;
import dev.starfold.planet.PlanetCore;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.sky.GroundDaylight;
import dev.starfold.worldgen.PlanetBiomeFixtures;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetMinerals;
import dev.starfold.worldgen.PlanetTerrain;

import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Planet types come from data; a planet made from one is saved with the values it was given. */
public class PlanetTypeFixtures {
    @BeforeAll
    public static void boot() {
        PlanetBiomeFixtures.boot();
    }

    static final Path DATA = Path.of("src/main/resources/data/starfold");
    private static net.minecraft.core.HolderLookup.Provider loaded;

    /** The vanilla registries and the planet types of the mod's own data, as a loaded data pack would give them. */
    public static net.minecraft.core.HolderLookup.Provider lookup() {
        ops();
        return loaded;
    }

    public static synchronized com.mojang.serialization.DynamicOps<JsonElement> ops() {
        if (loaded == null) {
            var base = PlanetBiomeFixtures.lookup;
            var empty =
                    new net.minecraft.core.MappedRegistry<PlanetType>(
                            PlanetType.KEY, com.mojang.serialization.Lifecycle.stable());
            var bare =
                    net.minecraft.resources.RegistryOps.create(
                            JsonOps.INSTANCE,
                            net.minecraft.core.HolderLookup.Provider.create(
                                    java.util.stream.Stream.concat(
                                            base.listRegistries().map(base::lookupOrThrow),
                                            java.util.stream.Stream.of(empty.asLookup()))));
            var types =
                    new net.minecraft.core.MappedRegistry<PlanetType>(
                            PlanetType.KEY, com.mojang.serialization.Lifecycle.stable());
            for (var name :
                    java.util.List.of(
                            "rocky",
                            "desert",
                            "ice",
                            "temperate",
                            "lush",
                            "arid",
                            "archipelago",
                            "steppe"))
                net.minecraft.core.Registry.register(
                        types,
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                "starfold", name),
                        PlanetType.CODEC.parse(bare, typeJson(name)).getOrThrow());
            var frozen = types.freeze();
            var withTypes =
                    net.minecraft.core.HolderLookup.Provider.create(
                            java.util.stream.Stream.concat(
                                    base.listRegistries().map(base::lookupOrThrow),
                                    java.util.stream.Stream.of(frozen.asLookup())));
            var layouts =
                    new net.minecraft.core.MappedRegistry<UniverseLayout>(
                            UniverseLayout.KEY, com.mojang.serialization.Lifecycle.stable());
            var emptyLayouts =
                    new net.minecraft.core.MappedRegistry<UniverseLayout>(
                            UniverseLayout.KEY, com.mojang.serialization.Lifecycle.stable());
            var typed =
                    net.minecraft.resources.RegistryOps.create(
                            JsonOps.INSTANCE,
                            net.minecraft.core.HolderLookup.Provider.create(
                                    java.util.stream.Stream.concat(
                                            withTypes
                                                    .listRegistries()
                                                    .map(withTypes::lookupOrThrow),
                                            java.util.stream.Stream.of(emptyLayouts.asLookup()))));
            try {
                net.minecraft.core.Registry.register(
                        layouts,
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                                "starfold", "default"),
                        UniverseLayout.CODEC
                                .parse(
                                        typed,
                                        JsonParser.parseString(
                                                Files.readString(
                                                        DATA.resolve(
                                                                "starfold/universe/default.json"))))
                                .getOrThrow());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            var frozenLayouts = layouts.freeze();
            loaded =
                    net.minecraft.core.HolderLookup.Provider.create(
                            java.util.stream.Stream.concat(
                                    withTypes.listRegistries().map(withTypes::lookupOrThrow),
                                    java.util.stream.Stream.of(frozenLayouts.asLookup())));
        }
        return net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE, loaded);
    }

    public static JsonObject typeJson(String name) {
        try {
            return JsonParser.parseString(
                            Files.readString(
                                    DATA.resolve("starfold/planet_type/" + name + ".json")))
                    .getAsJsonObject();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static PlanetType type(String name) {
        return PlanetType.CODEC.parse(ops(), typeJson(name)).getOrThrow();
    }

    public static Path presetPath(String name) {
        return switch (name) {
            case "native_system", "native_trio", "native_stars", "native_exploration" ->
                Path.of("probes/src/main/resources/data/starfold/worldgen/world_preset/" + name + ".json");
            default -> DATA.resolve("worldgen/world_preset/" + name + ".json");
        };
    }

    public static JsonElement preset(String name) throws Exception {
        return JsonParser.parseString(
                        Files.readString(presetPath(name)))
                .getAsJsonObject()
                .getAsJsonObject("dimensions")
                .getAsJsonObject("starfold:native_planet")
                .getAsJsonObject("generator");
    }

}
