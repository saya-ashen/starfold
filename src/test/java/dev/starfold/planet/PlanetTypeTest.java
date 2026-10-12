package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.starfold.Sources;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.sky.GroundDaylight;
import dev.starfold.sky.GroundSolar;
import dev.starfold.worldgen.PlanetClimate;
import dev.starfold.worldgen.PlanetDecoration;
import dev.starfold.worldgen.PlanetEcology;
import dev.starfold.worldgen.PlanetEcologyTest;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetMinerals;
import dev.starfold.worldgen.PlanetPalette;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.SystemChunkGenerator;

import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Planet types come from data; a planet made from one is saved with the values it was given. */
public class PlanetTypeTest {
    @BeforeAll
    public static void boot() {
        PlanetEcologyTest.boot();
    }

    static final Path DATA = Path.of("src/main/resources/data/starfold");
    private static net.minecraft.core.HolderLookup.Provider loaded;

    /** The vanilla registries and the planet types of the mod's own data, as a loaded data pack would give them. */
    static net.minecraft.core.HolderLookup.Provider lookup() {
        ops();
        return loaded;
    }

    public static synchronized com.mojang.serialization.DynamicOps<JsonElement> ops() {
        if (loaded == null) {
            var base = PlanetEcologyTest.lookup;
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

    static JsonObject typeJson(String name) {
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

    public static JsonElement preset(String name) throws Exception {
        return JsonParser.parseString(
                        Files.readString(DATA.resolve("worldgen/world_preset/" + name + ".json")))
                .getAsJsonObject()
                .getAsJsonObject("dimensions")
                .getAsJsonObject("starfold:native_planet")
                .getAsJsonObject("generator");
    }

    @Test
    void theDocumentedPackNamesItsOwnTypeAndLayoutAndMakesANewSystem() throws Exception {
        var text = Files.readString(Path.of("docs/DATAPACKS.md"));
        text = text.substring(text.indexOf("## Example 1:"), text.indexOf("## Star systems"));
        var blocks =
                java.util.regex.Pattern.compile(
                                "```json\\s*\\n(.*?)\\n```", java.util.regex.Pattern.DOTALL)
                        .matcher(text);
        var json = new java.util.ArrayList<JsonObject>();
        while (blocks.find()) json.add(JsonParser.parseString(blocks.group(1)).getAsJsonObject());
        assertEquals(4, json.size());
        assertEquals(48, json.get(0).getAsJsonObject("pack").get("pack_format").getAsInt());
        // Read the actual example, not a second copy of its JSON. This is codec evidence, not a
        // game pack-load test.
        var type = PlanetType.CODEC.parse(ops(), json.get(1)).getOrThrow();
        var types =
                new net.minecraft.core.MappedRegistry<PlanetType>(
                        PlanetType.KEY, com.mojang.serialization.Lifecycle.stable());
        net.minecraft.core.Registry.register(
                types, net.minecraft.resources.ResourceLocation.parse("demo:ash"), type);
        var frozenTypes = types.freeze();
        var base = PlanetEcologyTest.lookup;
        var withTypes =
                net.minecraft.core.HolderLookup.Provider.create(
                        java.util.stream.Stream.concat(
                                base.listRegistries().map(base::lookupOrThrow),
                                java.util.stream.Stream.of(frozenTypes.asLookup())));
        var layout =
                UniverseLayout.CODEC
                        .parse(
                                net.minecraft.resources.RegistryOps.create(
                                        JsonOps.INSTANCE, withTypes),
                                json.get(2))
                        .getOrThrow();
        var layouts =
                new net.minecraft.core.MappedRegistry<UniverseLayout>(
                        UniverseLayout.KEY, com.mojang.serialization.Lifecycle.stable());
        net.minecraft.core.Registry.register(
                layouts,
                net.minecraft.resources.ResourceLocation.parse("demo:ash_universe"),
                layout);
        var frozenLayouts = layouts.freeze();
        var loaded =
                net.minecraft.core.HolderLookup.Provider.create(
                        java.util.stream.Stream.concat(
                                withTypes.listRegistries().map(withTypes::lookupOrThrow),
                                java.util.stream.Stream.of(frozenLayouts.asLookup())));
        var exampleOps = net.minecraft.resources.RegistryOps.create(JsonOps.INSTANCE, loaded);
        var generator =
                json.get(3)
                        .getAsJsonObject("dimensions")
                        .getAsJsonObject("starfold:native_planet")
                        .get("generator");
        var system = SystemChunkGenerator.CODEC.codec().parse(exampleOps, generator).getOrThrow();
        assertEquals(2, system.count());
        assertEquals(768, system.planet(1).generation().size());
        assertSame(Blocks.TUFF, system.planet(1).generation().palette().rock());
        assertEquals(20261003, system.planet(1).seed());
        var universe =
                Universe.generate(
                        42,
                        system.universe().orElseThrow().value(),
                        PlanetSystem.model(system.groups()));
        assertEquals(8, universe.planets().size());
        for (var planet : universe.planets())
            assertSame(Blocks.TUFF, planet.generation().palette().rock());
    }

    @Test
    void theBuiltInTypesAreReadAndTheirRangesHold() {
        var rocky = type("rocky");
        assertEquals(PlanetPalette.ROCK, rocky.palette());
        assertEquals(512, rocky.minSize());
        assertEquals(1536, rocky.maxSize());
        assertTrue(rocky.biome().is(Biomes.STONY_PEAKS));
        var desert = type("desert");
        assertSame(Blocks.SANDSTONE, desert.palette().rock());
        assertEquals(Optional.of(Blocks.SAND), desert.palette().cover());
        assertTrue(desert.biome().is(Biomes.DESERT));
        var ice = type("ice");
        assertSame(Blocks.PACKED_ICE, ice.palette().rock());
        assertEquals(448, ice.minSize());
        for (var type : new PlanetType[] {rocky, desert, ice}) {
            var seen = new java.util.HashSet<Integer>();
            for (long seed = 0; seed < 400; seed++) {
                int size = type.size(seed);
                assertTrue(
                        size >= type.minSize() && size <= type.maxSize() && size % 16 == 0,
                        String.valueOf(size));
                assertEquals(size, type.size(seed));
                seen.add(size);
                var generation = type.generation(size);
                assertEquals(size, generation.size());
                assertTrue(
                        generation.depth() >= 191
                                && generation.depth() <= new PlanetShape(size).radius() * .75);
                assertTrue(generation.coreShell());
            }
            assertTrue(seen.size() > 20, "sizes spread over the range: " + seen.size());
            assertTrue(
                    seen.contains(type.minSize())
                            || seen.contains(type.maxSize())
                            || seen.size() > 30);
        }
        assertEquals(326, rocky.depth(1024));
        assertEquals(191, rocky.depth(512));
        assertThrows(IllegalArgumentException.class, () -> rocky.generation(2048));
        assertThrows(IllegalArgumentException.class, () -> rocky.generation(496));
    }

    @Test
    void aTypeThatCannotMakeAPlanetIsRefused() {
        var wide = typeJson("rocky");
        wide.getAsJsonObject("size").addProperty("max", 4096);
        assertThrows(IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), wide));
        var tiny = typeJson("rocky");
        tiny.getAsJsonObject("size").addProperty("min", 256);
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanetType.CODEC.parse(ops(), tiny),
                "too small to be the least depth");
        var backwards = typeJson("rocky");
        backwards.getAsJsonObject("size").addProperty("min", 2048);
        assertThrows(
                IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), backwards));
        var airy = typeJson("desert");
        airy.getAsJsonObject("palette").addProperty("cover", "minecraft:air");
        assertThrows(IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), airy));
        var wet = typeJson("desert");
        wet.getAsJsonObject("palette").addProperty("rock", "minecraft:water");
        assertThrows(IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), wet));
        var plain = typeJson("desert");
        plain.addProperty("algorithm", "rocky");
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanetType.CODEC.parse(ops(), plain),
                "only the cratered algorithm has a palette");
        var unknown = typeJson("desert");
        unknown.getAsJsonObject("palette").addProperty("deep", "minecraft:no_such_block");
        assertTrue(PlanetType.CODEC.parse(ops(), unknown).isError());
    }

    @Test
    void aDesertPlanetIsSandOverItsOwnStrataWithItsOwnOresAndTheSameGroundAndCaves() {
        var type = type("desert");
        var desert = new PlanetTerrain(20261003, 5, type.generation(768));
        var rock =
                new PlanetTerrain(
                        20261003,
                        5,
                        new PlanetGeneration(
                                "rocky",
                                "rocky_craters",
                                1,
                                .8,
                                .03,
                                .08,
                                768,
                                type.depth(768),
                                PlanetGeneration.CORE_SHELL));
        assertEquals(desert.floor(), rock.floor());
        int sand = 0;
        var rocks = new java.util.HashMap<net.minecraft.world.level.block.Block, Integer>();
        var ores = new java.util.HashMap<net.minecraft.world.level.block.Block, Integer>();
        var strata =
                java.util.List.of(
                        Blocks.SANDSTONE, Blocks.TERRACOTTA, Blocks.STONE, Blocks.DEEPSLATE);
        var allowed =
                java.util.Set.of(
                        Blocks.GOLD_ORE,
                        Blocks.DEEPSLATE_GOLD_ORE,
                        Blocks.COPPER_ORE,
                        Blocks.DEEPSLATE_COPPER_ORE,
                        Blocks.IRON_ORE,
                        Blocks.DEEPSLATE_IRON_ORE,
                        Blocks.REDSTONE_ORE,
                        Blocks.DEEPSLATE_REDSTONE_ORE,
                        Blocks.DIAMOND_ORE,
                        Blocks.DEEPSLATE_DIAMOND_ORE);
        for (var f : Face.values())
            for (int u = 40; u < 760; u += 97)
                for (int v = 40; v < 760; v += 89) {
                    var a = desert.column(f, u, v);
                    var b = rock.column(f, u, v);
                    assertEquals(b.ground(), a.ground(), "the same ground under another material");
                    if (Anomaly.surface(
                                    Anomaly.distance(
                                            desert.unit(f, u + .5, v + .5), desert.shape()))
                            != null) continue;
                    for (int y = a.ground() - 3; y <= a.ground(); y++) {
                        var block = a.state(y).getBlock();
                        assertTrue(
                                block == Blocks.SAND
                                        || block == Blocks.IRON_ORE
                                        || block == Blocks.COPPER_ORE,
                                block + " at " + y);
                        if (block == Blocks.SAND) sand++;
                    }
                    int last = 0;
                    for (int y = a.ground() - 4; y > desert.floor(); y--) {
                        var mine = a.state(y);
                        assertEquals(b.state(y).isAir(), mine.isAir(), "the same caves");
                        if (mine.isAir()) continue;
                        var block = mine.getBlock();
                        if (allowed.contains(block)) {
                            ores.merge(block, 1, Integer::sum);
                            double share = PlanetMinerals.share(desert.layer(y));
                            if (block == Blocks.DIAMOND_ORE
                                    || block == Blocks.DEEPSLATE_DIAMOND_ORE)
                                assertTrue(share >= .79, "diamonds only deep: " + share);
                            if (block == Blocks.COPPER_ORE || block == Blocks.DEEPSLATE_COPPER_ORE)
                                assertTrue(share <= .61, "copper only shallow: " + share);
                            assertEquals(
                                    desert.layer(y) < PlanetMinerals.layer(.3),
                                    block.toString().contains("deepslate"),
                                    "the deep form below the first stratum");
                            continue;
                        }
                        int at = strata.indexOf(block);
                        assertTrue(at >= last, block + " at " + y + " after " + strata.get(last));
                        last = at;
                        rocks.merge(block, 1, Integer::sum);
                    }
                    assertSame(PlanetCore.SHELL_BLOCK, a.state(desert.floor()).getBlock());
                }
        assertTrue(sand > 500, String.valueOf(sand));
        for (var block : strata)
            assertTrue(rocks.getOrDefault(block, 0) > 2000, block + " " + rocks);
        assertTrue(ores.size() >= 8, ores.toString());
        assertFalse(ores.containsKey(Blocks.COAL_ORE));
    }

    @Test
    void mineralsAreWrittenAsSharesOfDepthAndHoldToTheirOrder() throws Exception {
        assertEquals(127, PlanetMinerals.layer(0));
        assertEquals(-64, PlanetMinerals.layer(1));
        assertEquals(0, PlanetMinerals.layer(PlanetMinerals.share(0)));
        for (int layer = -64; layer <= 127; layer++)
            assertEquals(layer, PlanetMinerals.layer(PlanetMinerals.share(layer)));
        var minerals = type("ice").minerals();
        assertEquals(4, minerals.strata().size());
        assertEquals(4, minerals.ores().size());
        assertSame(Blocks.PACKED_ICE, minerals.strata().get(0).block());
        assertEquals(127, minerals.strata().get(0).from());
        var json = PlanetMinerals.CODEC.encodeStart(JsonOps.INSTANCE, minerals).getOrThrow();
        assertEquals(minerals, PlanetMinerals.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        assertEquals(PlanetMinerals.NONE, type("rocky").minerals());
        assertFalse(
                PlanetGeneration.CODEC
                        .encodeStart(JsonOps.INSTANCE, type("rocky").generation(1024))
                        .getOrThrow()
                        .getAsJsonObject()
                        .has("minerals"));
        var table = new PlanetMinerals.Table(minerals);
        assertSame(Blocks.PACKED_ICE, table.rock(200).getBlock());
        assertSame(Blocks.PACKED_ICE, table.rock(100).getBlock());
        assertSame(Blocks.BLUE_ICE, table.rock(PlanetMinerals.layer(.35) - 1).getBlock());
        assertSame(Blocks.DEEPSLATE, table.rock(-64).getBlock());
        assertNull(new PlanetMinerals.Table(PlanetMinerals.NONE).rock(0));
        var upside = typeJson("ice");
        var strata = upside.getAsJsonObject("minerals").getAsJsonArray("strata");
        var first = strata.get(1);
        strata.set(1, strata.get(2));
        strata.set(2, first);
        assertThrows(IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), upside));
        var late = typeJson("ice");
        late.getAsJsonObject("minerals")
                .getAsJsonArray("strata")
                .get(0)
                .getAsJsonObject()
                .addProperty("from", .1);
        assertThrows(IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), late));
        var common = typeJson("ice");
        common.getAsJsonObject("minerals")
                .getAsJsonArray("ores")
                .get(0)
                .getAsJsonObject()
                .addProperty("rarity", .2);
        assertTrue(PlanetType.CODEC.parse(ops(), common).isError());
        var inverted = typeJson("ice");
        var ore =
                inverted.getAsJsonObject("minerals")
                        .getAsJsonArray("ores")
                        .get(0)
                        .getAsJsonObject();
        ore.addProperty("from", .9);
        ore.addProperty("to", .2);
        assertThrows(IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), inverted));
        var liquid = typeJson("ice");
        liquid.getAsJsonObject("minerals")
                .getAsJsonArray("ores")
                .get(0)
                .getAsJsonObject()
                .addProperty("block", "minecraft:lava");
        assertThrows(IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), liquid));
    }

    @Test
    void aTemperatePlanetMayHaveMineralsOfItsOwnAndOtherwiseKeepsThoseOfItsKind() {
        var json = typeJson("temperate");
        json.add("minerals", typeJson("ice").get("minerals"));
        var type = PlanetType.CODEC.parse(ops(), json).getOrThrow();
        var own = type.planet(5, Optional.of(1024)).terrain();
        var kind = type("temperate").planet(5, Optional.of(1024)).terrain();
        int ice = 0, stone = 0, emerald = 0;
        for (var f : Face.values())
            for (int u = 30; u < 1000; u += 61)
                for (int v = 30; v < 1000; v += 67) {
                    var a = own.column(f, u, v);
                    var b = kind.column(f, u, v);
                    assertEquals(b.ground(), a.ground());
                    for (int y = a.ground(); y < PlanetTerrain.MAX_Y; y++)
                        assertEquals(b.state(y), a.state(y), "the same surface and growth");
                    for (int y = own.floor() + 1; y < a.ground() - 8; y++) {
                        assertEquals(b.state(y).isAir(), a.state(y).isAir());
                        var block = a.state(y).getBlock();
                        if (block == Blocks.PACKED_ICE || block == Blocks.BLUE_ICE) ice++;
                        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE)
                            emerald++;
                        if (b.state(y).is(Blocks.STONE)) stone++;
                        assertNotSame(Blocks.COAL_ORE, block);
                    }
                }
        assertTrue(ice > 5000 && stone > 5000 && emerald > 0, ice + " " + stone + " " + emerald);
    }

    @Test
    void aPlanetNamedByTypeIsMadeWhenAskedForAndSavedInFull() throws Exception {
        var planet = new JsonObject();
        planet.addProperty("planet_type", "starfold:ice");
        planet.addProperty("terrain_seed", 20261004L);
        var entry = PlanetType.PLANET.parse(ops(), planet).getOrThrow();
        assertFalse(entry.written());
        var made = entry.get();
        assertSame(made, entry.get());
        var ice = type("ice");
        assertEquals(ice.size(20261004L), made.terrain().shape().size());
        assertEquals(ice.generation(ice.size(20261004L)), made.generation());
        assertEquals(5, made.version());
        var saved = PlanetType.PLANET.encodeStart(ops(), entry).getOrThrow().getAsJsonObject();
        assertFalse(saved.has("planet_type"));
        assertEquals(
                "minecraft:packed_ice",
                saved.getAsJsonObject("planet_generation")
                        .getAsJsonObject("palette")
                        .get("rock")
                        .getAsString());
        var again = PlanetType.PLANET.parse(ops(), saved).getOrThrow();
        assertTrue(again.written());
        assertEquals(made.generation(), again.get().generation());
        assertEquals(made.seed(), again.get().seed());
        assertEquals(saved, PlanetType.PLANET.encodeStart(ops(), again).getOrThrow());
        planet.addProperty("size", 512);
        assertEquals(
                512,
                PlanetType.PLANET.parse(ops(), planet).getOrThrow().get().terrain().shape().size());
        planet.addProperty("size", 2048);
        var outside = PlanetType.PLANET.parse(ops(), planet).getOrThrow();
        assertTrue(
                assertThrows(IllegalArgumentException.class, outside::get)
                        .getMessage()
                        .contains("starfold:ice"),
                "a size outside the type's range, and which type");
        // A planet may carry its type written out, which is how one is specified by hand.
        var inline = new JsonObject();
        inline.add("planet_type", typeJson("desert"));
        inline.addProperty("terrain_seed", 5L);
        inline.addProperty("size", 640);
        assertSame(
                Blocks.SAND,
                PlanetType.PLANET
                        .parse(ops(), inline)
                        .getOrThrow()
                        .get()
                        .generation()
                        .palette()
                        .cover()
                        .orElseThrow());
        var unknown = new JsonObject();
        unknown.addProperty("planet_type", "starfold:no_such_type");
        unknown.addProperty("terrain_seed", 5L);
        assertTrue(PlanetType.PLANET.parse(ops(), unknown).isError());
        // A planet without a palette is saved without one, as before.
        var plain =
                PlanetType.PLANET
                        .encodeStart(
                                ops(),
                                new PlanetType.Entry(type("rocky").planet(7, Optional.of(1024))))
                        .getOrThrow()
                        .getAsJsonObject();
        assertFalse(plain.getAsJsonObject("planet_generation").has("palette"));
    }

    @Test
    void aSystemsFirstPlanetIsWrittenOutAndTheOthersWaitForTheirTypes() throws Exception {
        var json = preset("native_stars").getAsJsonObject();
        var g = SystemChunkGenerator.CODEC.codec().parse(ops(), json).getOrThrow();
        assertEquals(8, g.count());
        assertEquals(768, g.planet(2).terrain().shape().size());
        var saved =
                SystemChunkGenerator.CODEC
                        .codec()
                        .encodeStart(ops(), g)
                        .getOrThrow()
                        .getAsJsonObject();
        for (var planet : saved.getAsJsonArray("planets"))
            assertFalse(planet.getAsJsonObject().has("planet_type"), "saved in full");
        var back = SystemChunkGenerator.CODEC.codec().parse(ops(), saved).getOrThrow();
        for (int id = 0; id < 8; id++)
            assertEquals(g.planet(id).generation(), back.planet(id).generation());
        assertEquals(g.getMinY(), back.getMinY());
        var typedFirst = json.deepCopy();
        var first = new JsonObject();
        first.addProperty("planet_type", "starfold:rocky");
        first.addProperty("terrain_seed", 1L);
        typedFirst.getAsJsonArray("planets").set(0, first);
        assertThrows(
                IllegalArgumentException.class,
                () -> SystemChunkGenerator.CODEC.codec().parse(ops(), typedFirst));
    }

    @Test
    void thePaletteCrossesTheWireWithThePlanetsProfile() {
        var buffer =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        var profile = new PlanetPackets.Profile(42, 20261003, 5, type("desert").generation(768));
        PlanetPackets.Profile.CODEC.encode(buffer, profile);
        assertEquals(profile, PlanetPackets.Profile.CODEC.decode(buffer));
        var old =
                new PlanetPackets.Profile(
                        42,
                        20261002,
                        5,
                        new PlanetGeneration(
                                "rocky", "rocky_craters", 1, 1.2, .04, .09, 1024, 326));
        PlanetPackets.Profile.CODEC.encode(buffer, old);
        assertEquals(
                PlanetPalette.ROCK,
                PlanetPackets.Profile.CODEC.decode(buffer).generation().palette());
        assertEquals(5, profile.generation().minerals().ores().size());
        var icy = new PlanetPackets.Profile(42, 7, 5, type("ice").generation(512));
        PlanetPackets.Profile.CODEC.encode(buffer, icy);
        assertEquals(icy, PlanetPackets.Profile.CODEC.decode(buffer));
    }

    @Test
    void theTwoStarPresetNamesADesertAndAnIcePlanet() throws Exception {
        var g =
                SystemChunkGenerator.CODEC
                        .codec()
                        .parse(ops(), preset("native_stars"))
                        .getOrThrow();
        assertEquals(8, g.count());
        assertEquals(-.35, g.planet(5).generation().climate().humidity());
        assertEquals(.88, g.planet(6).terrain().oceanShare(), .03);
        assertEquals(0, g.planet(7).terrain().oceanShare());
        assertFalse(g.planet(4).generation().rocky());
        assertEquals(1024, g.planet(4).terrain().shape().size());
        assertSame(Blocks.SAND, g.planet(2).generation().palette().cover().orElseThrow());
        assertEquals(768, g.planet(2).terrain().shape().size());
        assertSame(Blocks.PACKED_ICE, g.planet(3).generation().palette().rock());
        assertEquals(PlanetPalette.ROCK, g.planet(1).generation().palette());
        assertTrue(
                Files.readString(DATA.resolve("worldgen/world_preset/native_stars.json"))
                        .contains("\"planet_type\":\"starfold:desert\""));
    }

    @Test
    void aTemperateTypeMakesAPlanetOfSeasSoilAndGrowthAtItsOwnSize() {
        var type = type("temperate");
        assertTrue(type.temperate());
        assertEquals(1024, type.minSize());
        assertEquals(2048, type.maxSize());
        assertEquals(0, type.oreBias());
        assertEquals(.115, type.caveWidth());
        assertEquals(1, type.relief());
        var planet = type.planet(20261005, Optional.of(1024));
        assertEquals(PlanetTerrain.ECOLOGY_VERSION, planet.version());
        var g = planet.generation();
        assertFalse(g.rocky());
        assertEquals("ecology", g.algorithm());
        assertEquals(1024, g.size());
        assertEquals(326, g.depth());
        assertTrue(g.coreShell());
        var terrain = planet.terrain();
        int water = 0, grass = 0, sand = 0, land = 0;
        var biomes =
                new java.util.HashSet<
                        net.minecraft.resources.ResourceKey<
                                net.minecraft.world.level.biome.Biome>>();
        for (var f : Face.values())
            for (int u = 8; u < 1024; u += 24)
                for (int v = 8; v < 1024; v += 24) {
                    var column = terrain.column(f, u, v);
                    var top = terrain.surfaceState(f, u, v);
                    if (!top.getFluidState().isEmpty()) water++;
                    else {
                        land++;
                        if (top.is(Blocks.GRASS_BLOCK)) grass++;
                        if (top.is(Blocks.SAND)) sand++;
                    }
                    assertSame(
                            terrain.closure(f, u, v) ? Anomaly.CORE_BLOCK : PlanetCore.SHELL_BLOCK,
                            column.state(terrain.floor()).getBlock());
                    biomes.add(
                            planet.getBiomeSource()
                                    .getNoiseBiome((f.ordinal() * 4096 + u) / 4, 32, v / 4, null)
                                    .unwrapKey()
                                    .orElseThrow());
                }
        assertTrue(
                water > 200 && grass > 200 && sand > 20 && land > water / 4,
                water + " " + grass + " " + sand + " " + land);
        assertEquals(
                java.util.Set.of(Biomes.FOREST, Biomes.PLAINS, Biomes.DESERT, Biomes.OCEAN),
                biomes);
        // Beyond a face of 1024 nothing of the planet is asked of its biomes: it answers as outside
        // any face.
        assertTrue(
                planet.getBiomeSource()
                        .getNoiseBiome((1500) / 4, 32, 100 / 4, null)
                        .is(Biomes.PLAINS));
        // The biome at a place is that of the planet's own ground there: sea where its ground is
        // under the sea.
        int agree = 0, asked = 0;
        for (int u = 8; u < 1024; u += 40)
            for (int v = 8; v < 1024; v += 40) {
                boolean sea = terrain.groundY(Face.values()[2], u, v) < terrain.sea().level();
                boolean ocean =
                        planet.getBiomeSource()
                                .getNoiseBiome((2 * 4096 + u) / 4, 32, v / 4, null)
                                .is(Biomes.OCEAN);
                asked++;
                if (sea == ocean) agree++;
            }
        assertTrue(agree > asked * 9 / 10, agree + " of " + asked);
    }

    @Test
    void aTypeIsTemperateWithItsBiomesOrRockyWithout() {
        var bare = typeJson("temperate");
        bare.remove("biomes");
        assertThrows(IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), bare));
        var mixed = typeJson("rocky");
        mixed.add("biomes", typeJson("temperate").get("biomes"));
        assertThrows(IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), mixed));
        var painted = typeJson("temperate");
        painted.add("palette", typeJson("desert").get("palette"));
        assertThrows(IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), painted));
        var odd = typeJson("temperate");
        odd.getAsJsonObject("biomes").addProperty("forest", "minecraft:desert");
        assertThrows(
                IllegalArgumentException.class,
                () -> PlanetType.CODEC.parse(ops(), odd),
                "its regions are the four it has always had");
        var steep = typeJson("temperate");
        steep.addProperty("relief", 1.5);
        assertThrows(IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), steep));
        // Saved in full and read back, a temperate planet keeps its biomes and its size.
        var entry = new PlanetType.Entry(type("temperate").planet(9, Optional.of(1536)));
        var saved = PlanetType.PLANET.encodeStart(ops(), entry).getOrThrow().getAsJsonObject();
        assertEquals(4, saved.get("terrain_version").getAsInt());
        assertTrue(saved.has("planet_biomes"));
        var back = PlanetType.PLANET.parse(ops(), saved).getOrThrow().get();
        assertEquals(1536, back.terrain().shape().size());
        assertEquals(entry.get().generation(), back.generation());
    }

    static int[] regions(PlanetTerrain terrain, boolean belt) {
        var ecology = new PlanetEcology(terrain);
        int[] count = new int[3];
        int size = terrain.shape().size();
        for (var f : Face.values())
            for (int u = 8; u < size; u += 16)
                for (int v = 8; v < size; v += 16) {
                    var unit = terrain.unit(f, u + .5, v + .5);
                    if (belt && Math.abs(unit.y()) > .35) continue;
                    count[ecology.region(unit).ordinal()]++;
                }
        return count;
    }

    @Test
    void aClimateSharesOutThePlanetsRegionsAndAMildOneIsWhatThereAlwaysWas() {
        var mild = type("temperate").planet(11, Optional.of(1024)).terrain();
        var lush = type("lush").planet(11, Optional.of(1024)).terrain();
        var arid = type("arid").planet(11, Optional.of(1024)).terrain();
        assertEquals(PlanetClimate.MILD, mild.generation().climate());
        assertFalse(
                PlanetGeneration.CODEC
                        .encodeStart(JsonOps.INSTANCE, mild.generation())
                        .getOrThrow()
                        .getAsJsonObject()
                        .has("climate"));
        var saved =
                PlanetGeneration.CODEC
                        .encodeStart(JsonOps.INSTANCE, arid.generation())
                        .getOrThrow();
        assertEquals(
                arid.generation(),
                PlanetGeneration.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow());
        int[] m = regions(mild, false), l = regions(lush, false), a = regions(arid, false);
        int forest = PlanetEcology.Region.FOREST.ordinal(),
                desert = PlanetEcology.Region.DESERT.ordinal();
        assertTrue(
                l[forest] > m[forest] * 5 / 4 && l[desert] < m[desert] / 2,
                "lush: " + java.util.Arrays.toString(l) + " mild: " + java.util.Arrays.toString(m));
        assertTrue(
                a[desert] > m[desert] * 5 / 4 && a[forest] < m[forest] * 3 / 4,
                "arid: " + java.util.Arrays.toString(a) + " mild: " + java.util.Arrays.toString(m));
        // With a strong latitude the deserts keep to the belt about the equator.
        var bandedJson = typeJson("arid");
        bandedJson.getAsJsonObject("climate").addProperty("temperature", -.1);
        bandedJson.getAsJsonObject("climate").addProperty("latitude", .8);
        var banded =
                PlanetType.CODEC
                        .parse(ops(), bandedJson)
                        .getOrThrow()
                        .planet(11, Optional.of(1024))
                        .terrain();
        int[] all = regions(banded, false), belt = regions(banded, true);
        double inBelt = belt[desert] / (double) (belt[0] + belt[1] + belt[2]),
                overall = all[desert] / (double) (all[0] + all[1] + all[2]);
        assertTrue(overall > .02 && inBelt > overall * 1.5, inBelt + " " + overall);
        // The ground itself is the same whatever the climate.
        var onlyClimate = typeJson("arid");
        onlyClimate.remove("ocean");
        var same =
                PlanetType.CODEC
                        .parse(ops(), onlyClimate)
                        .getOrThrow()
                        .planet(11, Optional.of(1024))
                        .terrain();
        for (var f : Face.values())
            for (int u = 20; u < 1024; u += 111)
                for (int v = 20; v < 1024; v += 97)
                    assertEquals(mild.groundY(f, u, v), same.groundY(f, u, v));
        var dry = typeJson("rocky");
        dry.add("climate", typeJson("arid").get("climate"));
        assertThrows(IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), dry));
        var extreme = typeJson("arid");
        extreme.getAsJsonObject("climate").addProperty("humidity", -2);
        assertTrue(PlanetType.CODEC.parse(ops(), extreme).isError());
        var buffer =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        var profile = new PlanetPackets.Profile(42, 11, 4, arid.generation());
        PlanetPackets.Profile.CODEC.encode(buffer, profile);
        assertEquals(profile, PlanetPackets.Profile.CODEC.decode(buffer));
    }

    @Test
    void aTypesShareOfOceanIsMetOnEachPlanetAsItsOwnSeedMakesIt() {
        var plain = type("temperate");
        assertTrue(plain.ocean().isEmpty());
        double usual = plain.planet(11, Optional.of(1024)).terrain().oceanShare();
        assertTrue(usual > .5 && usual < .85, "what a temperate planet has had: " + usual);
        assertEquals(0, plain.planet(11, Optional.of(1024)).generation().climate().land());
        for (var name : java.util.List.of("arid", "lush", "archipelago")) {
            var type = type(name);
            double wanted = type.ocean().orElseThrow();
            for (long seed : new long[] {3, 20261006L, -77}) {
                var planet = type.planet(seed, Optional.of(1024));
                double land = planet.generation().climate().land();
                assertEquals(
                        wanted,
                        planet.terrain().oceanShare(),
                        .03,
                        name + " seed " + seed + " raised " + land);
                // Saved with the height its ground was raised by, it is the same planet read back.
                var saved =
                        PlanetGeneration.CODEC
                                .encodeStart(JsonOps.INSTANCE, planet.generation())
                                .getOrThrow();
                assertEquals(
                        land,
                        saved.getAsJsonObject()
                                .getAsJsonObject("climate")
                                .get("land")
                                .getAsDouble());
                assertEquals(
                        planet.generation(),
                        PlanetGeneration.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow());
            }
        }
        var steppe = type("steppe").planet(5, Optional.of(1024));
        assertEquals(0, steppe.terrain().oceanShare(), "no sea at all");
        assertEquals(PlanetClimate.MAX_LAND, steppe.generation().climate().land());
        // Raised ground keeps its relief and its growth: grass still covers most of the land, bare
        // stone only its heights.
        int grass = 0, stone = 0, water = 0;
        var terrain = steppe.terrain();
        for (var f : Face.values())
            for (int u = 8; u < 1024; u += 24)
                for (int v = 8; v < 1024; v += 24) {
                    var top = terrain.surfaceState(f, u, v);
                    if (top.is(Blocks.STONE)) stone++;
                    else if (!top.getFluidState().isEmpty()) water++;
                    else grass++;
                }
        assertEquals(0, water);
        assertTrue(grass > stone * 3, grass + " " + stone);
        assertTrue(terrain.peaks() > 157);
        int low = Integer.MAX_VALUE, high = 0;
        for (var f : Face.values())
            for (int u = 8; u < 1024; u += 40)
                for (int v = 8; v < 1024; v += 40) {
                    int y = terrain.groundY(f, u, v);
                    low = Math.min(low, y);
                    high = Math.max(high, y);
                }
        assertTrue(low >= 128 && high - low > 40, low + " " + high);
        var both = typeJson("arid");
        both.getAsJsonObject("climate").addProperty("land", 5);
        assertThrows(IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), both));
        var rocky = typeJson("rocky");
        rocky.addProperty("ocean", .3);
        assertThrows(IllegalArgumentException.class, () -> PlanetType.CODEC.parse(ops(), rocky));
        var flooded = typeJson("arid");
        flooded.addProperty("ocean", .99);
        assertTrue(PlanetType.CODEC.parse(ops(), flooded).isError());
    }

    @Test
    void aPlanetWithSeasAndGrowthHasCreaturesAndABareOneHasNoneUnlessItsTypeSaysSo()
            throws Exception {
        for (var name : java.util.List.of("temperate", "lush", "arid", "archipelago", "steppe"))
            assertTrue(type(name).planet(3, Optional.empty()).generation().creatures(), name);
        for (var name : java.util.List.of("rocky", "desert", "ice"))
            assertFalse(type(name).planet(3, Optional.empty()).generation().creatures(), name);
        var haunted = typeJson("rocky");
        haunted.addProperty("creatures", true);
        assertTrue(
                PlanetType.CODEC
                        .parse(ops(), haunted)
                        .getOrThrow()
                        .planet(3, Optional.empty())
                        .generation()
                        .creatures());
        var empty = typeJson("temperate");
        empty.addProperty("creatures", false);
        assertFalse(
                PlanetType.CODEC
                        .parse(ops(), empty)
                        .getOrThrow()
                        .planet(3, Optional.empty())
                        .generation()
                        .creatures());
        // A planet saved before this has the creatures of its biomes, and says nothing of it; one
        // without says so.
        var old = new PlanetGeneration("rocky", "rocky_craters", 1, 1.2, .04, .09, 1024, 326);
        assertTrue(old.creatures());
        assertFalse(
                PlanetGeneration.CODEC
                        .encodeStart(JsonOps.INSTANCE, old)
                        .getOrThrow()
                        .getAsJsonObject()
                        .has("creatures"));
        var bare = type("rocky").generation(1024);
        var saved = PlanetGeneration.CODEC.encodeStart(JsonOps.INSTANCE, bare).getOrThrow();
        assertFalse(saved.getAsJsonObject().get("creatures").getAsBoolean());
        assertEquals(bare, PlanetGeneration.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow());
        var buffer =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        var profile = new PlanetPackets.Profile(42, 7, 5, bare);
        PlanetPackets.Profile.CODEC.encode(buffer, profile);
        assertFalse(PlanetPackets.Profile.CODEC.decode(buffer).generation().creatures());
        // In every preset of several kinds the first planet has creatures and the bare ones have
        // none; the frozen pair is as it was.
        for (var preset :
                java.util.List.of(
                        "native_exploration", "native_trio", "native_stars", "native_universe")) {
            var g = SystemChunkGenerator.CODEC.codec().parse(ops(), preset(preset)).getOrThrow();
            assertTrue(g.planet(0).generation().creatures(), preset);
            assertFalse(g.planet(1).generation().creatures(), preset);
        }
        assertFalse(
                Files.readString(DATA.resolve("worldgen/world_preset/native_system.json"))
                        .contains("creatures"));
    }

    @Test
    void aSystemAsksItsPlanetsWhoLivesWhere() throws Exception {
        var g =
                SystemChunkGenerator.CODEC
                        .codec()
                        .parse(ops(), preset("native_stars"))
                        .getOrThrow();
        int[] sizes = new int[g.count()], depths = new int[sizes.length];
        for (int id = 0; id < sizes.length; id++) {
            sizes[id] = g.planet(id).terrain().shape().size();
            depths[id] = g.planet(id).generation().depth();
        }
        PlanetShape.system(sizes, depths);
        try {
            var plains =
                    lookup().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                            .getOrThrow(Biomes.PLAINS);
            var monsters = net.minecraft.world.entity.MobCategory.MONSTER;
            var animals = net.minecraft.world.entity.MobCategory.CREATURE;
            for (int id = 0; id < g.count(); id++) {
                var at =
                        new net.minecraft.core.BlockPos(
                                PlanetStorage.offset(id) + 100,
                                130,
                                PlanetStorage.offsetZ(id) + 100);
                boolean lives = g.planet(id).generation().creatures();
                assertEquals(lives, g.lives(at), "planet " + id);
                if (!lives) {
                    assertTrue(g.getMobsAt(plains, null, monsters, at).isEmpty());
                    assertTrue(g.getMobsAt(plains, null, animals, at).isEmpty());
                }
            }
            assertFalse(
                    g.lives(new net.minecraft.core.BlockPos(100, 130, 5000)),
                    "nothing in the gap between planets");
            assertTrue(
                    g.getMobsAt(
                                    plains,
                                    null,
                                    monsters,
                                    new net.minecraft.core.BlockPos(100, 130, 5000))
                            .isEmpty());
        } finally {
            PlanetShape.reset();
        }
        var type =
                com.google.gson.JsonParser.parseString(
                                Files.readString(DATA.resolve("dimension_type/native_deep.json")))
                        .getAsJsonObject();
        assertEquals(
                7,
                type.getAsJsonObject("monster_spawn_light_level").get("max_inclusive").getAsInt(),
                "night on the surface is dark enough, as in the overworld");
        var source = Sources.text("SystemChunkGenerator");
        assertTrue(
                source.contains(
                        "NaturalSpawner.spawnMobsForChunkGeneration(region,biome,chunk,random)"));
        var mixins = Files.readString(Path.of("src/main/resources/starfold.mixins.json"));
        assertTrue(
                mixins.contains("\"GroundMonsterSpawnMixin\"")
                        && mixins.contains("\"GroundSunBurnMixin\""));
    }

    @Test
    void aWorldBeginsOnTheFaceThatHasItsDayAhead() {
        for (var model :
                java.util.List.of(PlanetSystem.model(), PlanetSystem.model(new int[] {2, 2}))) {
            var shape = new PlanetShape(2048);
            int home = GroundDaylight.homeFace(model, shape);
            assertEquals(home, GroundDaylight.homeFace(model, shape));
            var face = Face.values()[home];
            // The sun is up there within three minutes and stays up for thirteen minutes.
            int day = 0;
            for (int tick = 3600; tick <= 3600 + 15600; tick += 600) {
                assertTrue(
                        GroundDaylight.elevation(model, shape, face, tick) > .12,
                        "tick " + tick + " on face " + home);
                day++;
            }
            assertEquals(27, day);
            // The clock begins as the sun clears the horizon there: daylight from the first moment,
            // and for ten minutes.
            long sunrise = GroundDaylight.sunrise(model, shape);
            assertTrue(sunrise > 0 && sunrise < 3600, "sunrise " + sunrise);
            for (long tick = sunrise; tick <= sunrise + 12000; tick += 600)
                assertEquals(
                        1,
                        GroundSolar.daylight(GroundDaylight.elevation(model, shape, face, tick)),
                        1e-9,
                        "tick " + tick);
            for (var other : Face.values()) {
                double mine = 0, theirs = 0;
                for (int tick = 0; tick <= GroundDaylight.FIRST_DAY; tick += 600) {
                    mine +=
                            GroundSolar.daylight(
                                    GroundDaylight.elevation(model, shape, face, tick));
                    theirs +=
                            GroundSolar.daylight(
                                    GroundDaylight.elevation(model, shape, other, tick));
                }
                assertTrue(mine >= theirs);
            }
        }
    }

    @Test
    void aLivingPlanetHasTheFeaturesOfItsBiomesAndKeepsItsOwnOresTreesAndNoLakes()
            throws Exception {
        for (var name : java.util.List.of("temperate", "lush", "arid", "archipelago", "steppe")) {
            var g = type(name).planet(3, Optional.empty()).generation();
            assertTrue(g.features() && g.structures(), name);
        }
        for (var name : java.util.List.of("rocky", "desert", "ice")) {
            var g = type(name).planet(3, Optional.empty()).generation();
            assertFalse(g.features() || g.structures(), name);
        }
        var ruins = typeJson("desert");
        ruins.addProperty("structures", true);
        var made =
                PlanetType.CODEC
                        .parse(ops(), ruins)
                        .getOrThrow()
                        .planet(3, Optional.empty())
                        .generation();
        assertTrue(made.structures());
        assertFalse(made.features());
        var old = new PlanetGeneration("temperate", "ecology", 1, 1, 0, .115, 2048, 652);
        assertFalse(old.features() || old.structures());
        assertFalse(
                PlanetGeneration.CODEC
                        .encodeStart(JsonOps.INSTANCE, old)
                        .getOrThrow()
                        .getAsJsonObject()
                        .has("features"));
        var saved = PlanetGeneration.CODEC.encodeStart(JsonOps.INSTANCE, made).getOrThrow();
        assertEquals(made, PlanetGeneration.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow());
        for (var preset :
                java.util.List.of(
                        "native_exploration", "native_trio", "native_stars", "native_universe")) {
            var g = SystemChunkGenerator.CODEC.codec().parse(ops(), preset(preset)).getOrThrow();
            assertTrue(g.planet(0).generation().features(), preset);
            assertFalse(g.planet(1).generation().features(), preset);
            assertTrue(g.planet(0).generation().structures(), preset);
            assertFalse(g.planet(1).generation().structures(), preset);
        }
        assertTrue(
                SystemChunkGenerator.interior(128, 8, 8)
                        && SystemChunkGenerator.interior(128, 119, 119));
        assertFalse(
                SystemChunkGenerator.interior(128, 7, 60)
                        || SystemChunkGenerator.interior(128, 60, 120)
                        || SystemChunkGenerator.interior(128, -1, 60)
                        || SystemChunkGenerator.interior(128, 200, 60));
        var sets =
                com.google.gson.JsonParser.parseString(
                                java.nio.file.Files.readString(
                                        java.nio.file.Path.of(
                                                "src/main/resources/data/starfold/tags/worldgen/structure_set/planets.json")))
                        .getAsJsonObject();
        assertFalse(sets.get("replace").getAsBoolean());
        assertTrue(sets.getAsJsonArray("values").toString().contains("minecraft:villages"));
        assertFalse(sets.getAsJsonArray("values").toString().contains("stronghold"));
        assertEquals(
                java.util.Set.of(
                        net.minecraft.world.level.levelgen.GenerationStep.Decoration.LAKES,
                        net.minecraft.world.level.levelgen.GenerationStep.Decoration
                                .UNDERGROUND_ORES),
                PlanetDecoration.SKIPPED);
        var placed =
                lookup().lookupOrThrow(net.minecraft.core.registries.Registries.PLACED_FEATURE);
        var decoration =
                new PlanetDecoration(
                        new net.minecraft.world.level.biome.FixedBiomeSource(
                                lookup().lookupOrThrow(
                                                net.minecraft.core.registries.Registries.BIOME)
                                        .getOrThrow(Biomes.PLAINS)));
        assertTrue(
                decoration.tree(
                        placed.getOrThrow(
                                        net.minecraft.data.worldgen.placement.VegetationPlacements
                                                .TREES_PLAINS)
                                .value()));
        assertTrue(
                decoration.tree(
                        placed.getOrThrow(
                                        net.minecraft.data.worldgen.placement.VegetationPlacements
                                                .TREES_BIRCH_AND_OAK)
                                .value()));
        assertFalse(
                decoration.tree(
                        placed.getOrThrow(
                                        net.minecraft.data.worldgen.placement.VegetationPlacements
                                                .PATCH_GRASS_PLAIN)
                                .value()));
        assertFalse(
                decoration.tree(
                        placed.getOrThrow(
                                        net.minecraft.data.worldgen.placement.VegetationPlacements
                                                .FLOWER_DEFAULT)
                                .value()));
    }
}
