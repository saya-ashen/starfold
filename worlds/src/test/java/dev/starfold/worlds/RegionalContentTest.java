package dev.starfold.worlds;

import dev.starfold.worlds.fixtures.ExperienceFixtures;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biomes;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class RegionalContentTest {
    @Test
    void customBiomesKeepDesertMobsAndDeclareRealRegionalPlantsAndTags() throws Exception {
        dev.starfold.worlds.fixtures.ExperienceFixtures.boot();
        var lookup = dev.starfold.worlds.fixtures.ExperienceFixtures.registryLookup();
        var vanilla = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.DESERT).value();
        var mobs =
                net.minecraft.world.level.biome.MobSpawnSettings.CODEC
                        .codec()
                        .encodeStart(JsonOps.INSTANCE, vanilla.getMobSettings())
                        .getOrThrow();
        for (String name : new String[] {"desert_barren", "desert_scrub"}) {
            var key =
                    ResourceKey.create(
                            Registries.BIOME,
                            ResourceLocation.fromNamespaceAndPath("starfold_worlds", name));
            var biome = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(key).value();
            assertEquals(
                    mobs,
                    net.minecraft.world.level.biome.MobSpawnSettings.CODEC
                            .codec()
                            .encodeStart(JsonOps.INSTANCE, biome.getMobSettings())
                            .getOrThrow());
            var features = biome.getGenerationSettings().features();
            var ids =
                    features.get(9).stream()
                            .map(h -> h.unwrapKey().orElseThrow().location().toString())
                            .toList();
            assertEquals(
                    name.equals("desert_barren")
                            ? java.util.List.of()
                            : java.util.List.of(
                                    "starfold_worlds:regional_cactus", "minecraft:patch_dead_bush_2"),
                    ids);
        }
        for (String name :
                new String[] {
                    "has_structure/desert_pyramid",
                    "has_structure/mineshaft",
                    "has_structure/pillager_outpost",
                    "has_structure/ruined_portal_desert",
                    "has_structure/trial_chambers",
                    "has_structure/village_desert",
                    "is_overworld",
                    "snow_golem_melts",
                    "spawns_gold_rabbits",
                    "spawns_warm_variant_frogs",
                    "stronghold_biased_to"
                }) {
            var json =
                    com.google.gson.JsonParser.parseString(
                                    Files.readString(
                                            Path.of(
                                                    "worlds/src/main/resources/data/minecraft/tags/worldgen/biome/"
                                                            + name
                                                            + ".json")))
                            .getAsJsonObject();
            assertFalse(json.get("replace").getAsBoolean());
            var expected =
                    new java.util.HashSet<>(
                            java.util.Set.of(
                                    "starfold_worlds:desert_barren", "starfold_worlds:desert_scrub"));
            if (java.util.Set.of(
                            "has_structure/trial_chambers", "is_overworld", "stronghold_biased_to")
                    .contains(name))
                expected.addAll(
                        java.util.Set.of("starfold_worlds:lush_glade", "starfold_worlds:lush_jungle"));
            assertEquals(
                    expected,
                    java.util.stream.StreamSupport.stream(
                                    json.getAsJsonArray("values").spliterator(), false)
                            .map(com.google.gson.JsonElement::getAsString)
                            .collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void vanillaDesertReferenceAndPlantPlacementsAreRegistryBacked() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        for (String id : new String[] {"patch_cactus_desert", "patch_dead_bush_2"})
            assertNotNull(
                    lookup.lookupOrThrow(Registries.PLACED_FEATURE)
                            .getOrThrow(
                                    ResourceKey.create(
                                            Registries.PLACED_FEATURE,
                                            ResourceLocation.withDefaultNamespace(id))));
        var desert = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.DESERT).value();
        var json =
                net.minecraft.world.level.biome.MobSpawnSettings.CODEC
                        .codec()
                        .encodeStart(JsonOps.INSTANCE, desert.getMobSettings())
                        .getOrThrow();
        var runs = Files.createDirectories(Path.of("build/g2-regions/test-runs"));
        var dir = Files.createTempDirectory(runs, "vanilla-desert-");
        Files.writeString(
                dir.resolve("desert-mobs.json"),
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n");
        assertEquals(2, desert.getBaseTemperature());
    }
}
