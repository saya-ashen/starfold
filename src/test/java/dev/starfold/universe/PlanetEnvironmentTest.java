package dev.starfold.universe;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.ground.GroundPackets;
import dev.starfold.render.PlanetRemotePackets;
import dev.starfold.worldgen.PlanetAtmosphere;
import dev.starfold.worldgen.PlanetEnvironment;
import dev.starfold.worldgen.PlanetGeneration;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

/** Exercise the actual authoring, resolved-save and client metadata paths. No physics or rendering. */
class PlanetEnvironmentTest {
    @BeforeAll
    static void boot() {
        PlanetTypeFixtures.boot();
    }

    private static <T> void roundTrip(Codec<T> codec, T value) {
        var ops = PlanetTypeFixtures.ops();
        assertEquals(
                value, codec.parse(ops, codec.encodeStart(ops, value).getOrThrow()).getOrThrow());
    }

    private static PlanetType type(String name) {
        return PlanetType.CODEC
                .parse(PlanetTypeFixtures.ops(), PlanetTypeFixtures.typeJson(name))
                .getOrThrow();
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static PlanetType overridden() {
        var json = PlanetTypeFixtures.typeJson("rocky");
        json.add(
                "environment",
                JsonParser.parseString(
                        """
                {"atmosphere":{"density":0.12,"color":13597019,"breathable":false},"gravity":0.8,"temperature_offset":70}
                """));
        return PlanetType.CODEC.parse(PlanetTypeFixtures.ops(), json).getOrThrow();
    }

    @Test
    void everySectionHasAnIndependentRoundTrip() {
        for (var name : java.util.List.of("rocky", "temperate", "desert", "arid")) {
            var t = type(name);
            roundTrip(PlanetTypeShape.CODEC, t.shape());
            roundTrip(PlanetTypeTerrain.CODEC, t.terrain());
            roundTrip(PlanetTypeLife.CODEC, t.life());
            roundTrip(PlanetTypeEnvironment.CODEC, t.environment());
            roundTrip(PlanetType.CODEC, t);
        }
        roundTrip(PlanetTypeEnvironment.CODEC, overridden().environment());
        var json =
                PlanetType.CODEC
                        .encodeStart(PlanetTypeFixtures.ops(), overridden())
                        .getOrThrow()
                        .getAsJsonObject();
        assertEquals(java.util.Set.of("shape", "terrain", "life", "environment"), json.keySet());
    }

    @Test
    void unsaidAtmospheresDependOnEcologyNotCreaturesAndGravityDependsOnResolvedSize() {
        assertEquals(
                PlanetAtmosphere.EARTH,
                type("temperate").generation(1024).environment().atmosphere());
        assertEquals(
                PlanetAtmosphere.NONE, type("rocky").generation(1024).environment().atmosphere());
        var bare = PlanetTypeFixtures.typeJson("temperate");
        bare.remove("environment");
        bare.getAsJsonObject("life").addProperty("creatures", false);
        assertEquals(
                PlanetAtmosphere.EARTH,
                PlanetType.CODEC
                        .parse(PlanetTypeFixtures.ops(), bare)
                        .getOrThrow()
                        .generation(1024)
                        .environment()
                        .atmosphere());
        var haunted = PlanetTypeFixtures.typeJson("rocky");
        haunted.getAsJsonObject("life").addProperty("creatures", true);
        assertEquals(
                PlanetAtmosphere.NONE,
                PlanetType.CODEC
                        .parse(PlanetTypeFixtures.ops(), haunted)
                        .getOrThrow()
                        .generation(1024)
                        .environment()
                        .atmosphere());
        assertEquals(Math.sqrt(.5), type("rocky").generation(512).environment().gravity());
        assertEquals(1, type("rocky").generation(1024).environment().gravity());
        assertEquals(1.4, type("temperate").generation(2048).environment().gravity());
        // The lower clamp is currently below the legal planet size range; pin the formula itself.
        assertEquals(.35, PlanetEnvironment.defaults(false, 64).gravity());
        assertEquals(1.4, PlanetEnvironment.defaults(false, 4096).gravity());
        assertEquals(0, type("temperate").generation(1024).environment().temperatureOffset());
        assertEquals(70, overridden().generation(512).environment().temperatureOffset());
        assertEquals(.8, overridden().generation(512).environment().gravity());
        assertEquals(.8, overridden().generation(1536).environment().gravity());
        var atmosphereOnly =
                new PlanetTypeEnvironment(
                        overridden().environment().atmosphere(), Optional.empty(), 0);
        assertEquals(Math.sqrt(.5), atmosphereOnly.resolve(false, 512).gravity());
        assertEquals(
                PlanetAtmosphere.EARTH,
                new PlanetTypeEnvironment(Optional.empty(), Optional.of(.6), 0)
                        .resolve(true, 1024)
                        .atmosphere());
    }

    @Test
    void invalidEnvironmentValuesAreRefused() {
        for (double density : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(
                    IllegalArgumentException.class, () -> new PlanetAtmosphere(density, 0, false));
        for (int color : new int[] {-1, 0x1000000})
            assertThrows(
                    IllegalArgumentException.class, () -> new PlanetAtmosphere(1, color, true));
        for (double gravity : new double[] {-1, 0, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new PlanetEnvironment(PlanetAtmosphere.NONE, gravity, 0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new PlanetTypeEnvironment(Optional.empty(), Optional.of(gravity), 0));
        }
        for (String json :
                java.util.List.of(
                        "{\"gravity\":0}",
                        "{\"gravity\":-1}",
                        "{\"atmosphere\":{\"density\":-1,\"color\":0,\"breathable\":false}}",
                        "{\"atmosphere\":{\"density\":1,\"color\":16777216,\"breathable\":false}}")) {
            assertThrows(
                    RuntimeException.class,
                    () ->
                            PlanetTypeEnvironment.CODEC
                                    .parse(JsonOps.INSTANCE, JsonParser.parseString(json))
                                    .getOrThrow());
        }
    }

    @Test
    void nonFiniteTemperatureOffsetsAreRejectedAtDataBoundaries() {
        for (double offset : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
            var json = new com.google.gson.JsonObject();
            json.addProperty("temperature_offset", offset);
            assertTrue(PlanetTypeEnvironment.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
            var b = buffer();
            try {
                new PlanetEnvironment(PlanetAtmosphere.EARTH, 1, offset).write(b);
                assertThrows(io.netty.handler.codec.DecoderException.class, () -> PlanetEnvironment.read(b));
            } finally {
                b.release();
            }
        }
    }

    @Test
    void resolvedEnvironmentSurvivesSaveClimateCopyAndLocalAndRemoteProfilesWithoutTheTypePack() {
        var planet = overridden().planet(42, Optional.of(1024));
        var expected = planet.generation().environment();
        var saved =
                PlanetChunkGenerator.CODEC
                        .codec()
                        .encodeStart(PlanetTypeFixtures.ops(), planet)
                        .getOrThrow();
        // Decode against vanilla registries, with no planet type registry at all.
        var bare =
                net.minecraft.resources.RegistryOps.create(
                        JsonOps.INSTANCE, dev.starfold.worldgen.PlanetBiomeFixtures.lookup);
        var restored = PlanetChunkGenerator.CODEC.codec().parse(bare, saved).getOrThrow();
        assertEquals(expected, restored.generation().environment());
        var nbt =
                PlanetGeneration.CODEC
                        .encodeStart(NbtOps.INSTANCE, restored.generation())
                        .getOrThrow();
        assertEquals(
                expected,
                PlanetGeneration.CODEC.parse(NbtOps.INSTANCE, nbt).getOrThrow().environment());
        assertEquals(
                expected,
                restored.generation().withRecipe(restored.generation().recipe()).environment());
        var profile = PlanetPackets.Profile.of(17, restored);
        var b = buffer();
        try {
            PlanetPackets.Profile.CODEC.encode(b, profile);
            assertEquals(
                    expected, PlanetPackets.Profile.CODEC.decode(b).generation().environment());
            assertEquals(0, b.readableBytes());
            PlanetRemotePackets.Summary.CODEC.encode(
                    b, new PlanetRemotePackets.Summary(0, profile, 0, new long[0], new long[0]));
            assertEquals(
                    expected,
                    PlanetRemotePackets.Summary.CODEC
                            .decode(b)
                            .profile()
                            .generation()
                            .environment());
            assertEquals(0, b.readableBytes());
        } finally {
            b.release();
        }
        var old = saved.deepCopy().getAsJsonObject();
        old.remove("planet_format");
        assertThrows(
                RuntimeException.class,
                () -> PlanetChunkGenerator.CODEC.codec().parse(bare, old).getOrThrow());
        old.addProperty("planet_format", 1);
        assertThrows(
                RuntimeException.class,
                () -> PlanetChunkGenerator.CODEC.codec().parse(bare, old).getOrThrow());
        old.addProperty("planet_format", 2);
        old.getAsJsonObject("planet_generation").remove("environment");
        assertThrows(
                RuntimeException.class,
                () -> PlanetChunkGenerator.CODEC.codec().parse(bare, old).getOrThrow());
    }

    @Test
    void everySystemSurfaceCarriesItsAtmosphereThroughSavedCatalogSpinAndSkyPacket()
            throws Exception {
        var system =
                SystemChunkGenerator.CODEC
                        .codec()
                        .parse(PlanetTypeFixtures.ops(), PlanetTypeFixtures.preset("native_stars"))
                        .getOrThrow();
        var model = system.homeModel();
        for (int slot = 0; slot < system.count(); slot++)
            assertEquals(
                    system.planet(slot).generation().environment().atmosphere(),
                    model.body(slot).atmosphere());
        assertEquals(PlanetAtmosphere.EARTH, model.body(0).atmosphere());
        var sun =
                model.bodies().stream()
                        .filter(GroundEphemeris.Body::light)
                        .findFirst()
                        .orElseThrow();
        var surface =
                new GroundEphemeris.Body(
                                model.orbit(0), 0, false, 0, GroundEphemeris.Appearance.SURFACE)
                        .withEnvironment(overridden().generation(1024).environment());
        var custom = new GroundEphemeris.Model(java.util.List.of(sun, surface));
        var universe =
                new Universe(
                        custom.bodies(),
                        java.util.List.of(overridden().planet(42, Optional.of(1024))));
        var saved = universe.save(new CompoundTag(), PlanetTypeFixtures.lookup());
        var restored = Universe.load(saved, PlanetTypeFixtures.lookup(), universe.allocator());
        assertEquals(surface.atmosphere(), restored.catalog().get(1).atmosphere());
        var loaded = new GroundEphemeris.Model(restored.catalog());
        var copied = GroundEphemeris.spinning(loaded, false, 0);
        assertEquals(surface.atmosphere(), copied.body(0).atmosphere());
        assertEquals(70, copied.body(0).temperatureOffset());
        var b = buffer();
        try {
            GroundPackets.Sky.CODEC.encode(b, new GroundPackets.Sky(0, copied));
            var received = GroundPackets.Sky.CODEC.decode(b).model();
            assertEquals(surface.atmosphere(), received.body(0).atmosphere());
            assertEquals(70, received.body(0).temperatureOffset());
            assertEquals(0, b.readableBytes());
        } finally {
            b.release();
        }
        var raw = dev.starfold.sky.GroundEphemerisSave.modelTag(copied);
        raw.getList("bodies", 10).getCompound(1).remove("atmosphere");
        assertThrows(RuntimeException.class, () -> dev.starfold.sky.GroundEphemerisSave.model(raw));
    }
}
