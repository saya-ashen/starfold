package dev.starfold.planet;

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
import dev.starfold.universe.PlanetChunkGenerator;
import dev.starfold.universe.PlanetPackets;
import dev.starfold.universe.PlanetType;
import dev.starfold.universe.PlanetTypeTest;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.universe.SystemDefinition;
import dev.starfold.universe.UniverseLayout;
import dev.starfold.worldgen.PlanetGeneration;
import dev.starfold.worldgen.PlanetHeightCache;
import dev.starfold.worldgen.PlanetTerrainTest;
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
public class ExperiencePackTest {
    static final Path DATA = Path.of("src/main/resources/data/starfold_pack");
    static HolderLookup.Provider lookup;

    static JsonObject json(Path p) throws Exception {
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    static DynamicOps<JsonElement> ops() {
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
                                "starfold_pack", p.getFileName().toString().replace(".json", "")),
                        value);
            }
        }
        var old = lookup;
        lookup =
                HolderLookup.Provider.create(
                        java.util.stream.Stream.concat(
                                old.listRegistries()
                                        .filter(k -> !k.equals(key))
                                        .map(old::lookupOrThrow),
                                java.util.stream.Stream.of(registry.freeze().asLookup())));
    }

    @BeforeAll
    public static void boot() throws Exception {
        PlanetTerrainTest.registry();
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
        for (var id : List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"))
            assertNotNull(
                    net.minecraft.world.level.dimension.LevelStem.CODEC
                            .parse(ops(), dimensions.get(id))
                            .getOrThrow());
        assertEquals(
                "starfold:native_deep",
                dimensions.getAsJsonObject("starfold:native_planet").get("type").getAsString());
        return SystemChunkGenerator.CODEC
                .codec()
                .parse(ops(), dimensions.getAsJsonObject("starfold:native_planet").get("generator"))
                .getOrThrow();
    }

    @Test
    void allNewRegistryDocumentsDecodeAndBothPresetsBuildTwelvePlanets() throws Exception {
        for (var name : List.of("experience", "experience_named_preview")) {
            var g = generator(name);
            g.validate();
            g.materialize(42);
            assertEquals(12, g.count());
            assertEquals(416, g.planet(8).generation().size());
            for (int i = 0; i < 12; i++) {
                var policy = g.planet(i).generation();
                assertNotNull(
                        PlanetGeneration.CODEC
                                .parse(
                                        JsonOps.INSTANCE,
                                        PlanetGeneration.CODEC
                                                .encodeStart(JsonOps.INSTANCE, policy)
                                                .getOrThrow())
                                .getOrThrow());
            }
        }
        assertEquals(12, lookup.lookupOrThrow(PlanetType.KEY).listElements().count());
    }

    @Test
    void actualTwelvePlanetPresetSavesAndSendsEveryIdentity() throws Exception {
        var original = generator("experience");
        var saved = SystemChunkGenerator.CODEC.codec().encodeStart(ops(), original).getOrThrow();
        var system = SystemChunkGenerator.CODEC.codec().parse(ops(), saved).getOrThrow();
        GroundServer.system(system);
        try {
            var identities = PlanetSystem.identities();
            assertEquals(12, identities.size());
            assertTrue(identities.getFirst().type().isEmpty());
            String[] paths = {
                "lush",
                "warden",
                "rocky",
                "ice",
                "desert",
                "archipelago",
                "mushroom",
                "asteroid",
                "basalt",
                "crystal",
                "end"
            };
            for (int i = 1; i < 12; i++)
                assertEquals(
                        "starfold_pack:" + paths[i - 1],
                        identities.get(i).type().orElseThrow().toString());
            var packet =
                    new GroundPackets.Shapes(
                            java.util.stream.IntStream.range(0, 12)
                                    .map(i -> system.planet(i).generation().size())
                                    .toArray(),
                            java.util.stream.IntStream.range(0, 12)
                                    .map(i -> system.planet(i).generation().depth())
                                    .toArray(),
                            system.names(),
                            identities);
            var buf =
                    new net.minecraft.network.RegistryFriendlyByteBuf(
                            io.netty.buffer.Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                GroundPackets.Shapes.CODEC.encode(buf, packet);
                assertEquals(identities, GroundPackets.Shapes.CODEC.decode(buf).identities());
            } finally {
                buf.release();
            }
        } finally {
            PlanetSystem.reset();
        }
    }

    @Test
    void namedExperiencePlanetsLocalizeByTypeAndSurviveSave() throws Exception {
        var system = generator("experience");
        system =
                SystemChunkGenerator.CODEC
                        .codec()
                        .parse(
                                ops(),
                                SystemChunkGenerator.CODEC
                                        .codec()
                                        .encodeStart(ops(), system)
                                        .getOrThrow())
                        .getOrThrow();
        String[] names = {
            "hearth", "viridia", "hush", "anvil", "rime", "saffron", "pelagia", "haven", "fleck",
            "cinder", "prism", "ivory"
        };
        String[] zh = {"炉心", "苍翠", "寂痕", "砧星", "霜原", "赭沙", "群澜", "菌港", "砾星", "烬岩", "晶棱", "象牙"};
        for (var language : List.of("zh_cn", "en_us")) {
            var lang =
                    json(Path.of("src/main/resources/assets/starfold/lang/" + language + ".json"));
            for (int i = 0; i < 12; i++) {
                var identity = system.planet(i).identity();
                String key = "planet.starfold_pack." + names[i];
                assertEquals(key, identity.name().orElseThrow());
                assertTrue(lang.has(key));
                assertEquals(
                        language.equals("zh_cn")
                                ? zh[i]
                                : Character.toUpperCase(names[i].charAt(0)) + names[i].substring(1),
                        identity.label(k -> lang.get(k).getAsString()));
            }
        }
        assertEquals("H · 砾星 · 9/12", PlanetIdentity.caption(7, "砾星", 9, 12, 240, String::length));
    }

    @Test
    void homeIsExactlyTheOriginalAndSampledColumnsAreIdentical() throws Exception {
        var nativeJson = PlanetTypeTest.preset("native_universe").getAsJsonObject();
        var pack =
                json(DATA.resolve("worldgen/world_preset/experience.json"))
                        .getAsJsonObject("dimensions")
                        .getAsJsonObject("starfold:native_planet")
                        .getAsJsonObject("generator");
        var packHome = pack.getAsJsonArray("planets").get(0).getAsJsonObject().deepCopy();
        assertEquals("planet.starfold_pack.hearth", packHome.remove("name").getAsString());
        assertEquals(nativeJson.getAsJsonArray("planets").get(0), packHome);
        var home =
                PlanetChunkGenerator.CODEC
                        .codec()
                        .parse(ops(), nativeJson.getAsJsonArray("planets").get(0))
                        .getOrThrow();
        var actual = generator("experience").planet(0);
        assertEquals(home.seed(), actual.seed());
        for (var face : CubeSurfaceTopology.Face.values())
            for (int x : new int[] {1000, 1024, 1040})
                for (int z : new int[] {1000, 1024, 1040})
                    for (int y = -528; y < 240; y++)
                        assertEquals(
                                home.terrain().state(face, x, y, z),
                                actual.terrain().state(face, x, y, z));
    }

    @Test
    void everyInstalledTypeFarSurfaceMatchesGeneratedTop() throws Exception {
        for (var h : lookup.lookupOrThrow(PlanetType.KEY).listElements().toList()) {
            var g = h.value().planet(20261009L, Optional.of(h.value().minSize()));
            var t = g.terrain();
            var cache = new PlanetHeightCache(t);
            for (var face : CubeSurfaceTopology.Face.values())
                for (int a : new int[] {1, 3, 5, 7})
                    for (int b : new int[] {1, 3, 5, 7}) {
                        int x = t.shape().size() * a / 8,
                                z = t.shape().size() * b / 8,
                                y = t.surfaceY(face, x, z);
                        assertEquals(
                                t.state(face, x, y, z),
                                cache.surfaceState(face, x, z),
                                h.key() + " " + face + " " + x + "," + z);
                    }
        }
    }

    @Test
    void observedPlanetBiomeSurvivesWireAndRemoteCacheWithoutLoadedChunks() throws Exception {
        var system = generator("experience");
        system.materialize(20261009L);
        PlanetShape.system(
                java.util.stream.IntStream.range(0, 12)
                        .map(i -> system.planet(i).generation().size())
                        .toArray(),
                java.util.stream.IntStream.range(0, 12)
                        .map(i -> system.planet(i).generation().depth())
                        .toArray());
        try {
            for (int id : new int[] {1, 7}) {
                var g = system.planet(id);
                var profile = PlanetPackets.Profile.of(91, g);
                var buffer =
                        new net.minecraft.network.RegistryFriendlyByteBuf(
                                io.netty.buffer.Unpooled.buffer(), RegistryAccess.EMPTY);
                try {
                    PlanetPackets.Profile.CODEC.encode(buffer, profile);
                    profile = PlanetPackets.Profile.CODEC.decode(buffer);
                    assertEquals(0, buffer.readableBytes());
                } finally {
                    buffer.release();
                }
                assertEquals(
                        id == 1 ? "minecraft:jungle" : "starfold_pack:mushroom_haven",
                        profile.biome().toString());
                var remote = new PlanetRemoteView();
                remote.summary(
                        new PlanetRemotePackets.Summary(id, profile, 0, new long[0], new long[0]));
                assertEquals(profile.biome(), remote.snapshot().biome());
                for (var face : CubeSurfaceTopology.Face.values()) {
                    var pos = PlanetTint.position(id, face.ordinal(), 128, 130, 256);
                    assertEquals(
                            PlanetStorage.offset(id) + face.ordinal() * 4096 + 128, pos.getX());
                    assertEquals(PlanetStorage.offsetZ(id) + 256, pos.getZ());
                    var actual =
                            PlanetTint.biome(
                                    g.terrain(), profile.biome(), face.ordinal(), 128, 256);
                    assertEquals(
                            g.getBiomeSource()
                                    .getNoiseBiome((face.ordinal() * 4096 + 128) / 4, 32, 64, null)
                                    .unwrapKey()
                                    .orElseThrow(),
                            actual);
                }
            }
        } finally {
            PlanetSystem.reset();
        }
    }

    @Test
    void homeCoverRetainsOldSurfaceAndUsesTheGeneratedBiomeAtQuartCenters() throws Exception {
        var g = generator("experience").planet(0);
        var t = g.terrain();
        var cache = new PlanetHeightCache(t);
        for (var face : CubeSurfaceTopology.Face.values())
            for (int x = 66; x < t.shape().size() - 64; x += 64)
                for (int z = 66; z < t.shape().size() - 64; z += 64) {
                    assertEquals(
                            t.surfaceState(face, x, z),
                            cache.surfaceState(face, x, z),
                            "old ecology cover state is unchanged");
                    int y = t.surfaceY(face, x, z);
                    assertEquals(t.state(face, x, y, z), cache.surfaceState(face, x, z));
                    var generated =
                            g.getBiomeSource()
                                    .getNoiseBiome(
                                            (face.ordinal() * 4096 + x) / 4, y / 4, z / 4, null)
                                    .unwrapKey()
                                    .orElseThrow();
                    assertEquals(
                            generated,
                            PlanetTint.biome(
                                    t,
                                    ResourceLocation.withDefaultNamespace("plains"),
                                    face.ordinal(),
                                    x,
                                    z));
                }
    }

    @Test
    void allFarCoverConsumersUseAuthoritativeSurface() throws Exception {
        var renderer = Sources.text("GroundRenderer");
        assertFalse(
                renderer.contains("terrain.solidTop("),
                "far cover must not replace a palette with stone");
        for (var file : List.of("GroundExterior", "PlanetRemoteRenderer"))
            assertTrue(Sources.text(file).contains("heights.surfaceState("));
    }

    @Test
    void everyPaletteAndMineralTableValidatesAcrossTypeSizeRange() {
        lookup.lookupOrThrow(PlanetType.KEY)
                .listElements()
                .forEach(
                        h -> {
                            var t = h.value();
                            for (int size = t.minSize(); size <= t.maxSize(); size += 16) {
                                var g = t.generation(size);
                                assertNotNull(
                                        PlanetGeneration.CODEC
                                                .parse(
                                                        JsonOps.INSTANCE,
                                                        PlanetGeneration.CODEC
                                                                .encodeStart(JsonOps.INSTANCE, g)
                                                                .getOrThrow())
                                                .getOrThrow());
                            }
                        });
    }
}
