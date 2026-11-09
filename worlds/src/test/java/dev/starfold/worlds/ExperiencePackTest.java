package dev.starfold.worlds;

import dev.starfold.worlds.fixtures.ExperienceFixtures;

import dev.starfold.planet.*;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.mojang.serialization.*;

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
public class ExperiencePackTest extends ExperienceFixtures {
    @Test
    void experienceUsesOneLargeSunAndDerivedPeriods() throws Exception {
        var json =
                JsonParser.parseString(
                                Files.readString(
                                        Path.of(
                                                "worlds/src/main/resources/data/starfold_worlds/starfold/system/experience.json")))
                        .getAsJsonObject();
        var suns = json.getAsJsonArray("suns");
        assertEquals(1, suns.size());
        var sun = suns.get(0).getAsJsonObject();
        assertEquals(12000, sun.get("radius").getAsDouble());
        var planets = sun.getAsJsonArray("planets");
        assertEquals(8, planets.size());
        for (var planet : planets)
            assertFalse(planet.getAsJsonObject().getAsJsonObject("orbit").has("period"));
        assertEquals(1, sun.getAsJsonArray("gas_giants").size());
    }

    @Test
    void experiencePresetNamesArePackTranslations() throws Exception {
        for (String language : List.of("zh_cn", "en_us")) {
            var lang = json(Path.of("worlds/src/main/resources/assets/starfold_worlds/lang/" + language + ".json"));
            for (String preset : List.of("experience", "experience_named_preview")) {
                assertTrue(Files.exists(DATA.resolve("worldgen/world_preset/" + preset + ".json")));
                assertTrue(lang.has("generator.starfold_worlds." + preset));
            }
            assertFalse(lang.has("screen.starfold.experience_dependencies"));
        }
    }

    @Test
    void beyondHasEightSeparatedSystemsWithBoundedStorageAndDeepSpaceTravel() throws Exception {
        var generator = generator("experience");
        var home = generator.homeModel();
        var layout = generator.universe().orElseThrow().value();
        assertFalse(layout.endless());
        assertEquals(8, layout.minStars());
        assertEquals(8, layout.maxStars());
        assertEquals(5_200_000, layout.gap());
        assertEquals(2, layout.minPlanets());
        assertEquals(3, layout.maxPlanets());
        for (long seed : new long[] {0, 42, 12345}) {
            var universe = dev.starfold.universe.Universe.generate(seed, layout, home, true);
            assertEquals(8, universe.stars().size());
            assertTrue(universe.planets().size() >= 16 && universe.planets().size() <= 24);
            var stars = universe.stars();
            for (int i = 0; i < stars.size(); i++) {
                var star = stars.get(i);
                double distance = star.position().sub(home.origin()).length();
                double seconds = (distance - home.extent()) / dev.starfold.space.SpaceSpeed.DEEP_MAX;
                assertTrue(seconds >= 60 && seconds <= 180, "boundary-to-centre floor " + seconds);
                assertTrue(dev.starfold.universe.Universe.clearOfHome(star.position(),
                        home.orbit(0).radius() + 2 * layout.ring() + 8192, home));
                for (int j = 0; j < i; j++)
                    assertTrue(star.position().sub(stars.get(j).position()).length() >= layout.gap());
            }
            var all = universe.model(home);
            assertEquals(home.count() + universe.planets().size(), all.count());
            assertEquals(home.bodies().size() + 8 + universe.planets().size(), all.bodies().size());
            var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(
                    io.netty.buffer.Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                GroundPackets.Sky.CODEC.encode(buffer, new GroundPackets.Sky(0, all));
                assertTrue(buffer.readableBytes() < 64 * 1024);
                if (seed == 42)
                    System.out.printf("INTERSTELLAR systems=%d beyondPlanets=%d totalPlanets=%d skyBytes=%d homeExtent=%.0f%n",
                            stars.size(), universe.planets().size(), all.count(), buffer.readableBytes(), home.extent());
            } finally {
                buffer.release();
            }
        }
    }

    @Test
    void experienceUsesV2SingleStarKeplerLayoutAndReportsActualScale() throws Exception {
        for (var name : List.of("experience", "experience_named_preview"))
            assertNoOrbitPeriods(json(DATA.resolve("starfold/system/" + name + ".json")));
        var json = json(DATA.resolve("starfold/system/experience.json"));
        var definition = SystemDefinition.CODEC.parse(ops(), json).getOrThrow();
        definition.validate();
        var sun = definition.suns().getFirst();
        assertEquals(1, definition.suns().size());
        assertEquals(0, sun.position().length());
        assertEquals(12000, sun.radius());
        assertEquals(SystemDefinition.STAR_GM, sun.gm());
        assertEquals(new SystemDefinition.Home(0, 2), definition.home());
        assertNoOrbitPeriods(json);
        assertArrayEquals(
                new String[] {
                    "rocky", "lush", "home", "archipelago", "desert", "mushroom", "warden", "end"
                },
                sun.planets().stream()
                        .map(p -> p.type().unwrapKey().orElseThrow().location().getPath())
                        .toArray(String[]::new));
        double[] radii = {
            180000, 330000, SystemDefinition.HOME_ORBIT, 580000, 700000, 2200000, 3000000, 3800000
        };
        for (int i = 0; i < radii.length; i++) assertEquals(radii[i], sun.planets().get(i).orbit().radius());
        var home = sun.planets().get(2);
        assertEquals(384000, home.orbit().period(sun.gm()), 1e-6);
        assertEquals(42666.666666666664, home.spin().orElseThrow().period(), 1e-9);
        assertEquals(23.5, home.spin().orElseThrow().tilt());
        assertEquals(40000, home.moons().getFirst().orbit().radius());
        var gas = sun.gasGiants().getFirst();
        assertEquals(1400000, gas.orbit().radius());
        assertEquals(4096, gas.radius());
        assertEquals(
                List.of(30000.0, 50000.0, 80000.0),
                gas.moons().stream().map(p -> p.orbit().radius()).toList());

        var made = definition.materialize(42);
        var system = generator("experience");
        system.validate();
        system.materialize(42);
        assertFalse(system.startsWithShip());
        assertEquals(12, system.count());
        var model = made.sky().model();
        String[] slots = {
            "home", "rocky", "lush", "archipelago", "desert", "mushroom", "warden", "end",
            "asteroid", "basalt", "ice", "crystal"
        };
        var table =
                new StringBuilder(
                        "| Slot | Body | Orbit (blocks) | Period (ticks) | Mean solar day (ticks) | Sun diameter | Moon diameter from parent |\n|---:|---|---:|---:|---:|---:|---:|\n");
        for (int slot = 0; slot < slots.length; slot++) {
            var planet = made.planets().get(slot);
            assertEquals(slots[slot], planet.typeId().orElseThrow().getPath());
            var orbit = model.orbit(slot);
            double period = orbit.period();
            var rotation = model.spin(slot);
            var stellarOrbit = orbit;
            while (!model.bodies().get(stellarOrbit.parent()).light())
                stellarOrbit = (dev.starfold.chart.GroundEphemeris.Orbit)
                        model.bodies().get(stellarOrbit.parent()).motion();
            double solarDay = 1 / Math.abs((rotation.enabled() ? 1 / rotation.period() : 0)
                    - 1 / stellarOrbit.period());
            if (slot == 0) assertEquals(48000, solarDay, 1e-6);
            double sunAngle = 2 * Math.toDegrees(Math.asin(12000 / model.at(slot, 0).center().length()));
            var parent = model.bodies().get(orbit.parent());
            double moonAngle =
                    parent.light()
                            ? Double.NaN
                            : 2 * Math.toDegrees(
                                    Math.asin(planet.terrain().shape().radius() / orbit.radius()));
            table.append(
                    String.format(
                            java.util.Locale.ROOT,
                            "| %d | %s | %.0f | %.1f | %.1f | %.3f° | %s |%n",
                            slot,
                            slots[slot],
                            orbit.radius(),
                            period,
                            solarDay,
                            sunAngle,
                            Double.isNaN(moonAngle)
                                    ? "—"
                                    : String.format(java.util.Locale.ROOT, "%.3f°", moonAngle)));
        }
        System.out.println("V2_LAYOUT_TABLE\n" + table);
        System.out.printf(java.util.Locale.ROOT, "AMBER_FROM_BASALT %.6f degrees%n",
                2 * Math.toDegrees(Math.asin(gas.radius() / gas.moons().getFirst().orbit().radius())));
    }

    @Test
    void packAtmospheresAreExplicitAndGravityIsDerivedRatherThanApplied() {
        var expected =
                java.util.Map.ofEntries(
                        java.util.Map.entry(
                                "home",
                                new dev.starfold.worldgen.PlanetAtmosphere(1, 0x78A7FF, true)),
                        java.util.Map.entry(
                                "lush",
                                new dev.starfold.worldgen.PlanetAtmosphere(1, 0x78A7FF, true)),
                        java.util.Map.entry(
                                "archipelago",
                                new dev.starfold.worldgen.PlanetAtmosphere(1, 0x78A7FF, true)),
                        java.util.Map.entry(
                                "desert",
                                new dev.starfold.worldgen.PlanetAtmosphere(.12, 0xCF795B, false)),
                        java.util.Map.entry(
                                "ice",
                                new dev.starfold.worldgen.PlanetAtmosphere(.08, 0xB4D9F2, false)),
                        java.util.Map.entry(
                                "basalt",
                                new dev.starfold.worldgen.PlanetAtmosphere(.3, 0xA87568, false)),
                        java.util.Map.entry(
                                "crystal",
                                new dev.starfold.worldgen.PlanetAtmosphere(.05, 0xB29BE5, false)),
                        java.util.Map.entry(
                                "mushroom",
                                new dev.starfold.worldgen.PlanetAtmosphere(1.1, 0xB39BCB, true)),
                        java.util.Map.entry(
                                "warden",
                                new dev.starfold.worldgen.PlanetAtmosphere(.8, 0x689A9A, false)),
                        java.util.Map.entry("rocky", dev.starfold.worldgen.PlanetAtmosphere.NONE),
                        java.util.Map.entry(
                                "asteroid", dev.starfold.worldgen.PlanetAtmosphere.NONE),
                        java.util.Map.entry("end", dev.starfold.worldgen.PlanetAtmosphere.NONE));
        expected.forEach(
                (name, atmosphere) -> {
                    var t =
                            lookup.lookupOrThrow(PlanetType.KEY)
                                    .getOrThrow(
                                            ResourceKey.create(
                                                    PlanetType.KEY,
                                                    ResourceLocation.fromNamespaceAndPath(
                                                            "starfold_worlds", name)))
                                    .value();
                    assertTrue(t.environment().gravity().isEmpty(), name);
                    for (int size : new int[] {t.minSize(), t.maxSize()}) {
                        var environment = t.generation(size).environment();
                        assertEquals(atmosphere, environment.atmosphere(), name);
                        assertEquals(
                                Math.clamp(Math.sqrt(size / 1024.0), .35, 1.4),
                                environment.gravity(),
                                name);
                    }
                });
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
                assertEquals(policy.environment().atmosphere(), g.homeModel().body(i).atmosphere());
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
    void v2AmberAndMoonCatalogUseExpectedKeplerPaths() throws Exception {
        var definition =
                SystemDefinition.CODEC
                        .parse(ops(), json(DATA.resolve("starfold/system/experience.json")))
                        .getOrThrow();
        var sun = definition.suns().getFirst();
        var gas = sun.gasGiants().getFirst();
        assertEquals(4096, gas.radius());
        assertEquals(1400000, gas.orbit().radius());
        assertEquals(80 * 4096 * 4096, gas.gm());
        assertEquals(3, gas.moons().size());
        assertEquals(List.of(30000.0, 50000.0, 80000.0), gas.moons().stream().map(p -> p.orbit().radius()).toList());
        assertEquals(
                new dev.starfold.chart.GasGiant(1, 20261013, "planet.starfold_worlds.amber", 9987389, 15389090, 512, 1024),
                gas.gas());
        assertEquals(12, definition.materialize(42).sky().model().count());
        var celestial = definition.materialize(42).sky().model();
        var gasTarget = CelestialNavigation.targets(celestial).stream().filter(t -> t.body(celestial).appearance() == dev.starfold.chart.GroundEphemeris.Appearance.GAS).findFirst().orElseThrow();
        assertTrue(gasTarget.surfaceSlot(celestial).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> gasTarget.requireSurfaceSlot(celestial));
    }

    @Test
    void allTypesUseOneVersionAndTheirFixedLandformRecipes() throws Exception {
        var system=generator("experience");system.validate();system.materialize(42);
        for(int i=0;i<system.count();i++) {
            var planet=system.planet(i);var recipe=planet.generation().recipe();
            assertEquals(dev.starfold.worldgen.PlanetTerrain.VERSION,planet.version());
            assertEquals(recipe,planet.terrain().terrainPlan().recipe());
            var forms=recipe.landforms().stream().map(f->f.type()).toList();
            switch(planet.typeId().orElseThrow().getPath()) {
                case "rocky" -> {assertTrue(forms.contains("starfold:crater"));assertTrue(recipe.value("relief","mountains")>0);assertNull(planet.terrain().sea());}
                case "basalt" -> assertTrue(forms.contains("starfold:volcano"));
                case "desert" -> assertTrue(forms.contains("starfold:canyon"));
                case "ice" -> assertTrue(forms.contains("starfold:glacial_rift"));
                default -> assertFalse(recipe.biomes().isEmpty());
            }
        }
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
            assertEquals(
                    "starfold_worlds:home", identities.getFirst().type().orElseThrow().toString());
            String[] paths = {
                "rocky",
                "lush",
                "archipelago",
                "desert",
                "mushroom",
                "warden",
                "end",
                "asteroid",
                "basalt",
                "ice",
                "crystal"
            };
            for (int i = 1; i < 12; i++)
                assertEquals(
                        "starfold_worlds:" + paths[i - 1],
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
            "hearth", "anvil", "viridia", "pelagia", "saffron", "haven", "hush", "ivory",
            "fleck", "cinder", "rime", "prism"
        };
        String[] zh = {"炉心", "砧星", "苍翠", "群澜", "赭沙", "菌港", "寂痕", "象牙", "砾星", "烬岩", "霜原", "晶棱"};
        for (var language : List.of("zh_cn", "en_us")) {
            var lang =
                    json(Path.of("worlds/src/main/resources/assets/starfold_worlds/lang/" + language + ".json"));
            for (int i = 0; i < 12; i++) {
                var identity = system.planet(i).identity();
                String key = "planet.starfold_worlds." + names[i];
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
        var nativeJson = PlanetTypeFixtures.preset("native_universe").getAsJsonObject();
        var packHome =
                json(DATA.resolve("starfold/system/experience.json"))
                        .getAsJsonArray("suns")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonArray("planets")
                        .get(2)
                        .getAsJsonObject();
        assertEquals("starfold_worlds:home", packHome.get("type").getAsString());
        assertEquals("planet.starfold_worlds.hearth", packHome.get("name").getAsString());
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

    private static PlanetPackets.Profile transfer(PlanetPackets.Profile profile) {
        var prepared=dev.starfold.universe.PlanetPlanSync.prepare(profile,RegistryAccess.EMPTY);
        var raw=io.netty.buffer.Unpooled.buffer();
        try {
            var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(raw,RegistryAccess.EMPTY);
            PlanetPackets.Profile.CODEC.encode(buffer,prepared.profile());
            var metadata=PlanetPackets.Profile.CODEC.decode(buffer);assertFalse(buffer.isReadable());
            var received=new java.util.ArrayList<PlanetPackets.Profile>();var assembler=new dev.starfold.universe.PlanetPlanSync.Assembler();
            assembler.await(metadata,"worlds-test",received::add);
            for(var part:prepared.parts()) {
                buffer.clear();dev.starfold.universe.PlanetPlanSync.Part.CODEC.encode(buffer,part);
                assembler.accept(dev.starfold.universe.PlanetPlanSync.Part.CODEC.decode(buffer));assertFalse(buffer.isReadable());
            }
            assertEquals(1,received.size());return received.getFirst();
        } finally {raw.release();}
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
            for (int id : new int[] {2, 5}) {
                var g = system.planet(id);
                var profile=transfer(PlanetPackets.Profile.of(91,g));
                assertEquals(
                        id == 2 ? "minecraft:jungle" : "starfold_worlds:mushroom_haven",
                        profile.biome().toString());
                var remote = new PlanetRemoteView();
                remote.summary(
                        new PlanetRemotePackets.Summary(id, profile, 0, new long[0], new long[0]));
                assertEquals(profile.biome(), remote.snapshot().biome());
                assertEquals(profile.plan().orElseThrow().identity(),remote.snapshot().terrain().plan().identity());
                assertEquals(g.terrain().terrainPlan().recipe(),remote.snapshot().terrain().terrainPlan().recipe());
                for (var face : CubeSurfaceTopology.Face.values()) {
                    var pos = PlanetTint.position(id, face.ordinal(), 128, 130, 256);
                    assertEquals(
                            PlanetStorage.offset(id) + face.ordinal() * 4096 + 128, pos.getX());
                    assertEquals(PlanetStorage.offsetZ(id) + 256, pos.getZ());
                    var actual =
                            PlanetTint.biome(
                                    g.terrain(),
                                    remote.snapshot().biome(),
                                    face.ordinal(),
                                    130,
                                    258);
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
