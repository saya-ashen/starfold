package dev.starfold.flight;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundGuidance;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.geometry.PlanetMotion;
import dev.starfold.geometry.PlanetSpace;
import dev.starfold.ground.GroundNavigationSave;
import dev.starfold.ground.GroundTopology;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.planet.PlanetSystemTest;
import dev.starfold.sky.GroundClock;
import dev.starfold.sky.GroundEphemeris;
import dev.starfold.sky.GroundEphemerisSave;
import dev.starfold.waypoint.Waypoint;
import dev.starfold.waypoint.WaypointSave;

import net.minecraft.nbt.*;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

/** Disk/state integration only: terrain is a fixture, not a running ServerLevel or player inventory. */
class VoyageRegressionTest {
    @TempDir Path directory;
    private final UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID otherPlayer = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final PlanetSystemTest flight = new PlanetSystemTest();
    private WaypointSave markers = new WaypointSave();
    private int diskCheckpoints, spaceCheckpoints, localCheckpoints;

    private void sameState(GroundDynamics.State expected, GroundDynamics.State actual) {
        // Vanilla NBT canonicalizes -0.0 to +0.0; compare physical values, not record bits.
        assertEquals(expected.tick(), actual.tick());
        assertEquals(expected.space(), actual.space());
        assertEquals(expected.planet(), actual.planet());
        assertEquals(expected.local(), actual.local());
        assertEquals(expected.flying(), actual.flying());
        assertEquals(expected.braking(), actual.braking());
        assertEquals(expected.guidance(), actual.guidance());
        assertEquals(0, expected.position().sub(actual.position()).length(), 1e-12);
        assertEquals(0, expected.velocity().sub(actual.velocity()).length(), 1e-12);
        assertEquals(1, Math.abs(expected.orientation().dot(actual.orientation())), 1e-12);
    }

    private CompoundTag disk(String name, CompoundTag tag) throws Exception {
        Path file = directory.resolve(name + ".dat");
        NbtIo.writeCompressed(tag, file);
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
    }

    private GroundDynamics.State checkpoint(
            GroundEphemeris.Model model, GroundDynamics.Terrain terrain, GroundDynamics.State state)
            throws Exception {
        var clock = new GroundClock(new GroundEphemerisSave(model, state.tick()));
        var restoredClock =
                GroundClock.load(disk("clock", clock.save(new CompoundTag(), null)), null);
        assertEquals(model, restoredClock.clock.model);
        assertEquals(state.tick(), restoredClock.clock.tick());

        var navigation = new GroundNavigationSave();
        navigation.put(player, state);
        var other =
                GroundDynamics.local(
                        state.tick(),
                        1,
                        new GroundTopology.Pose(Face.BOTTOM, new Point(777, 180, 999)),
                        GroundEphemeris.ZERO,
                        new Quaterniond(),
                        true,
                        GroundDynamics.OFF);
        navigation.put(otherPlayer, other);
        var restored =
                GroundNavigationSave.load(
                        disk("navigation", navigation.save(new CompoundTag(), null)), null);
        sameState(other, restored.find(otherPlayer));
        sameState(state, restored.find(player));
        assertNull(restored.find(new UUID(0, 3)));

        var expectedMarkers = markers.list(player);
        var expectedTarget = markers.active(player);
        markers = WaypointSave.load(disk("waypoints", markers.save(new CompoundTag(), null)), null);
        assertEquals(expectedMarkers, markers.list(player));
        assertEquals(expectedTarget, markers.active(player));
        assertTrue(markers.list(otherPlayer).isEmpty());
        if (expectedTarget != null) {
            assertEquals(state.guidance().target(), expectedTarget.planet());
            assertEquals(
                    expectedTarget.world(model, state.tick()),
                    markers.active(player)
                            .world(restoredClock.clock.model, restoredClock.clock.tick()));
        }
        var resumed =
                GroundDynamics.resume(
                        restoredClock.clock.model,
                        terrain,
                        restored.find(player),
                        restoredClock.clock.tick());
        sameState(state, resumed);
        diskCheckpoints++;
        if (state.space()) spaceCheckpoints++;
        else localCheckpoints++;
        return resumed;
    }

    private GroundDynamics.State fly(
            GroundEphemeris.Model model, GroundDynamics.State state, int target) throws Exception {
        for (int i = 0; i < 2500; i++) {
            if (!state.space() && state.planet() == target && state.local().point().y() < 230)
                return checkpoint(model, PlanetSystemTest.OPEN, state);
            var direction =
                    PlanetSpace.unit(
                            model.at(target, state.tick())
                                    .center()
                                    .sub(GroundDynamics.position(model, state, state.tick())));
            var q = flight.aim(direction);
            var input = new PlanetMotion.Input(i + 1, q.x, q.y, q.z, q.w, 1, 0, 0, true, true);
            var uninterrupted =
                    GroundDynamics.step(model, PlanetSystemTest.OPEN, state, input, false);
            if (i % 71 == 0) {
                state = checkpoint(model, PlanetSystemTest.OPEN, state);
                sameState(
                        uninterrupted,
                        GroundDynamics.step(model, PlanetSystemTest.OPEN, state, input, false));
            }
            state = uninterrupted;
        }
        throw new AssertionError("Did not reach planet " + target);
    }

    @Test
    void spinningBinaryRoundTripKeepsClockMomentumAndBaseTargetAcrossDiskReloads()
            throws Exception {
        var model = GroundEphemeris.spinning(PlanetSystem.model(), true, 0);
        var aBase =
                new Waypoint(
                        "a_base", 0, new GroundTopology.Pose(Face.TOP, new Point(1024, 20, 1024)));
        var bBase =
                new Waypoint(
                        "b_base", 1, new GroundTopology.Pose(Face.TOP, new Point(1024, -5, 1024)));
        markers.add(player, aBase);
        markers.add(player, bBase);
        markers.select(player, "b_base");
        var toward = PlanetSpace.unit(model.at(1, 0).center().sub(model.at(0, 0).center()));
        var pose =
                GroundTopology.locate(
                        model.at(0, 0).inverseVector(toward.scale(GroundOrbit.RADIUS + 4100)));
        var state =
                GroundDynamics.detach(
                        model,
                        GroundDynamics.local(
                                0,
                                0,
                                pose,
                                GroundEphemeris.ZERO,
                                new Quaterniond(),
                                true,
                                new GroundGuidance.Options(1, false, false)));
        var b = fly(model, state, 1);
        assertEquals(1, b.planet());
        assertFalse(b.space());
        markers.select(player, "a_base");
        b = GroundDynamics.guidance(b, new GroundGuidance.Options(0, false, false));
        var a = fly(model, b, 0);
        assertEquals(0, a.planet());
        assertFalse(a.space());
        assertEquals(aBase, markers.active(player));
        assertTrue(spaceCheckpoints > 0);
        assertTrue(localCheckpoints >= 2);
        assertTrue(diskCheckpoints > 4);
    }

    @Test
    void lowGroundRestoreQueriesSavedPlanetAndKeepsNegativeHeightAndGuidance() throws Exception {
        var model = PlanetSystem.model();
        for (int planet = 0; planet < 2; planet++) {
            final int expectedPlanet = planet;
            var terrain =
                    new GroundDynamics.Terrain() {
                        public boolean clear(GroundTopology.Pose p) {
                            fail("Lost planet identity");
                            return false;
                        }

                        public double clearance(GroundTopology.Pose p) {
                            throw new AssertionError("Unexpected query");
                        }

                        public boolean clear(int id, GroundTopology.Pose p) {
                            assertEquals(expectedPlanet, id);
                            return true;
                        }
                    };
            for (double height : new double[] {-22, -5, 1, 49}) {
                var state =
                        GroundDynamics.local(
                                900,
                                planet,
                                new GroundTopology.Pose(Face.WEST, new Point(800, height, 1200)),
                                new Point(.1, 0, .2),
                                new Quaterniond().rotationY(.3),
                                false,
                                new GroundGuidance.Options(1 - planet, true, true));
                var restored = checkpoint(model, terrain, state);
                assertEquals(height, restored.local().point().y());
                assertFalse(restored.flying());
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                GroundDynamics.step(
                                        model,
                                        terrain,
                                        restored,
                                        PlanetMotion.Input.idle(0, restored.orientation()),
                                        false));
            }
        }
    }

    @Test
    void occupiedOrFutureRestoreRefusesWithoutMutatingSavedEntry() throws Exception {
        var model = PlanetSystem.model();
        var state =
                GroundDynamics.local(
                        900,
                        1,
                        new GroundTopology.Pose(Face.TOP, new Point(1024, -5, 1024)),
                        GroundEphemeris.ZERO,
                        new Quaterniond(),
                        false,
                        GroundDynamics.OFF);
        var save = new GroundNavigationSave();
        save.put(player, state);
        var before = disk("blocked", save.save(new CompoundTag(), null));
        var loaded = GroundNavigationSave.load(before, null);
        var blocked =
                new GroundDynamics.Terrain() {
                    public boolean clear(GroundTopology.Pose p) {
                        return false;
                    }

                    public double clearance(GroundTopology.Pose p) {
                        return 0;
                    }
                };
        assertThrows(
                IllegalStateException.class,
                () -> GroundDynamics.resume(model, blocked, loaded.find(player), 900));
        assertThrows(
                IllegalArgumentException.class,
                () -> GroundDynamics.resume(model, blocked, loaded.find(player), 899));
        assertEquals(before, loaded.save(new CompoundTag(), null));
        assertEquals(state, loaded.find(player));
    }
}
