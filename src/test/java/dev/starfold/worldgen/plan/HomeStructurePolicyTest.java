package dev.starfold.worldgen.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;

import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.worldgen.plan.terrain.TerrainRecipe;

import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.StructureSet;

import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

class HomeStructurePolicyTest {
    private static final TagKey<StructureSet> HOME =
            TagKey.create(Registries.STRUCTURE_SET, ResourceLocation.parse("escape_velocity:home"));
    private static final TagKey<StructureSet> EMPTY =
            TagKey.create(Registries.STRUCTURE_SET, ResourceLocation.parse("test:empty"));
    private static final Path HOME_TAG =
            Path.of("modpack/kubejs/data/escape_velocity/tags/worldgen/structure_set/home.json");

    @BeforeAll
    static void boot() throws Exception {
        PlanetPlanFixtures.boot();
        var sets =
                (MappedRegistry<StructureSet>)
                        PlanetPlanFixtures.context
                                .registries()
                                .registryOrThrow(Registries.STRUCTURE_SET);
        var defaults = new ArrayList<Holder<StructureSet>>();
        sets.getTagOrEmpty(SystemChunkGenerator.STRUCTURES).forEach(defaults::add);
        var home = new ArrayList<Holder<StructureSet>>();
        for (var value : homeTag().getAsJsonArray("values"))
            home.add(
                    sets.getHolderOrThrow(
                            ResourceKey.create(
                                    Registries.STRUCTURE_SET,
                                    ResourceLocation.parse(value.getAsString()))));
        sets.bindTags(
                Map.of(SystemChunkGenerator.STRUCTURES, defaults, HOME, home, EMPTY, List.of()));
    }

    @AfterAll
    static void close() throws Exception {
        PlanetPlanFixtures.close();
    }

    private static JsonObject homeTag() throws Exception {
        return JsonParser.parseString(Files.readString(HOME_TAG)).getAsJsonObject();
    }

    private static JsonObject recipeJson() {
        return PlanetPlanFixtures.planet(416, "plains").generation().recipe().json();
    }

    private static PlanetChunkGenerator planet(String tag) {
        var base = PlanetPlanFixtures.planet(416, "plains");
        var json = base.generation().recipe().json();
        json.getAsJsonObject("planner").addProperty("structure_sets", tag);
        json.getAsJsonObject("preferences").addProperty("damage", 0);
        json.getAsJsonObject("preferences").addProperty("burial", 0);
        return new PlanetChunkGenerator(
                base.settings(),
                base.seed(),
                base.version(),
                Optional.empty(),
                Optional.of(base.generation().withRecipe(new TerrainRecipe(json))));
    }

    @Test
    void omittedTagPreservesRecipeJsonAndDefaultSelection() {
        var json = recipeJson();
        var recipe = new TerrainRecipe(json);
        assertTrue(recipe.structureSets().isEmpty());
        assertEquals(
                json,
                recipe.json(),
                "an omitted default must not change saved generation identity");
        assertEquals(json, TerrainRecipe.CODEC.encodeStart(JsonOps.INSTANCE, recipe).getOrThrow());
        assertEquals(recipe.toString(), new TerrainRecipe(recipe.json()).toString());
        var actual =
                new TraditionalPlanetPlanner(
                                PlanetPlanFixtures.planet(416, "plains"),
                                PlanetPlanFixtures.context)
                        .generate();
        var defaults =
                PlanetPlanFixtures.context
                        .registries()
                        .registryOrThrow(Registries.STRUCTURE_SET)
                        .getTagOrEmpty(SystemChunkGenerator.STRUCTURES);
        var expected =
                new TraditionalPlanetPlanner(
                                PlanetPlanFixtures.planet(416, "plains"),
                                PlanetPlanFixtures.context)
                        .generate(defaults);
        assertFalse(actual.plan().sites().isEmpty());
        assertArrayEquals(expected.plan().compressed(), actual.plan().compressed());
        assertEquals(expected.recipes(), actual.recipes());
        assertEquals(expected.skipped(), actual.skipped());
    }

    @Test
    void configuredTagSurvivesConstructorCodecAndCanonicalJson() {
        for (String id :
                List.of("escape_velocity:home", "starfold:planets", "test:custom", "home")) {
            var json = recipeJson();
            json.getAsJsonObject("planner").addProperty("structure_sets", id);
            var recipe = new TerrainRecipe(json);
            assertEquals(Optional.of(ResourceLocation.parse(id)), recipe.structureSets());
            var encoded = TerrainRecipe.CODEC.encodeStart(JsonOps.INSTANCE, recipe).getOrThrow();
            assertEquals(
                    ResourceLocation.parse(id).toString(),
                    encoded.getAsJsonObject()
                            .getAsJsonObject("planner")
                            .get("structure_sets")
                            .getAsString());
            var decoded = TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
            assertEquals(recipe, decoded);
            assertEquals(recipe.toString(), decoded.toString());
            assertEquals(
                    recipe,
                    TerrainRecipe.CODEC
                            .parse(JsonOps.INSTANCE, JsonParser.parseString(recipe.toString()))
                            .getOrThrow());
        }
    }

    @Test
    void invalidTagValuesFailWithFieldDiagnosticAndPlannerRemainsStrict() {
        for (String value :
                List.of(
                        "null",
                        "false",
                        "1",
                        "[]",
                        "{}",
                        "\"\"",
                        "\"test:\"",
                        "\"#escape_velocity:home\"",
                        "\"bad namespace:home\"",
                        "\"test:UPPER\"")) {
            var json = recipeJson();
            json.getAsJsonObject("planner").add("structure_sets", JsonParser.parseString(value));
            var error = assertThrows(IllegalArgumentException.class, () -> new TerrainRecipe(json));
            assertTrue(error.getMessage().contains("planner.structure_sets"), value);
            var parsed = TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, json);
            assertTrue(parsed.isError(), value);
            assertTrue(
                    parsed.error().orElseThrow().message().contains("planner.structure_sets"),
                    value);
        }
        var unknown = recipeJson();
        unknown.getAsJsonObject("planner").addProperty("structure_set", "escape_velocity:home");
        assertTrue(TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, unknown).isError());
        var missingStages = recipeJson();
        missingStages.getAsJsonObject("planner").remove("stages");
        missingStages
                .getAsJsonObject("planner")
                .addProperty("structure_sets", "escape_velocity:home");
        assertTrue(TerrainRecipe.CODEC.parse(JsonOps.INSTANCE, missingStages).isError());
    }

    @Test
    void homeTagContainsExactlyTheFourVanillaSets() throws Exception {
        var tag = homeTag();
        assertTrue(tag.get("replace").getAsBoolean());
        var values =
                tag.getAsJsonArray("values").asList().stream()
                        .map(JsonElement::getAsString)
                        .toList();
        assertEquals(4, values.size());
        assertEquals(
                Set.of(
                        "minecraft:villages",
                        "minecraft:mineshafts",
                        "minecraft:shipwrecks",
                        "minecraft:ocean_ruins"),
                Set.copyOf(values));
    }

    @Test
    void configuredHomeTagUsesOnlyItsSetsAndMatchesExplicitSelection() {
        var registry =
                PlanetPlanFixtures.context.registries().registryOrThrow(Registries.STRUCTURE_SET);
        var home = registry.getTag(HOME).orElseThrow();
        var allowed = new HashSet<ResourceLocation>();
        home.forEach(
                set ->
                        set.value()
                                .structures()
                                .forEach(
                                        entry ->
                                                allowed.add(
                                                        entry.structure()
                                                                .unwrapKey()
                                                                .orElseThrow()
                                                                .location())));
        var actual =
                new TraditionalPlanetPlanner(
                                planet("escape_velocity:home"), PlanetPlanFixtures.context)
                        .generate();
        var expected =
                new TraditionalPlanetPlanner(
                                planet("escape_velocity:home"), PlanetPlanFixtures.context)
                        .generate(home);
        assertFalse(actual.plan().sites().isEmpty(), () -> actual.skipped().toString());
        assertTrue(
                actual.plan().sites().stream()
                        .allMatch(site -> allowed.contains(site.structure())));
        assertArrayEquals(expected.plan().compressed(), actual.plan().compressed());
        assertEquals(expected.recipes(), actual.recipes());
        for (var site : actual.plan().sites()) {
            assertEquals(0, site.effects().burial());
            assertEquals(0, site.effects().damage());
        }
    }

    @Test
    void missingConfiguredTagFailsInsteadOfUsingGlobalDefault() {
        var planner =
                new TraditionalPlanetPlanner(planet("test:missing"), PlanetPlanFixtures.context);
        var error = assertThrows(IllegalArgumentException.class, planner::generate);
        assertTrue(error.getMessage().contains("#test:missing"));
        assertTrue(error.getMessage().contains("terrain.planner.structure_sets"));
    }

    @Test
    void absentDefaultTagPreservesImplicitEmptySelectionButFailsWhenExplicit() {
        var original = PlanetPlanFixtures.context;
        var registries = new ArrayList<Registry<?>>();
        original.registries()
                .registries()
                .filter(entry -> !entry.key().equals(Registries.STRUCTURE_SET))
                .forEach(entry -> registries.add(entry.value()));
        registries.add(
                new MappedRegistry<StructureSet>(Registries.STRUCTURE_SET, Lifecycle.stable())
                        .freeze());
        var context =
                new StructureSandbox.Context(
                        new RegistryAccess.ImmutableRegistryAccess(registries),
                        original.templates(),
                        original.random());
        var implicit =
                new TraditionalPlanetPlanner(PlanetPlanFixtures.planet(416, "plains"), context)
                        .generate();
        assertTrue(implicit.plan().sites().isEmpty());
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new TraditionalPlanetPlanner(planet("starfold:planets"), context)
                                        .generate());
        assertTrue(error.getMessage().contains("#starfold:planets"));
        assertTrue(error.getMessage().contains("terrain.planner.structure_sets"));
    }

    @Test
    void existingEmptyTagIntentionallyDisablesSelectionAndSurvivesPlanCodec() {
        var archive =
                new TraditionalPlanetPlanner(planet("test:empty"), PlanetPlanFixtures.context)
                        .generate();
        assertTrue(archive.plan().sites().isEmpty());
        assertTrue(archive.recipes().isEmpty());
        assertTrue(archive.skipped().isEmpty());
        var decoded = PlanetPlan.decode(archive.plan().compressed());
        assertEquals(Optional.of(EMPTY.location()), decoded.terrain().recipe().structureSets());
        assertArrayEquals(archive.plan().compressed(), decoded.compressed());
    }
}
