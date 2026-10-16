package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.PlanetMotion;
import dev.starfold.ground.GroundFlightPackets;
import dev.starfold.space.SpaceNavigation;
import dev.starfold.universe.*;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

import java.util.List;

class SingleSystemTest {
    @org.junit.jupiter.api.BeforeAll
    static void boot() {
        dev.starfold.worldgen.PlanetEcologyTest.boot();
    }

    @Test
    void authoredSinglePlanetHasACompleteGeneratorAndSky() throws Exception {
        var d = AuthoredSystemTest.definition();
        d.getAsJsonArray("suns").remove(1);
        var planets = AuthoredSystemTest.sun(d, 0).getAsJsonArray("planets");
        planets.remove(2);
        planets.remove(1);
        var orbit = planets.get(0).getAsJsonObject().getAsJsonObject("orbit");
        orbit.addProperty("radius", 12000);
        orbit.addProperty("period", 30000);
        var made = AuthoredSystemTest.read(d).materialize(7);
        assertEquals(1, made.planets().size());
        assertEquals(List.of(1), made.sky().groups());
        var generator = new SystemChunkGenerator(PlanetStorage.LAYOUT, made.planets());
        assertEquals(1, generator.count());
        var ops = AuthoredSystemTest.ops();
        var saved = SystemDefinition.Sky.CODEC.encodeStart(ops, made.sky()).getOrThrow();
        assertEquals(made.sky(), SystemDefinition.Sky.CODEC.parse(ops, saved).getOrThrow());
    }

    @Test
    void singleSurfaceCanCoastOutsideItsFlightShellAndCycleHome() {
        PlanetShape.system(new int[] {2048}, new int[] {191});
        try {
            var model = PlanetSystem.model(1);
            var p = model.at(0, 0).center().add(new Point(0, 10000, 0));
            var state =
                    new GroundDynamics.State(
                            0,
                            true,
                            p,
                            new Point(0, 1, 0),
                            new Quaterniond(),
                            null,
                            true,
                            false,
                            GroundGuidance.Options.OFF,
                            0);
            var terrain =
                    new GroundDynamics.Terrain() {
                        public boolean clear(PlanetShape.Pose pose) {
                            return true;
                        }

                        public double clearance(PlanetShape.Pose pose) {
                            return 10000;
                        }
                    };
            var input = PlanetMotion.Input.idle(1, new Quaterniond());
            var next = GroundDynamics.step(model, terrain, state, input, false);
            assertTrue(next.space());
            assertEquals(0, p.add(new Point(0, 1, 0)).sub(next.position()).length(), 1e-9);
            assertEquals(List.of(0), SpaceNavigation.cycle(model, p, 0, 0));
            assertEquals(0, SpaceNavigation.next(model, p, 0, -1, 0));
            assertEquals(-1, SpaceNavigation.next(model, p, 0, 0, 0));
            var packet =
                    new GroundFlightPackets.Setup(7, model, GroundSpace.DEFAULT_MASK, state, true);
            var raw = io.netty.buffer.Unpooled.buffer();
            try {
                var buf =
                        new net.minecraft.network.RegistryFriendlyByteBuf(
                                raw, net.minecraft.core.RegistryAccess.EMPTY);
                GroundFlightPackets.Setup.CODEC.encode(buf, packet);
                assertEquals(packet, GroundFlightPackets.Setup.CODEC.decode(buf));
                assertEquals(0, buf.readableBytes());
            } finally {
                raw.release();
            }
            var invalid =
                    new GroundDynamics.State(
                            0,
                            true,
                            p,
                            GroundEphemeris.ZERO,
                            new Quaterniond(),
                            null,
                            true,
                            false,
                            GroundGuidance.Options.OFF,
                            1);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new GroundFlightPackets.Setup(
                                    7, model, GroundSpace.DEFAULT_MASK, invalid, true));
        } finally {
            PlanetSystem.reset();
        }
    }

    @Test
    void discoveryUsesTheSingleHomesRootAndKeepsItsSlot() throws Exception {
        var home = PlanetSystem.model(1);
        var layout =
                UniverseLayout.CODEC
                        .parse(
                                PlanetTypeTest.ops(),
                                com.google.gson.JsonParser.parseString(
                                        java.nio.file.Files.readString(
                                                java.nio.file.Path.of(
                                                                "src/main/resources/data/starfold")
                                                        .resolve(
                                                                "starfold/universe/default.json"))))
                        .getOrThrow();
        var universe = Universe.generate(7, layout, home);
        assertEquals(home.at(0, 1234), universe.model(home).at(0, 1234));
        assertArrayEquals(new int[] {0, 0}, Universe.cell(layout, home, home.origin()));
    }
}
