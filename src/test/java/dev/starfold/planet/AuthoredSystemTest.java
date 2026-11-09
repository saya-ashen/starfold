package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.GroundPackets;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.GroundEphemerisSave;
import dev.starfold.space.SpaceView;
import dev.starfold.universe.PlanetType;
import dev.starfold.universe.PlanetTypeFixtures;
import dev.starfold.universe.SystemChunkGenerator;
import dev.starfold.universe.SystemDefinition;
import dev.starfold.universe.Universe;
import dev.starfold.universe.UniverseLayout;
import dev.starfold.worldgen.PlanetBiomeFixtures;

import org.junit.jupiter.api.Test;

import java.util.List;

class AuthoredSystemTest {
    @org.junit.jupiter.api.BeforeAll
    static void boot() {
        PlanetBiomeFixtures.boot();
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
        var base = PlanetTypeFixtures.lookup();
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
                                PlanetTypeFixtures.ops(),
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

    @Test
    void datapackOnlySystemAllocatesAfterHomeWithoutAUniverse() throws Exception {
        var definition = definition();
        definition.remove("universe");
        var system = generator(definition, preset());
        system.materialize(42);
        assertTrue(system.universe().isEmpty());
        var ids = new dev.starfold.sky.BodyIdAllocator();
        var home = system.homeModel();
        ids.reserve(home.bodies());
        int next = home.bodies().stream().mapToInt(b -> b.id().index()).max().orElseThrow() + 1;
        assertEquals(new dev.starfold.chart.BodyId(0, next), ids.allocate(0));
        var loaded =
                dev.starfold.sky.BodyIdAllocator.load(
                        ids.save(new net.minecraft.nbt.CompoundTag(), null), null);
        assertEquals(new dev.starfold.chart.BodyId(0, next + 1), loaded.allocate(0));
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
        assertEquals(10, block.matcher(english).results().count());
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
                system.planet(1).generation().recipe().minerals().strata().getFirst().block());
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
                        .contains("handwritten:ash")); // identity survives; registry is still
        // unnecessary
        var bare =
                net.minecraft.resources.RegistryOps.create(
                        com.mojang.serialization.JsonOps.INSTANCE, PlanetBiomeFixtures.lookup);
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
                        net.minecraft.nbt.NbtOps.INSTANCE, PlanetBiomeFixtures.lookup);
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
            orbit.addProperty("phase", p * Math.PI);
        }
        var made = read(d).materialize(42);
        assertEquals("Dune", made.sky().names().getFirst());
        assertEquals(new Point(60000, 4000, -20000), made.sky().model().origin());
        assertEquals(420, made.sky().model().primary().radius());
        assertEquals(List.of(2, 3), made.sky().groups());
        assertEquals(SystemDefinition.derivedSeed(42, 4), made.planets().getFirst().seed());
        assertEquals("Aster", made.sky().names().get(2));
    }

    @Test
    void reachRoundTripsThroughDefinitionGeneratorUniverseAndBothModelPackets() throws Exception {
        var json = definition();
        planet(json, 0, 0).addProperty("reach", 512);
        var definition = read(json);
        assertEquals(512, definition.suns().getFirst().planets().getFirst().reach());
        assertEquals(2048, definition.suns().getFirst().planets().get(1).reach());
        var encoded = SystemDefinition.CODEC.encodeStart(ops(), definition).getOrThrow();
        var roundTrip = SystemDefinition.CODEC.parse(ops(), encoded).getOrThrow();
        assertEquals(512, roundTrip.suns().getFirst().planets().getFirst().reach());
        assertEquals(encoded, SystemDefinition.CODEC.encodeStart(ops(), roundTrip).getOrThrow());
        assertEquals(definition.materialize(42).sky(), roundTrip.materialize(42).sky());
        var made = definition.materialize(42);
        var model = made.sky().model();
        assertEquals(512, model.body(0).reach());
        var system = generator(json, preset());
        system.materialize(42);
        var bare =
                net.minecraft.resources.RegistryOps.create(
                        com.mojang.serialization.JsonOps.INSTANCE, PlanetTypeFixtures.lookup());
        var saved = SystemChunkGenerator.CODEC.codec().encodeStart(bare, system).getOrThrow();
        assertEquals(
                model,
                SystemChunkGenerator.CODEC.codec().parse(bare, saved).getOrThrow().homeModel());
        var universe = new Universe(model.bodies(), made.planets());
        var tag = universe.save(new net.minecraft.nbt.CompoundTag(), PlanetTypeFixtures.lookup());
        assertEquals(8, tag.getInt("format"));
        var loaded = Universe.load(tag, PlanetTypeFixtures.lookup(), universe.allocator());
        assertEquals(model.bodies(), loaded.catalog());
        assertEquals(512, new GroundEphemeris.Model(loaded.catalog()).body(0).reach());
        assertThrows(
                IllegalArgumentException.class,
                () -> loaded.model(model),
                "Combining the same identities twice must fail");
        var legacy = tag.copy();
        legacy.putInt("format", 7);
        assertThrows(
                IllegalArgumentException.class,
                () -> Universe.load(legacy, PlanetTypeFixtures.lookup(), universe.allocator()));
        for (int old : new int[] {1, 2, 3, 4, 5, 6, 9}) {
            var obsolete = tag.copy();
            obsolete.putInt("format", old);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Universe.load(obsolete, PlanetBiomeFixtures.lookup, universe.allocator()));
        }
        var missing = tag.copy();
        missing.getList("bodies", 10).getCompound(model.index(0)).remove("reach");
        assertThrows(
                IllegalArgumentException.class,
                () -> Universe.load(missing, PlanetBiomeFixtures.lookup, universe.allocator()));
        var raw = io.netty.buffer.Unpooled.buffer();
        try {
            var buffer =
                    new net.minecraft.network.RegistryFriendlyByteBuf(
                            raw, net.minecraft.core.RegistryAccess.EMPTY);
            var sky = new GroundPackets.Sky(123, model);
            GroundPackets.Sky.CODEC.encode(buffer, sky);
            assertEquals(sky, GroundPackets.Sky.CODEC.decode(buffer));
            var state =
                    GroundDynamics.local(
                            123,
                            new PlanetShape.Pose(
                                    dev.starfold.geometry.CubeSurfaceTopology.Face.TOP,
                                    new Point(1024, 600, 1024)),
                            GroundEphemeris.ZERO,
                            new org.joml.Quaterniond(),
                            true,
                            GroundDynamics.OFF);
            var setup =
                    new dev.starfold.ground.GroundFlightPackets.Setup(
                            42, model, dev.starfold.chart.GroundSpace.DEFAULT_MASK, state, true);
            dev.starfold.ground.GroundFlightPackets.Setup.CODEC.encode(buffer, setup);
            assertEquals(setup, dev.starfold.ground.GroundFlightPackets.Setup.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally {
            raw.release();
        }
        for (double invalid : new double[] {0, 511, 2049}) {
            var bad = json.deepCopy();
            planet(bad, 0, 0).addProperty("reach", invalid);
            assertThrows(RuntimeException.class, () -> read(bad));
        }
    }

    @Test
    void defaultHomeClearanceRetainsTheOldArithmeticAtItsBoundary() throws Exception {
        var model = read(definition()).materialize(42).sky().model();
        double surface = new PlanetShape(PlanetShape.MAX).radius() + 2048 + 128;
        double outer = 0;
        for (int p = 0; p < 3; p++) outer = Math.max(outer, model.orbit(p).radius());
        double boundary = 10000 + outer + surface;
        for (double distance :
                new double[] {Math.nextDown(boundary), boundary, Math.nextUp(boundary)}) {
            var position = model.origin().add(new Point(-distance, 0, 0));
            assertEquals(distance >= boundary, Universe.clearOfHome(position, 10000, model));
        }
    }

    @Test
    void smallerReachAllowsCloserRingsAndSunsAndShrinksTheHomeEnvelope() throws Exception {
        var json = definition();
        var defaults = read(json);
        double radius =
                new PlanetShape(
                                defaults.suns()
                                        .getFirst()
                                        .planets()
                                        .get(2)
                                        .type()
                                        .value()
                                        .maxSize())
                        .radius();
        double biggest = new PlanetShape(2048).radius();
        double required = biggest + radius + 512 + 512 + SystemDefinition.MARGIN;
        planet(json, 0, 2).getAsJsonObject("orbit").addProperty("radius", 8192 + required + .01);
        assertThrows(IllegalArgumentException.class, () -> read(json).validate());
        for (int p = 0; p < 3; p++) planet(json, 0, p).addProperty("reach", 512);
        var small = read(json);
        small.validate();
        var tooClose = json.deepCopy();
        planet(tooClose, 0, 2)
                .getAsJsonObject("orbit")
                .addProperty("radius", 8192 + required - .01);
        assertThrows(IllegalArgumentException.class, () -> read(tooClose).validate());
        var nearSun = definition();
        planet(nearSun, 1, 0).getAsJsonObject("orbit").addProperty("radius", 1500);
        assertThrows(IllegalArgumentException.class, () -> read(nearSun).validate());
        planet(nearSun, 1, 0).addProperty("reach", 512);
        read(nearSun).validate();
        var home = defaults.materialize(42).sky().model();
        var smaller = definition();
        for (int p = 0; p < 3; p++) planet(smaller, 0, p).addProperty("reach", 512);
        var smallHome = read(smaller).materialize(42).sky().model();
        double outer = 0;
        for (int p = 0; p < 3; p++)
            outer = Math.max(outer, home.orbit(p).radius() + home.body(p).hullOut());
        var position = home.origin().add(new Point(outer + biggest + 128 + 10000 - 768, 0, 0));
        assertFalse(Universe.clearOfHome(position, 10000, home));
        assertTrue(Universe.clearOfHome(position, 10000, smallHome));
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
                PlanetBiomeFixtures.lookup
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
        var lookup = PlanetBiomeFixtures.lookup;
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
                double period = path.period(sun.gm());
                for (double tick : new double[] {0, period / 4, period}) {
                    double angle = path.phase() + 2 * Math.PI * tick / period;
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
                            2 * Math.PI * path.radius() / period,
                            model.at(id, tick).velocity().length(),
                            1e-9);
                }
                id++;
            }
        assertEquals(
                new Point(60000, 4000, -20000),
                model.everyStar().subList(1, model.everyStar().size()).getFirst().position());
        assertEquals(
                420, model.everyStar().subList(1, model.everyStar().size()).getFirst().radius());
        assertEquals(512, model.primary().radius());
        assertTrue(model.spin(0).enabled());
        assertEquals(48000, model.spin(0).period());
        assertEquals(Math.sin(Math.toRadians(23.5)), model.spin(0).axis().x());
        assertFalse(model.spin(1).enabled());
    }

    @Test
    void registryAndPresetRoundTripsCanHappenBeforeTheWorldSeedExists() throws Exception {
        var system = generator(definition(), preset());
        var encoded = SystemChunkGenerator.CODEC.codec().encodeStart(ops(), system).getOrThrow();
        assertTrue(encoded.getAsJsonObject().get("system").isJsonObject());
        var bare =
                net.minecraft.resources.RegistryOps.create(
                        com.mojang.serialization.JsonOps.INSTANCE, PlanetBiomeFixtures.lookup);
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
            PlanetSystem.reset();
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
                        com.mojang.serialization.JsonOps.INSTANCE, PlanetBiomeFixtures.lookup);
        var restored = SystemChunkGenerator.CODEC.codec().parse(bare, saved).getOrThrow();
        var home = restored.homeModel();
        var layout = restored.universe().orElseThrow().value();
        var universe = Universe.generate(45, layout, home, true);
        var all = universe.model(home);
        for (var body : universe.catalog()) assertEquals(body.slot() < 0 ? 0 : 2048, body.reach());
        assertTrue(all.count() > 5);
        for (int p = 0; p < 5; p++)
            for (long tick : new long[] {0, 24000, 90000})
                assertEquals(home.at(p, tick), all.at(p, tick));
        assertFalse(
                Universe.clearOfHome(
                        home.everyStar().subList(1, home.everyStar().size()).getFirst().position(),
                        10000,
                        home));
        for (var star : universe.stars())
            assertTrue(
                    Universe.clearOfHome(
                            star.position(),
                            home.orbit(0).radius()
                                    + (layout.maxPlanets() - 1) * layout.ring()
                                    + new PlanetShape(2048).radius()
                                    + GroundEphemeris.Body.DEFAULT_REACH
                                    + 128,
                            home));
        var loaded =
                Universe.load(
                        universe.save(
                                new net.minecraft.nbt.CompoundTag(), PlanetBiomeFixtures.lookup),
                        PlanetBiomeFixtures.lookup,
                        universe.allocator());
        assertEquals(all, loaded.model(home));
    }

    @Test
    void proceduralDiscoveryActuallySkipsAndSavesAnOccupiedAuthoredCell() throws Exception {
        var base = read(definition()).materialize(12).sky().model();
        var layout =
                PlanetTypeFixtures.lookup()
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
        sun(d, 1)
                .add(
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
        var registry = PlanetTypeFixtures.lookup();
        var saved = guarded.save(new net.minecraft.nbt.CompoundTag(), registry);
        var restored = Universe.load(saved, registry, guarded.allocator());
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
                        new Bad("1..1600", d -> {}),
                        new Bad(
                                "home.sun/home.planet",
                                d -> d.getAsJsonObject("home").addProperty("sun", 9)),
                        new Bad(
                                "orbits too close",
                                d ->
                                        planet(d, 0, 1)
                                                .getAsJsonObject("orbit")
                                                .addProperty("radius", 4000)),
                        new Bad("sun.radius", d -> sun(d, 0).addProperty("radius", 16385)),
                        new Bad(
                                "orbit envelope",
                                d ->
                                        sun(d, 1)
                                                .add(
                                                        "position",
                                                        com.google.gson.JsonParser.parseString(
                                                                "[1000000000,0,0]"))),
                        new Bad(
                                "sun.position",
                                d ->
                                        sun(d, 1)
                                                .add(
                                                        "position",
                                                        com.google.gson.JsonParser.parseString(
                                                                "[1000000001,0,0]"))),
                        new Bad("sun.radius", d -> sun(d, 1).addProperty("radius", 0)),
                        new Bad("sun.radius", d -> sun(d, 1).addProperty("radius", Double.NaN)),
                        new Bad(
                                "three finite",
                                d ->
                                        sun(d, 1)
                                                .add(
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
                                "orbit.radius",
                                d ->
                                        planet(d, 1, 0)
                                                .getAsJsonObject("orbit")
                                                .addProperty("radius", Double.NaN)),
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
                                    var type = PlanetTypeFixtures.typeJson("rocky");
                                    type.getAsJsonObject("shape")
                                            .getAsJsonObject("size")
                                            .addProperty("min", 256);
                                    planet(d, 0, 1).add("type", type);
                                }),
                        new Bad(
                                "handwritten:missing",
                                d -> planet(d, 0, 1).addProperty("type", "handwritten:missing")),
                        new Bad(
                                "handwritten:no_layout",
                                d -> d.addProperty("beyond", "handwritten:no_layout")),
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
                                        sun(d, 1)
                                                .add(
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
                                            if (b.message.equals("1..1600"))
                                                data.getAsJsonArray("suns").asList().clear();
                                            else b.change.accept(data);
                                            assertTrue(
                                                    failure(data).contains(b.message),
                                                    () -> failure(data));
                                        }));
    }

    @Test
    void authoredStarSupportsTheCalibrated12000BlockRadius() throws Exception {
        var data = definition();
        data.getAsJsonArray("suns").remove(1);
        sun(data, 0).addProperty("radius", 12000);
        planet(data, 0, 0).getAsJsonObject("orbit").addProperty("radius", 30000);
        planet(data, 0, 1).getAsJsonObject("orbit").addProperty("radius", 50000);
        planet(data, 0, 2).getAsJsonObject("orbit").addProperty("radius", 80000);
        var made = read(data).materialize(42);
        assertEquals(12000, made.sky().model().primary().radius());
    }

    @Test
    void nativeFlightPositionBoundaryMatchesTheExpandedAuthoredCoordinateRange() {
        assertDoesNotThrow(
                () ->
                        new GroundDynamics.State(
                                0,
                                true,
                                new Point(1e9, 0, 0),
                                GroundEphemeris.ZERO,
                                new org.joml.Quaterniond(),
                                null,
                                false,
                                false,
                                GroundDynamics.OFF,
                                0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new GroundDynamics.State(
                                0,
                                true,
                                new Point(1e9 + 1, 0, 0),
                                GroundEphemeris.ZERO,
                                new org.joml.Quaterniond(),
                                null,
                                false,
                                false,
                                GroundDynamics.OFF,
                                0));
    }

    @Test
    void orbitPeriodIsDerivedAndNotWrittenToTheAuthoredSchema() throws Exception {
        var definition = read(definition());
        assertEquals(
                384000,
                new SystemDefinition.Path(SystemDefinition.HOME_ORBIT, 0)
                        .period(SystemDefinition.STAR_GM),
                1e-8);
        var encoded = SystemDefinition.CODEC.encodeStart(ops(), definition).getOrThrow();
        for (var sun : encoded.getAsJsonObject().getAsJsonArray("suns"))
            for (var planet : sun.getAsJsonObject().getAsJsonArray("planets"))
                assertFalse(planet.getAsJsonObject().getAsJsonObject("orbit").has("period"));
    }

    @Test
    void progradeSpinKeepsTheMeanSolarDayAt48000Ticks() {
        double year = new SystemDefinition.Path(SystemDefinition.HOME_ORBIT, 0)
                .period(SystemDefinition.STAR_GM);
        double spin = 1 / (1.0 / 48000 + 1 / year);
        assertEquals(48000, 1 / (1 / spin - 1 / year), 1e-8);
        var rotation = new GroundEphemeris.Spin(true, GroundEphemeris.Y,
                new org.joml.Quaterniond(), 0, spin, 0);
        var model = new GroundEphemeris.Model(List.of(
                new GroundEphemeris.Body(new GroundEphemeris.Fixed(GroundEphemeris.ZERO),
                        12000, true, -1, GroundEphemeris.Appearance.LIGHT),
                new GroundEphemeris.Body(new GroundEphemeris.Orbit(0, GroundEphemeris.Y,
                        new Point(0, 0, 1), SystemDefinition.HOME_ORBIT, year, 0, 0, rotation),
                        0, false, 0, GroundEphemeris.Appearance.SURFACE)));
        var before = model.at(0, 0).local(GroundEphemeris.ZERO);
        var after = model.at(0, 48000).local(GroundEphemeris.ZERO);
        assertEquals(0, before.sub(after).length(), 1e-8);
    }

    @Test
    void homeUsesOrdinarySpacingWithoutPairLimits() throws Exception {
        var d = definition();
        planet(d, 0, 1).getAsJsonObject("orbit").addProperty("radius", 8192);
        planet(d, 0, 1).getAsJsonObject("orbit").addProperty("phase", 3);
        read(d).validate();
        d.getAsJsonObject("home").addProperty("planet", 2);
        var made = read(d).materialize(42);
        assertEquals(List.of("Rime", "Aster", "Cinder", "D", "Dune"), made.sky().names());
        assertEquals(SystemDefinition.derivedSeed(42, 2), made.planets().get(0).seed());
        assertEquals(SystemDefinition.derivedSeed(42, 1), made.planets().get(2).seed());
    }

    @Test
    void sameRingNeedsEqualPeriodsAndEnoughPhaseSeparation() throws Exception {
        var d = definition();
        var orbit = planet(d, 1, 1).getAsJsonObject("orbit");
        orbit.addProperty("radius", 9000);
        orbit.addProperty("phase", .7 + Math.PI);
        read(d).validate();
        orbit.addProperty("phase", .8);
        assertTrue(failure(d).contains("orbits too close"));
    }

    @Test
    void countAndMutuallyExclusiveInputsFailRatherThanSilentlyIgnoringFields() throws Exception {
        var d = definition();
        var planets = sun(d, 0).getAsJsonArray("planets");
        while (planets.size() < 1601) planets.add(planet(d, 0, 0).deepCopy());
        assertTrue(failure(d).contains("1..1600"));
        for (String field : List.of("planets", "a", "b", "stars", "universe", "celestial")) {
            var p = preset();
            p.addProperty(field, "invalid");
            assertTrue(
                    assertThrows(RuntimeException.class, () -> generator(definition(), p))
                            .getMessage()
                            .contains("system cannot be combined with " + field));
        }
        var p = preset();
        p.addProperty("storage_layout", "six_face_x32768_v1");
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
                        .parse(PlanetTypeFixtures.ops(), PlanetTypeFixtures.preset("native_universe"))
                        .getOrThrow();
        assertFalse(system.authored());
        assertEquals(
                new GroundEphemeris.Model(
                        PlanetSystem.model(2).bodies().stream()
                                .map(
                                        body ->
                                                body.slot() < 0
                                                        ? body
                                                        : body.withAtmosphere(
                                                                system.planet(body.slot())
                                                                        .generation()
                                                                        .environment()
                                                                        .atmosphere()))
                                .toList()),
                system.homeModel());
        assertTrue(system.names().isEmpty());
        var source = Sources.text("SystemChunkGenerator");
        assertTrue(
                source.contains(
                        "materialize(seed);return ChunkGeneratorStructureState.createForFlat"));
    }

    private static SystemDefinition beyondWithSmallAuthoredSun(double orbit) throws Exception {
        var json =
                com.google.gson.JsonParser.parseString(
                                "{\"suns\":[{\"position\":[0,0,0],\"radius\":128,\"planets\":[{\"type\":\"starfold:temperate\",\"size\":2048,\"orbit\":{\"radius\":"
                                        + orbit
                                        + ",\"period\":24000}}]}],\"beyond\":{\"stars\":{\"min\":1,\"max\":1},\"planets\":{\"min\":1,\"max\":1},\"star_gap\":100000,\"ring\":8192,\"types\":[{\"type\":\"starfold:temperate\"}]}}")
                        .getAsJsonObject();
        var fixedSizeType = PlanetTypeFixtures.typeJson("temperate").deepCopy();
        var size = fixedSizeType.getAsJsonObject("shape").getAsJsonObject("size");
        size.addProperty("min", 2048);
        size.addProperty("max", 2048);
        json.getAsJsonObject("beyond")
                .getAsJsonArray("types")
                .get(0)
                .getAsJsonObject()
                .add("type", fixedSizeType);
        return SystemDefinition.CODEC.parse(PlanetTypeFixtures.ops(), json).getOrThrow();
    }

    @Test
    void beyondInnerRingStrictlyClearsTheActualMaximumGeneratedSun() throws Exception {
        assertEquals(960, dev.starfold.chart.SunSettings.generatedRadius(256));
        assertEquals(960, dev.starfold.chart.SunSettings.MAX_GENERATED_RADIUS);
        // Independent literals from the documented safety contract, not the production constant.
        double first = 960 + 2048 * 2 / Math.PI + 2048 + 128;
        for (double orbit : new double[] {Math.nextDown(first), first}) {
            var definition = beyondWithSmallAuthoredSun(orbit);
            assertThrows(IllegalArgumentException.class, definition::validate);
            assertThrows(IllegalArgumentException.class, () -> definition.materialize(42));
        }
        var valid = beyondWithSmallAuthoredSun(Math.nextUp(first));
        assertDoesNotThrow(valid::validate);
        var made = valid.materialize(42);
        assertEquals(128, made.sky().model().primary().radius());
        assertEquals(2048, made.planets().getFirst().generation().size());
        assertEquals(Math.nextUp(first), made.sky().model().orbit(0).radius());
        long maximumSeed = -1;
        for (long seed = 0; seed < 10000; seed++) {
            var random = new java.util.SplittableRandom(seed ^ 0x43756265506C616EL);
            random.nextInt(1); // The finite generator's star-count draw precedes the radius draw.
            if (random.nextInt(257) == 256) {
                maximumSeed = seed;
                break;
            }
        }
        assertTrue(maximumSeed >= 0);
        var generated =
                Universe.generate(
                        maximumSeed, valid.beyond().orElseThrow().value(), made.sky().model());
        assertEquals(960, generated.stars().getFirst().radius());
        assertEquals(2048, generated.planets().getFirst().generation().size());
    }

    @Test
    void beyondRejectsThe4200OrbitCounterexampleWithoutScalingItsAuthoredSun() throws Exception {
        var definition = beyondWithSmallAuthoredSun(4200);
        assertEquals(128, definition.suns().getFirst().radius());
        assertEquals(2048, definition.suns().getFirst().planets().getFirst().size().orElseThrow());
        assertEquals(4200, definition.suns().getFirst().planets().getFirst().orbit().radius());
        assertThrows(IllegalArgumentException.class, definition::validate);
        assertThrows(IllegalArgumentException.class, () -> definition.materialize(42));
        var withoutBeyond =
                new SystemDefinition(
                        definition.suns(), definition.home(), java.util.Optional.empty());
        assertEquals(128, withoutBeyond.materialize(42).sky().model().primary().radius());
        assertDoesNotThrow(beyondWithSmallAuthoredSun(17800)::validate);
    }

    @Test
    void proceduralBuildersKeepEveryLiteralAndFrameBit() {
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
        var center = new Point(0, 0, 17800);
        var axis = new Point(0, 1, 0);
        var radial = new Point(0, 0, 1);
        var pairBodies =
                List.of(
                        new GroundEphemeris.Body(
                                new GroundEphemeris.Fixed(center),
                                768,
                                true,
                                -1,
                                GroundEphemeris.Appearance.LIGHT),
                        new GroundEphemeris.Body(
                                new GroundEphemeris.Orbit(
                                        0, axis, radial, 17800, 24000, Math.PI, 0, a),
                                0,
                                false,
                                0,
                                GroundEphemeris.Appearance.SURFACE),
                        new GroundEphemeris.Body(
                                new GroundEphemeris.Orbit(
                                        0,
                                        axis,
                                        radial,
                                        17800,
                                        24000,
                                        Math.PI + 2 * Math.PI / 3,
                                        0,
                                        b),
                                0,
                                false,
                                1,
                                GroundEphemeris.Appearance.SURFACE));
        var pair = new GroundEphemeris.Model(pairBodies);
        var further = new GroundEphemeris.Orbit(3, axis, radial, 17800, 24000, 4.2, 0, b);
        var fourth =
                new GroundEphemeris.Orbit(
                        3,
                        axis,
                        radial,
                        30088,
                        24000 * Math.pow(30088.0 / 17800, 1.5),
                        6.300000000000001,
                        0,
                        b);
        var bodies = new java.util.ArrayList<>(pairBodies);
        bodies.add(
                new GroundEphemeris.Body(
                        new GroundEphemeris.Fixed(new Point(137900, 0, 17800)),
                        768,
                        true,
                        -1,
                        GroundEphemeris.Appearance.LIGHT));
        bodies.add(
                new GroundEphemeris.Body(further, 0, false, 2, GroundEphemeris.Appearance.SURFACE));
        bodies.add(
                new GroundEphemeris.Body(fourth, 0, false, 3, GroundEphemeris.Appearance.SURFACE));
        var stars = new GroundEphemeris.Model(bodies);
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
