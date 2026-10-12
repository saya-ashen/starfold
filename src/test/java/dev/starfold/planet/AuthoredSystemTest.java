package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.GroundPackets;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.GroundEphemeris;
import dev.starfold.sky.GroundEphemerisSave;
import dev.starfold.sky.GroundSolar;
import dev.starfold.space.Space;
import dev.starfold.space.SpaceView;
import dev.starfold.worldgen.PlanetEcologyTest;
import dev.starfold.worldgen.SystemChunkGenerator;

import org.junit.jupiter.api.Test;

import java.util.List;

class AuthoredSystemTest {
    @org.junit.jupiter.api.BeforeAll
    static void boot() {
        PlanetEcologyTest.boot();
    }

    static final java.nio.file.Path PACK =
            java.nio.file.Path.of("src/test/resources/datapacks/example_system");

    static com.google.gson.JsonObject json(String path) throws Exception {
        return com.google.gson.JsonParser.parseString(
                        java.nio.file.Files.readString(PACK.resolve(path)))
                .getAsJsonObject();
    }

    static com.google.gson.JsonObject definition() throws Exception {
        return json("data/handwritten/starfold/system/twin_lanterns.json");
    }

    static com.google.gson.JsonObject preset() throws Exception {
        return json("data/handwritten/worldgen/world_preset/twin_lanterns.json")
                .getAsJsonObject("dimensions")
                .getAsJsonObject("starfold:native_planet")
                .getAsJsonObject("generator");
    }

    static net.minecraft.core.HolderLookup.Provider types() throws Exception {
        var base = PlanetTypeTest.lookup();
        var types =
                new net.minecraft.core.MappedRegistry<PlanetType>(
                        PlanetType.KEY, com.mojang.serialization.Lifecycle.stable());
        base.lookupOrThrow(PlanetType.KEY)
                .listElements()
                .forEach(h -> net.minecraft.core.Registry.register(types, h.key(), h.value()));
        net.minecraft.core.Registry.register(
                types,
                net.minecraft.resources.ResourceLocation.parse("handwritten:ash"),
                PlanetType.CODEC
                        .parse(
                                PlanetTypeTest.ops(),
                                json("data/handwritten/starfold/planet_type/ash.json"))
                        .getOrThrow());
        return net.minecraft.core.HolderLookup.Provider.create(
                java.util.stream.Stream.concat(
                        base.listRegistries()
                                .filter(k -> !k.equals(PlanetType.KEY))
                                .map(base::lookupOrThrow),
                        java.util.stream.Stream.of(types.freeze().asLookup())));
    }

    static com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops() throws Exception {
        return net.minecraft.resources.RegistryOps.create(
                com.mojang.serialization.JsonOps.INSTANCE, types());
    }

    static SystemDefinition read(com.google.gson.JsonObject json) throws Exception {
        return SystemDefinition.CODEC.parse(ops(), json).getOrThrow();
    }

    static SystemChunkGenerator generator(
            com.google.gson.JsonObject definition, com.google.gson.JsonObject preset)
            throws Exception {
        var types = types();
        var systems =
                new net.minecraft.core.MappedRegistry<SystemDefinition>(
                        SystemDefinition.KEY, com.mojang.serialization.Lifecycle.stable());
        var d =
                SystemDefinition.CODEC
                        .parse(
                                net.minecraft.resources.RegistryOps.create(
                                        com.mojang.serialization.JsonOps.INSTANCE, types),
                                definition)
                        .getOrThrow();
        net.minecraft.core.Registry.register(
                systems,
                net.minecraft.resources.ResourceLocation.parse("handwritten:twin_lanterns"),
                d);
        var loaded =
                net.minecraft.core.HolderLookup.Provider.create(
                        java.util.stream.Stream.concat(
                                types.listRegistries().map(types::lookupOrThrow),
                                java.util.stream.Stream.of(systems.freeze().asLookup())));
        return SystemChunkGenerator.CODEC
                .codec()
                .parse(
                        net.minecraft.resources.RegistryOps.create(
                                com.mojang.serialization.JsonOps.INSTANCE, loaded),
                        preset)
                .getOrThrow();
    }

    /** The reference is kept in two languages: both must show the same files, and neither may lose a section. */
    @Test
    void bothLanguagesOfTheReferenceShowTheSameExamplesAndSections() throws Exception {
        var english = java.nio.file.Files.readString(java.nio.file.Path.of("docs/DATAPACKS.md"));
        var chinese =
                java.nio.file.Files.readString(java.nio.file.Path.of("docs/DATAPACKS_zh-CN.md"));
        var block =
                java.util.regex.Pattern.compile(
                        "```json\\s*\\n(.*?)\\n```", java.util.regex.Pattern.DOTALL);
        var heading = java.util.regex.Pattern.compile("(?m)^(#{1,4}) ");
        assertEquals(
                block.matcher(english).results().map(m -> m.group(1)).toList(),
                block.matcher(chinese).results().map(m -> m.group(1)).toList());
        assertEquals(9, block.matcher(english).results().count());
        assertEquals(
                heading.matcher(english).results().map(m -> m.group(1)).toList(),
                heading.matcher(chinese).results().map(m -> m.group(1)).toList());
        assertFalse(
                english.codePoints()
                        .anyMatch(
                                c ->
                                        c >= 0x4E00
                                                && c <= 0x9FFF
                                                && c != '简'
                                                && c != '体'
                                                && c != '中'
                                                && c != '文'));
    }

    @Test
    void theExamplePackIsCopiedExactlyAndDecodesWithoutJavaPresetRegistration() throws Exception {
        try (var files =
                java.nio.file.Files.walk(java.nio.file.Path.of("tools/datapacks/example_system"))) {
            for (var file : files.filter(java.nio.file.Files::isRegularFile).toList())
                assertArrayEquals(
                        java.nio.file.Files.readAllBytes(file),
                        java.nio.file.Files.readAllBytes(
                                PACK.resolve(
                                        java.nio.file.Path.of("tools/datapacks/example_system")
                                                .relativize(file))));
        }
        var text = java.nio.file.Files.readString(java.nio.file.Path.of("docs/DATAPACKS.md"));
        text =
                text.substring(
                        text.indexOf("## Example 2:"), text.indexOf("### Using it in a new world"));
        var blocks =
                java.util.regex.Pattern.compile(
                                "#### `([^`]+)`\\s*```json\\s*\\n(.*?)\\n```",
                                java.util.regex.Pattern.DOTALL)
                        .matcher(text);
        int examples = 0;
        while (blocks.find()) {
            assertEquals(
                    json(blocks.group(1)), com.google.gson.JsonParser.parseString(blocks.group(2)));
            examples++;
        }
        assertEquals(5, examples);
        var dimensions =
                json("data/handwritten/worldgen/world_preset/twin_lanterns.json")
                        .getAsJsonObject("dimensions");
        assertEquals(4, dimensions.size());
        for (String id :
                List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"))
            assertNotNull(
                    net.minecraft.world.level.dimension.LevelStem.CODEC
                            .parse(
                                    net.minecraft.resources.RegistryOps.create(
                                            com.mojang.serialization.JsonOps.INSTANCE,
                                            net.minecraft.data.registries.VanillaRegistries
                                                    .createLookup()),
                                    dimensions.get(id))
                            .getOrThrow());
        assertEquals(48, json("pack.mcmeta").getAsJsonObject("pack").get("pack_format").getAsInt());
        assertEquals(
                "handwritten:twin_lanterns",
                json("data/minecraft/tags/worldgen/world_preset/normal.json")
                        .getAsJsonArray("values")
                        .get(0)
                        .getAsString());
        var system = generator(definition(), preset());
        system.validate();
        system.materialize(42);
        assertEquals(5, system.count());
        assertArrayEquals(new int[] {3, 2}, system.groups());
        assertEquals(List.of("Aster", "Cinder", "Rime", "D", "Dune"), system.names());
        assertTrue(system.universe().isEmpty());
        assertEquals(20261001, system.planet(0).seed());
        assertEquals(768, system.planet(1).generation().size());
        assertEquals(
                net.minecraft.world.level.block.Blocks.TUFF,
                system.planet(1).generation().palette().rock());
    }

    @Test
    void savedGeneratorsAndSkiesNeedNeitherSystemNorTypeRegistryAfterMaterializing()
            throws Exception {
        var original = generator(definition(), preset());
        original.materialize(42);
        var fullOps = ops();
        var saved = SystemChunkGenerator.CODEC.codec().encodeStart(fullOps, original).getOrThrow();
        assertFalse(saved.getAsJsonObject().has("system"));
        assertTrue(saved.getAsJsonObject().has("celestial"));
        assertTrue(
                saved.toString()
                        .contains(
                                "handwritten:ash")); // identity survives; registry is still
                                                     // unnecessary
        var bare =
                net.minecraft.resources.RegistryOps.create(
                        com.mojang.serialization.JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        var restored = SystemChunkGenerator.CODEC.codec().parse(bare, saved).getOrThrow();
        restored.materialize(99999);
        assertEquals(original.homeModel(), restored.homeModel());
        assertEquals(original.names(), restored.names());
        assertEquals(
                saved, SystemChunkGenerator.CODEC.codec().encodeStart(bare, restored).getOrThrow());
        for (int p = 0; p < original.count(); p++)
            assertEquals(original.planet(p).seed(), restored.planet(p).seed());
        var nbt =
                net.minecraft.resources.RegistryOps.create(
                        net.minecraft.nbt.NbtOps.INSTANCE, PlanetEcologyTest.lookup);
        var tag = SystemChunkGenerator.CODEC.codec().encodeStart(nbt, restored).getOrThrow();
        assertEquals(
                restored.homeModel(),
                SystemChunkGenerator.CODEC.codec().parse(nbt, tag).getOrThrow().homeModel());
        var clock = new GroundClock(new GroundEphemerisSave(original.homeModel(), 1234));
        var copy = GroundClock.load(clock.save(new net.minecraft.nbt.CompoundTag(), null), null);
        assertEquals(original.homeModel(), copy.clock.model);
        assertEquals(1234, copy.clock.tick());
    }

    @Test
    void defaultsAndAHomeOnTheSecondSunAreExplicitNotHiddenABAssumptions() throws Exception {
        var d = definition();
        d.remove("home");
        planet(d, 0, 2).getAsJsonObject("orbit").remove("phase");
        planet(d, 0, 2).getAsJsonObject("spin").remove("tilt");
        var defaults = read(d);
        assertEquals(new SystemDefinition.Home(0, 0), defaults.home());
        assertEquals(0, defaults.suns().getFirst().planets().get(2).orbit().phase());
        assertEquals(0, defaults.suns().getFirst().planets().get(2).spin().orElseThrow().tilt());
        d.add("home", com.google.gson.JsonParser.parseString("{\"sun\":1,\"planet\":1}"));
        for (int p = 0; p < 2; p++) {
            var orbit = planet(d, 1, p).getAsJsonObject("orbit");
            orbit.addProperty("radius", 8192);
            orbit.addProperty("period", 36000);
            orbit.addProperty("phase", p * Math.PI);
        }
        var made = read(d).materialize(42);
        assertEquals("Dune", made.sky().names().getFirst());
        assertEquals(new Point(60000, 4000, -20000), made.sky().model().center());
        assertEquals(420, made.sky().model().sun().radius());
        assertEquals(List.of(2, 3), made.sky().groups());
        assertEquals(SystemDefinition.derivedSeed(42, 4), made.planets().getFirst().seed());
        assertEquals("Aster", made.sky().names().get(2));
    }

    @Test
    void selectedDimensionsRejectCloseOrbitsBeforeSeedBindingOrSaving() throws Exception {
        var data = definition();
        planet(data, 0, 2).getAsJsonObject("orbit").addProperty("radius", 9000);
        var invalid = generator(data, preset());
        var dimensions =
                new net.minecraft.core.MappedRegistry<
                        net.minecraft.world.level.dimension.LevelStem>(
                        net.minecraft.core.registries.Registries.LEVEL_STEM,
                        com.mojang.serialization.Lifecycle.stable());
        var type =
                PlanetEcologyTest.lookup
                        .lookupOrThrow(net.minecraft.core.registries.Registries.DIMENSION_TYPE)
                        .getOrThrow(
                                net.minecraft.world.level.dimension.BuiltinDimensionTypes
                                        .OVERWORLD);
        net.minecraft.core.Registry.register(
                dimensions,
                net.minecraft.resources.ResourceLocation.parse("handwritten:test"),
                new net.minecraft.world.level.dimension.LevelStem(type, invalid));
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> SystemDefinition.validateDimensions(dimensions.freeze()));
        assertTrue(
                error.getMessage()
                        .contains(
                                "dimension handwritten:test system: sun 0 planets 0 and 2 orbits too close"));
        assertEquals(0, invalid.count());
        var valid = generator(definition(), preset());
        var good =
                new net.minecraft.core.MappedRegistry<
                        net.minecraft.world.level.dimension.LevelStem>(
                        net.minecraft.core.registries.Registries.LEVEL_STEM,
                        com.mojang.serialization.Lifecycle.stable());
        net.minecraft.core.Registry.register(
                good,
                net.minecraft.resources.ResourceLocation.parse("handwritten:test"),
                new net.minecraft.world.level.dimension.LevelStem(type, valid));
        SystemDefinition.validateDimensions(good.freeze());
        assertEquals(0, valid.count());
        valid.materialize(42);
        assertEquals(5, valid.count());
    }

    @Test
    void seedBindingReallyRunsThroughTheGeneratorStateHook() throws Exception {
        var system = generator(definition(), preset());
        var lookup = PlanetEcologyTest.lookup;
        var random =
                net.minecraft.world.level.levelgen.RandomState.create(
                        net.minecraft.world.level.levelgen.NoiseGeneratorSettings.dummy(),
                        lookup.lookupOrThrow(net.minecraft.core.registries.Registries.NOISE),
                        7654);
        assertNotNull(
                system.createState(
                        lookup.lookupOrThrow(
                                net.minecraft.core.registries.Registries.STRUCTURE_SET),
                        random,
                        7654));
        assertEquals(SystemDefinition.derivedSeed(7654, 1), system.planet(1).seed());
        assertTrue(
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        system.bind(
                                                java.util.Collections.nCopies(
                                                        1596, system.planet(1))))
                        .getMessage()
                        .contains("1600"));
        assertEquals(5, system.count());
    }

    @Test
    void seedsAreWorldAndSourceIndexDerivedAndBindingCannotMoveAnExistingPlanet() throws Exception {
        var first = generator(definition(), preset());
        var same = generator(definition(), preset());
        var other = generator(definition(), preset());
        first.materialize(42);
        same.materialize(42);
        other.materialize(43);
        for (int p = 0; p < 5; p++) {
            assertEquals(first.planet(p).seed(), same.planet(p).seed());
            if (p > 0) {
                assertEquals(SystemDefinition.derivedSeed(42, p), first.planet(p).seed());
                assertNotEquals(first.planet(p).seed(), other.planet(p).seed());
            }
        }
        first.materialize(43);
        assertEquals(same.planet(1).seed(), first.planet(1).seed());
        var data = definition();
        data.getAsJsonObject("home").addProperty("planet", 1);
        var swapped = generator(data, preset());
        swapped.materialize(42);
        assertEquals("Cinder", swapped.names().getFirst());
        assertEquals(first.planet(1).seed(), swapped.planet(0).seed());
        assertEquals(20261001, swapped.planet(1).seed());
        assertEquals(0, first.homeModel().at(0, 0).center().x(), 1e-8);
        assertEquals(-8192, swapped.homeModel().at(0, 0).center().z(), 1e-8);
    }

    @Test
    void everyAuthoredPlanetFollowsItsJsonAtZeroQuarterAndOnePeriod() throws Exception {
        var data = read(definition());
        var model = data.materialize(42).sky().model();
        int id = 0;
        for (var sun : data.suns())
            for (var planet : sun.planets()) {
                var path = planet.orbit();
                for (double tick : new double[] {0, path.period() / 4, path.period()}) {
                    double angle = path.phase() + 2 * Math.PI * tick / path.period();
                    var expected =
                            sun.position()
                                    .add(
                                            new Point(
                                                    Math.sin(angle) * path.radius(),
                                                    0,
                                                    Math.cos(angle) * path.radius()));
                    assertTrue(
                            expected.sub(model.at(id, tick).center()).length() < 1e-8,
                            "planet " + id + " tick " + tick);
                    assertEquals(
                            2 * Math.PI * path.radius() / path.period(),
                            model.at(id, tick).velocity().length(),
                            1e-9);
                }
                id++;
            }
        assertEquals(new Point(60000, 4000, -20000), model.stars().getFirst().position());
        assertEquals(420, model.stars().getFirst().radius());
        assertEquals(512, model.sun().radius());
        assertTrue(model.a().enabled());
        assertEquals(48000, model.a().period());
        assertEquals(Math.sin(Math.toRadians(23.5)), model.a().axis().x());
        assertFalse(model.b().enabled());
    }

    @Test
    void registryAndPresetRoundTripsCanHappenBeforeTheWorldSeedExists() throws Exception {
        var system = generator(definition(), preset());
        var encoded = SystemChunkGenerator.CODEC.codec().encodeStart(ops(), system).getOrThrow();
        assertTrue(encoded.getAsJsonObject().get("system").isJsonObject());
        var bare =
                net.minecraft.resources.RegistryOps.create(
                        com.mojang.serialization.JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        var copied = SystemChunkGenerator.CODEC.codec().parse(bare, encoded).getOrThrow();
        assertNotNull(copied.getBiomeSource());
        assertThrows(IllegalStateException.class, copied::homeModel);
        assertThrows(IllegalStateException.class, () -> copied.planet(0));
        copied.materialize(7);
        assertEquals(5, copied.count());
        var d = read(definition());
        var round =
                SystemDefinition.CODEC
                        .parse(bare, SystemDefinition.CODEC.encodeStart(ops(), d).getOrThrow())
                        .getOrThrow();
        assertEquals(d.materialize(7).sky(), round.materialize(7).sky());
    }

    @Test
    void unboundSystemHolderIsNotDereferencedByGeneratorDecode() throws Exception {
        var base = types();
        var systems =
                new net.minecraft.core.MappedRegistry<SystemDefinition>(
                        SystemDefinition.KEY, com.mojang.serialization.Lifecycle.stable());
        var key =
                net.minecraft.resources.ResourceKey.create(
                        SystemDefinition.KEY,
                        net.minecraft.resources.ResourceLocation.parse(
                                "handwritten:twin_lanterns"));
        systems.createRegistrationLookup().getOrThrow(key);
        var loaded =
                net.minecraft.core.HolderLookup.Provider.create(
                        java.util.stream.Stream.concat(
                                base.listRegistries().map(base::lookupOrThrow),
                                java.util.stream.Stream.of(systems.asLookup())));
        var ops =
                net.minecraft.resources.RegistryOps.create(
                        com.mojang.serialization.JsonOps.INSTANCE, loaded);
        var system = SystemChunkGenerator.CODEC.codec().parse(ops, preset()).getOrThrow();
        assertNotNull(system.getBiomeSource());
        net.minecraft.core.Registry.register(
                systems, key, SystemDefinition.CODEC.parse(ops, definition()).getOrThrow());
        systems.freeze();
        system.validate();
        system.materialize(123);
        assertEquals(5, system.count());
    }

    @Test
    void orbitalCameraIncludesBothAuthoredSunsInsteadOfClippingTheSecond() throws Exception {
        var model = read(definition()).materialize(42).sky().model();
        var eye = model.at(0, 1234).center().add(new Point(0, 5400, 0));
        double distant = model.everyStar().get(1).position().sub(eye).length() + 420;
        assertTrue(distant > GroundOrbit.LIMIT + 8 * GroundOrbit.RADIUS);
        assertTrue(SpaceView.chartFar(4096, 160) > distant);
        assertEquals(SpaceView.FAR, SpaceView.chartFar(4096, 160));
        assertEquals(224, SpaceView.chartFar(GroundOrbit.START, 160));
        assertEquals(224, SpaceView.chartFar(0, 160));
        assertTrue(Sources.text("GroundFlightRenderMixin").contains("SpaceView.chartFar("));
    }

    @Test
    void authoredSkyAndNamesSurviveTheActualWire() throws Exception {
        var system = generator(definition(), preset());
        system.materialize(42);
        var buffer =
                new net.minecraft.network.RegistryFriendlyByteBuf(
                        io.netty.buffer.Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            var sky = new GroundPackets.Sky(0, system.homeModel());
            GroundPackets.Sky.CODEC.encode(buffer, sky);
            assertEquals(sky, GroundPackets.Sky.CODEC.decode(buffer));
            int[] sizes = new int[5], depths = new int[5];
            for (int p = 0; p < 5; p++) {
                sizes[p] = system.planet(p).generation().size();
                depths[p] = system.planet(p).generation().depth();
            }
            var shapes = new GroundPackets.Shapes(sizes, depths, system.names());
            GroundPackets.Shapes.CODEC.encode(buffer, shapes);
            var copy = GroundPackets.Shapes.CODEC.decode(buffer);
            assertArrayEquals(sizes, copy.sizes());
            assertEquals(system.names(), copy.names());
            assertEquals("Aster", PlanetSystem.name(0));
            assertEquals("Dune", PlanetSystem.name(4));
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
            PlanetShape.reset();
        }
        assertEquals("A", PlanetSystem.name(0));
    }

    @Test
    void beyondIsSnapshottedAndNeverPlacesItsSunInsideTheAuthoredEnvelope() throws Exception {
        var json = definition();
        json.addProperty("beyond", "starfold:default");
        var system = generator(json, preset());
        system.materialize(45);
        var saved = SystemChunkGenerator.CODEC.codec().encodeStart(ops(), system).getOrThrow();
        assertTrue(saved.getAsJsonObject().get("universe").isJsonObject());
        var bare =
                net.minecraft.resources.RegistryOps.create(
                        com.mojang.serialization.JsonOps.INSTANCE, PlanetEcologyTest.lookup);
        var restored = SystemChunkGenerator.CODEC.codec().parse(bare, saved).getOrThrow();
        var home = restored.homeModel();
        var layout = restored.universe().orElseThrow().value();
        var universe = Universe.generate(45, layout, home, true);
        var all = universe.model(home);
        assertTrue(all.count() > 5);
        for (int p = 0; p < 5; p++)
            for (long tick : new long[] {0, 24000, 90000})
                assertEquals(home.at(p, tick), all.at(p, tick));
        assertFalse(Universe.clearOfHome(home.stars().getFirst().position(), 10000, home));
        for (var star : universe.stars())
            assertTrue(
                    Universe.clearOfHome(
                            star.position(),
                            home.radius()
                                    + (layout.maxPlanets() - 1) * layout.ring()
                                    + new PlanetShape(2048).radius()
                                    + Space.OUT
                                    + 128,
                            home));
        var loaded =
                Universe.load(
                        universe.save(
                                new net.minecraft.nbt.CompoundTag(), PlanetEcologyTest.lookup),
                        PlanetEcologyTest.lookup);
        assertEquals(all, loaded.model(home));
    }

    @Test
    void proceduralDiscoveryActuallySkipsAndSavesAnOccupiedAuthoredCell() throws Exception {
        var base = read(definition()).materialize(12).sky().model();
        var layout =
                PlanetTypeTest.lookup()
                        .lookupOrThrow(UniverseLayout.KEY)
                        .getOrThrow(
                                net.minecraft.resources.ResourceKey.create(
                                        UniverseLayout.KEY,
                                        net.minecraft.resources.ResourceLocation.parse(
                                                "starfold:default")))
                        .value();
        var raw = Universe.generate(12, layout, base);
        var candidate =
                raw.stars().stream()
                        .filter(s -> s.position().length() < 150000)
                        .findFirst()
                        .orElseThrow();
        var d = definition();
        sun(d, 1).add(
                        "position",
                        com.google.gson.JsonParser.parseString(
                                "["
                                        + candidate.position().x()
                                        + ","
                                        + candidate.position().y()
                                        + ","
                                        + candidate.position().z()
                                        + "]"));
        var home = read(d).materialize(12).sky().model();
        var guarded = Universe.generate(12, layout, home, true);
        assertTrue(guarded.stars().size() < raw.stars().size());
        assertTrue(
                guarded.stars().stream().noneMatch(s -> s.position().equals(candidate.position())));
        assertEquals(8, guarded.explored());
        // The layout's holders belong to the type fixture, not a subsequently rebuilt ecology
        // registry.
        var registry = PlanetTypeTest.lookup();
        var saved = guarded.save(new net.minecraft.nbt.CompoundTag(), registry);
        var restored = Universe.load(saved, registry);
        assertEquals(guarded.explored(), restored.explored());
        assertEquals(guarded.model(home), restored.model(home));
        var cell = Universe.cell(layout, home, candidate.position());
        assertFalse(restored.discover(12, layout, home, cell[0], cell[1], 100));
    }

    static com.google.gson.JsonObject sun(com.google.gson.JsonObject data, int s) {
        return data.getAsJsonArray("suns").get(s).getAsJsonObject();
    }

    static com.google.gson.JsonObject planet(com.google.gson.JsonObject data, int s, int p) {
        return sun(data, s).getAsJsonArray("planets").get(p).getAsJsonObject();
    }

    static String failure(com.google.gson.JsonObject data) {
        return assertThrows(RuntimeException.class, () -> read(data).validate()).getMessage();
    }

    @org.junit.jupiter.api.TestFactory
    java.util.stream.Stream<org.junit.jupiter.api.DynamicTest>
            authorErrorsNameTheFieldAndHowToFixIt() {
        record Bad(
                String message, java.util.function.Consumer<com.google.gson.JsonObject> change) {}
        return List.of(
                        new Bad("2..1600", d -> d.getAsJsonArray("suns").remove(0)),
                        new Bad(
                                "home.sun/home.planet",
                                d -> d.getAsJsonObject("home").addProperty("sun", 9)),
                        new Bad(
                                "home sun needs at least two",
                                d -> {
                                    sun(d, 0).getAsJsonArray("planets").remove(2);
                                    sun(d, 0).getAsJsonArray("planets").remove(1);
                                }),
                        new Bad(
                                "base pair",
                                d ->
                                        planet(d, 0, 1)
                                                .getAsJsonObject("orbit")
                                                .addProperty("radius", 8000)),
                        new Bad(
                                "base pair",
                                d ->
                                        planet(d, 0, 1)
                                                .getAsJsonObject("orbit")
                                                .addProperty("period", 24001)),
                        new Bad(
                                "base pair",
                                d ->
                                        planet(d, 0, 1)
                                                .getAsJsonObject("orbit")
                                                .addProperty("phase", 3)),
                        new Bad("home sun position", d -> sun(d, 0).addProperty("radius", 1025)),
                        new Bad(
                                "200000",
                                d ->
                                        sun(d, 1).add(
                                                        "position",
                                                        com.google.gson.JsonParser.parseString(
                                                                "[200000,0,0]"))),
                        new Bad(
                                "sun.position",
                                d ->
                                        sun(d, 1).add(
                                                        "position",
                                                        com.google.gson.JsonParser.parseString(
                                                                "[1000000001,0,0]"))),
                        new Bad("sun.radius", d -> sun(d, 1).addProperty("radius", 0)),
                        new Bad("sun.radius", d -> sun(d, 1).addProperty("radius", Double.NaN)),
                        new Bad(
                                "three finite",
                                d ->
                                        sun(d, 1).add(
                                                        "position",
                                                        com.google.gson.JsonParser.parseString(
                                                                "[0,1]"))),
                        new Bad(
                                "Each sun",
                                d -> sun(d, 1).add("planets", new com.google.gson.JsonArray())),
                        new Bad(
                                "orbit.radius",
                                d ->
                                        planet(d, 1, 0)
                                                .getAsJsonObject("orbit")
                                                .addProperty("radius", 0)),
                        new Bad(
                                "orbit.period",
                                d ->
                                        planet(d, 1, 0)
                                                .getAsJsonObject("orbit")
                                                .addProperty("period", -1)),
                        new Bad(
                                "orbit.phase",
                                d ->
                                        planet(d, 1, 0)
                                                .getAsJsonObject("orbit")
                                                .addProperty("phase", Double.POSITIVE_INFINITY)),
                        new Bad(
                                "spin.period",
                                d ->
                                        planet(d, 0, 0)
                                                .getAsJsonObject("spin")
                                                .addProperty("period", 23999)),
                        new Bad(
                                "spin.tilt",
                                d ->
                                        planet(d, 0, 0)
                                                .getAsJsonObject("spin")
                                                .addProperty("tilt", 181)),
                        new Bad("planet name", d -> planet(d, 0, 0).addProperty("name", "")),
                        new Bad("planet size", d -> planet(d, 0, 0).addProperty("size", 513)),
                        new Bad("outside its type", d -> planet(d, 0, 1).addProperty("size", 2048)),
                        new Bad(
                                "too small",
                                d -> {
                                    var type = PlanetTypeTest.typeJson("rocky");
                                    type.getAsJsonObject("size").addProperty("min", 256);
                                    planet(d, 0, 1).add("type", type);
                                }),
                        new Bad(
                                "handwritten:missing",
                                d -> planet(d, 0, 1).addProperty("type", "handwritten:missing")),
                        new Bad(
                                "handwritten:no_layout",
                                d -> d.addProperty("beyond", "handwritten:no_layout")),
                        new Bad(
                                "beyond outer orbit period",
                                d -> {
                                    d.addProperty("beyond", "starfold:default");
                                    for (int p = 0; p < 2; p++)
                                        planet(d, 0, p)
                                                .getAsJsonObject("orbit")
                                                .addProperty("period", 1e9);
                                }),
                        new Bad(
                                "too close to its sun",
                                d ->
                                        planet(d, 1, 0)
                                                .getAsJsonObject("orbit")
                                                .addProperty("radius", 2000)),
                        new Bad(
                                "orbits too close",
                                d ->
                                        planet(d, 1, 1)
                                                .getAsJsonObject("orbit")
                                                .addProperty("radius", 10000)),
                        new Bad(
                                "outermost orbits overlap",
                                d ->
                                        sun(d, 1).add(
                                                        "position",
                                                        com.google.gson.JsonParser.parseString(
                                                                "[1000,0,0]"))))
                .stream()
                .map(
                        b ->
                                org.junit.jupiter.api.DynamicTest.dynamicTest(
                                        b.message,
                                        () -> {
                                            var data = definition();
                                            if (b.message.equals("2..1600")) {
                                                sun(data, 0).getAsJsonArray("planets").remove(2);
                                                sun(data, 0).getAsJsonArray("planets").remove(1);
                                                data.getAsJsonArray("suns").remove(1);
                                            } else b.change.accept(data);
                                            assertTrue(
                                                    failure(data).contains(b.message),
                                                    () -> failure(data));
                                        }));
    }

    @Test
    void sameRingNeedsEqualPeriodsAndEnoughPhaseSeparation() throws Exception {
        var d = definition();
        var orbit = planet(d, 1, 1).getAsJsonObject("orbit");
        orbit.addProperty("radius", 9000);
        orbit.addProperty("period", 36000);
        orbit.addProperty("phase", .7 + Math.PI);
        read(d).validate();
        orbit.addProperty("period", 36001);
        assertTrue(failure(d).contains("orbits too close"));
        orbit.addProperty("period", 36000);
        orbit.addProperty("phase", .8);
        assertTrue(failure(d).contains("orbits too close"));
    }

    @Test
    void countAndMutuallyExclusiveInputsFailRatherThanSilentlyIgnoringFields() throws Exception {
        var d = definition();
        var planets = sun(d, 0).getAsJsonArray("planets");
        while (planets.size() < 1601) planets.add(planet(d, 0, 0).deepCopy());
        assertTrue(failure(d).contains("2..1600"));
        for (String field : List.of("planets", "a", "b", "stars", "universe", "celestial")) {
            var p = preset();
            p.addProperty(field, "invalid");
            assertTrue(
                    assertThrows(RuntimeException.class, () -> generator(definition(), p))
                            .getMessage()
                            .contains("system cannot be combined with " + field));
        }
        var p = preset();
        p.addProperty("storage_layout", PlanetStorage.ROW_LAYOUT);
        assertTrue(
                assertThrows(RuntimeException.class, () -> generator(definition(), p))
                        .getMessage()
                        .contains("six_face_square_v2"));
    }

    @Test
    void theLegacyUniversePresetStillUsesExactlyThePinnedPair() throws Exception {
        var system =
                SystemChunkGenerator.CODEC
                        .codec()
                        .parse(PlanetTypeTest.ops(), PlanetTypeTest.preset("native_universe"))
                        .getOrThrow();
        assertFalse(system.authored());
        assertEquals(PlanetSystem.model(2), system.homeModel());
        assertTrue(system.names().isEmpty());
        var source = Sources.text("SystemChunkGenerator");
        assertTrue(
                source.contains(
                        "materialize(seed);return ChunkGeneratorStructureState.createForFlat"));
    }

    @Test
    void legacyModelsKeepEveryLiteralAndFrameBit() {
        var a =
                new GroundEphemeris.Spin(
                        true,
                        new Point(
                                Math.sin(Math.toRadians(23.5)), Math.cos(Math.toRadians(23.5)), 0),
                        new org.joml.Quaterniond(),
                        0,
                        48000,
                        0);
        var b =
                new GroundEphemeris.Spin(
                        false, new Point(0, 1, 0), new org.joml.Quaterniond(), 0, 36000, 0);
        var center = new Point(0, 0, 8192);
        var axis = new Point(0, 1, 0);
        var radial = new Point(0, 0, 1);
        var sun = new GroundSolar.Sun(true, center, 512);
        var pair =
                new GroundEphemeris.Model(
                        center, axis, radial, 8192, 24000, 0, 0, a, b, sun, true, List.of(),
                        List.of());
        var further =
                new GroundEphemeris.Orbit(
                        new Point(49152, 0, 8192), axis, radial, 8192, 24000, 4.2, 0, b);
        var fourth =
                new GroundEphemeris.Orbit(
                        new Point(49152, 0, 8192),
                        axis,
                        radial,
                        20480,
                        24000 * Math.pow(2.5, 1.5),
                        6.300000000000001,
                        0,
                        b);
        var stars =
                new GroundEphemeris.Model(
                        center,
                        axis,
                        radial,
                        8192,
                        24000,
                        0,
                        0,
                        a,
                        b,
                        sun,
                        true,
                        List.of(further, fourth),
                        List.of(new GroundEphemeris.Star(new Point(49152, 0, 8192), 512)));
        for (var models :
                List.of(
                        List.of(pair, PlanetSystem.model(new int[] {2})),
                        List.of(stars, PlanetSystem.model(new int[] {2, 2})))) {
            assertEquals(models.get(0), models.get(1));
            assertEquals(
                    GroundEphemerisSave.modelTag(models.get(0)),
                    GroundEphemerisSave.modelTag(models.get(1)));
            for (int planet = 0; planet < models.get(0).count(); planet++)
                for (long tick : new long[] {0, 1, 12000, 24000, 123456789})
                    assertEquals(models.get(0).at(planet, tick), models.get(1).at(planet, tick));
        }
    }
}
