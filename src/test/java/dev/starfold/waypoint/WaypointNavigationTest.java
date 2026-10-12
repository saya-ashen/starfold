package dev.starfold.waypoint;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.ChartLabSpace;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.ground.GroundTopology;
import dev.starfold.planet.PlanetShape;

import net.minecraft.nbt.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.UUID;

class WaypointNavigationTest {
    private GroundTopology.Pose pose(Face face, double x, double z) {
        return new GroundTopology.Pose(face, new Point(x, 30, z));
    }

    private Waypoint marker(String name) {
        return new Waypoint(name, 0, pose(Face.TOP, 500, 500));
    }

    @Test
    void metadataUsesNewChartSessionBeforeTheHullLeavesRegionCoordinates() {
        assertTrue(WaypointNavigation.accepts(42, 42, 17));
        assertFalse(WaypointNavigation.accepts(17, 42, 17));
        assertTrue(WaypointNavigation.accepts(17, 0, 17));
        assertFalse(WaypointNavigation.accepts(42, 0, 17));
        assertFalse(WaypointNavigation.accepts(0, 0, 0));
    }

    @Test
    void signedTurnUsesMinecraftYawAndWraps() {
        assertEquals(-90, WaypointNavigation.angle(0, new Point(1, 0, 0)), 1e-8);
        assertEquals(90, WaypointNavigation.angle(0, new Point(-1, 0, 0)), 1e-8);
        // Continuous arrow bearings replace the old text-only left/right/ahead categories.
        for (double angle : new double[] {-180, -37, 0, 4, 12, 180})
            assertEquals(
                    angle,
                    WaypointNavigation.angle(
                            0,
                            new Point(
                                    -Math.sin(Math.toRadians(angle)),
                                    0,
                                    Math.cos(Math.toRadians(angle)))),
                    1e-8);
        assertEquals(
                2,
                WaypointNavigation.angle(
                        179,
                        new Point(
                                -Math.sin(Math.toRadians(-179)),
                                0,
                                Math.cos(Math.toRadians(-179)))),
                1e-8);
    }

    @Test
    void selectionAndAutomaticFallbackAreIndependent() {
        var manual = marker("base");
        var launch = marker(Waypoint.LAUNCH);
        assertEquals("starfold:launch", launch.name());
        assertEquals(
                "message.starfold.mark.invalid",
                assertThrows(
                                Waypoint.Failure.class,
                                () -> new WaypointSave().add(UUID.randomUUID(), launch))
                        .key);
        assertNull(WaypointSave.active(null, null));
        assertEquals(launch, WaypointSave.active(null, launch));
        assertEquals(manual, WaypointSave.active(manual, launch));
        var save = new WaypointSave();
        var id = UUID.randomUUID();
        save.add(id, manual);
        save.select(id, "base");
        save.launch(id, launch);
        assertEquals(manual, save.active(id));
        save.remove(id, "base");
        assertEquals(launch, save.active(id));
    }

    @Test
    void launchSlotRoundTripsAndDoesNotConsumeNamedCapacity() throws Exception {
        var save = new WaypointSave();
        var id = UUID.randomUUID();
        save.launch(id, marker(Waypoint.LAUNCH));
        for (int i = 0; i < 64; i++) save.add(id, marker("m" + i));
        save.select(id, "m1");
        var file = Path.of("build/native-exploration/launch-slot.dat");
        Files.createDirectories(file.getParent());
        NbtIo.writeCompressed(save.save(new CompoundTag(), null), file);
        var loaded =
                WaypointSave.load(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()), null);
        assertEquals(save.active(id), loaded.active(id));
        assertEquals(64, loaded.list(id).size());
        loaded.clear(id);
        assertEquals(marker(Waypoint.LAUNCH), loaded.active(id));
        loaded.launch(id, marker("new_launch"));
        assertEquals(marker("new_launch"), loaded.active(id));
        assertNull(loaded.active(UUID.randomUUID()));
    }

    @Test
    void malformedLaunchSlotFailsClosed() {
        var save = new WaypointSave();
        var id = UUID.randomUUID();
        save.launch(id, marker(Waypoint.LAUNCH));
        var tag = save.save(new CompoundTag(), null);
        tag.getCompound("players").getCompound(id.toString()).putString("launch", "bad");
        assertThrows(IllegalArgumentException.class, () -> WaypointSave.load(tag, null));
    }

    @Test
    void launchRequiresKnownRestingGround() {
        assertTrue(WaypointNavigation.grounded(0, .1));
        assertFalse(WaypointNavigation.grounded(2, 0));
        assertFalse(WaypointNavigation.grounded(Double.NaN, 0));
        assertFalse(WaypointNavigation.grounded(0, 3));
        assertFalse(WaypointNavigation.grounded(-20, 0));
    }

    @Test
    void allFacesHaveSymmetricSurfaceRoutes() {
        var shape = PlanetShape.STANDARD;
        double n = shape.size();
        for (var a : Face.values())
            for (var b : Face.values()) {
                var from = pose(a, n * .3, n * .4);
                var to = pose(b, n * .7, n * .6);
                var route = WaypointNavigation.route(shape, from, to);
                assertNotNull(route, a + " -> " + b);
                assertEquals(
                        route.distance(),
                        WaypointNavigation.route(shape, to, from).distance(),
                        1e-6);
                assertEquals(route.distance(), route.direction().length(), 1e-7);
            }
    }

    @Test
    void returnDistanceAndDirectionAreContinuousAcrossEachSeam() {
        var shape = PlanetShape.STANDARD;
        double n = shape.size();
        for (var face : Face.values())
            for (var step : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                var home = pose(face, n / 2, n / 2);
                var outside =
                        new Point(
                                n / 2 + step[0] * (n / 2 + 10), 30, n / 2 + step[1] * (n / 2 + 10));
                var transported = GroundTopology.canonical(shape, face, outside);
                var route = WaypointNavigation.route(shape, transported.pose(), home);
                assertNotNull(route);
                assertEquals(n / 2 + 10, route.distance(), 1e-6);
                var bearing = ChartLabSpace.rotate(route.direction(), -transported.turns());
                assertEquals(-step[0], bearing.x() / route.distance(), 1e-6);
                assertEquals(-step[1], bearing.z() / route.distance(), 1e-6);
                var inside =
                        pose(face, n / 2 + step[0] * (n / 2 - 10), n / 2 + step[1] * (n / 2 - 10));
                assertEquals(
                        route.distance() - 20,
                        WaypointNavigation.route(shape, inside, home).distance(),
                        1e-6);
            }
    }
}
