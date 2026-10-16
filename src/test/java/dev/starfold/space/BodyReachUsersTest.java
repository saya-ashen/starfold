package dev.starfold.space;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.geometry.PlanetMotion;
import dev.starfold.planet.GroundDynamics;
import dev.starfold.sky.Space;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

class BodyReachUsersTest {
    private static GroundEphemeris.Model model(double reach) {
        return new GroundEphemeris.Model(
                List.of(
                        new GroundEphemeris.Body(
                                new GroundEphemeris.Fixed(new Point(0, 20000, 0)), 64, true, -1),
                        new GroundEphemeris.Body(
                                new GroundEphemeris.Fixed(GroundEphemeris.ZERO),
                                0,
                                false,
                                0,
                                reach)));
    }

    private static final GroundDynamics.Terrain EMPTY =
            new GroundDynamics.Terrain() {
                public boolean clear(PlanetShape.Pose pose) {
                    return true;
                }

                public double clearance(PlanetShape.Pose pose) {
                    return 10000;
                }
            };
    private static final PlanetMotion.Input IDLE = PlanetMotion.Input.idle(0, new Quaterniond());

    @Test
    void personalFlightUsesTheOwnedAndSelectedBodiesWithStrictBoundaries() {
        for (double reach : new double[] {512, 2048}) {
            var model = model(reach);
            for (double height : new double[] {2 * reach - 1, 2 * reach + 1}) {
                var pose = new PlanetShape.Pose(Face.TOP, new Point(1024, height, 1024));
                var local =
                        GroundDynamics.local(
                                0,
                                pose,
                                GroundEphemeris.ZERO,
                                new Quaterniond(),
                                true,
                                GroundDynamics.OFF);
                assertEquals(
                        height > 2 * reach,
                        GroundDynamics.step(model, EMPTY, local, IDLE, false).space());
            }
            for (double height : new double[] {1.5 * reach - 1, 1.5 * reach + 1}) {
                var pose = new PlanetShape.Pose(Face.TOP, new Point(1024, height, 1024));
                var local =
                        GroundDynamics.local(
                                0,
                                pose,
                                GroundEphemeris.ZERO,
                                new Quaterniond(),
                                true,
                                GroundDynamics.OFF);
                var detached = GroundDynamics.detach(model, local);
                assertEquals(
                        height >= 1.5 * reach,
                        GroundDynamics.step(model, EMPTY, detached, IDLE, false).space());
            }
        }
    }

    @Test
    void governorsAlignmentAndMissHysteresisUseTheDestinationEntryHeight() {
        var body = model(512).body(0);
        assertFalse(SpaceServer.align(body, 684, 0, false));
        assertTrue(SpaceServer.align(body, Math.nextDown(684.0), -1, false));
        assertFalse(SpaceServer.align(body, 1584, 100));
        assertTrue(SpaceServer.align(body, Math.nextDown(1584.0), 100));
        assertFalse(SpaceServer.inbound(body, false, 1, 884));
        assertTrue(SpaceServer.inbound(body, false, 1, Math.nextDown(884.0)));
        assertTrue(SpaceServer.inbound(body, true, 1, 1384));
        assertFalse(SpaceServer.inbound(body, true, 1, Math.nextUp(1384.0)));
        assertFalse(SpaceServer.governed(body, 684, false));
        assertTrue(SpaceServer.governed(body, Math.nextDown(684.0), false));
        assertEquals(150, SpaceServer.arrivalLimit(body, 384));
        assertEquals(250, SpaceServer.arrivalLimit(body, 984));
        assertEquals(350, SpaceServer.nearLimit(body, 984));
        var toward = new Point(1, 0, 0);
        var v = new Point(40, 30, 0);
        var radial = SpaceServer.arrival(body, v, toward, v, 384);
        assertEquals(7.5, radial.x());
        var near = SpaceServer.near(body, radial, toward, radial, 384);
        assertEquals(new Point(7.5, 0, 0), near);
        assertEquals(near, SpaceServer.govern(body, v, toward, v, 384, true));
        assertFalse(SpaceApproach.ready(body, 384, true));
        assertTrue(SpaceApproach.ready(body, 383, true));
        assertFalse(SpaceApproach.ready(body, 0, false));
        assertTrue(SpaceApproach.ready(body, -129, false));
    }

    @Test
    void forecastStopsAtTheSmallEntryShellRatherThanTheDefaultShell() {
        var model = model(512);
        var shape = PlanetShape.STANDARD;
        var motion =
                new Space.Motion(
                        new Point(0, shape.radius() + 1500, 0),
                        new Quaterniond(),
                        new Point(0, -40, 0),
                        new Vector3d());
        var predicted = SpaceApproach.predict(model, 0, shape, motion, 0);
        assertTrue(predicted.pose().point().y() <= 384);
        assertTrue(predicted.pose().point().y() > 370);
    }

    @Test
    void navigationBlockerUsesItsOwnBodyNotTheTargetsReach() {
        double radius = PlanetShape.of(0).radius();
        var small = model(512).body(0);
        var target =
                new GroundEphemeris.Body(
                        new GroundEphemeris.Fixed(new Point(0, 0, 30000)), 0, false, 1);
        var light = model(512).bodies().getFirst();
        var model = new GroundEphemeris.Model(List.of(light, small, target));
        var ship = new Point(2 * (radius + 500), 0, -30000);
        assertEquals(-1, SpaceNavigation.blocker(model, ship, 1, 0));
        var large = new GroundEphemeris.Body(small.motion(), 0, false, 0);
        assertEquals(
                0,
                SpaceNavigation.blocker(
                        new GroundEphemeris.Model(List.of(light, large, target)), ship, 1, 0));
    }

    @Test
    void worldBoundUsersPassTheBodyFoundFromTheirExistingOwnership() throws Exception {
        var frames = Sources.text("SableFrames");
        assertTrue(frames.contains("Space.leaves(clock.model.body(planet),point.y())"));
        assertTrue(frames.contains("Space.enters(clock.model.body(planet),height)"));
        assertTrue(
                frames.contains(
                        "ShipHost.get().landingReady(clock.model.body(planet),height,full)"));
        var weight = Sources.text("HullWeight");
        assertTrue(weight.contains("GroundClock.get((ServerLevel)level).clock.model"));
        assertTrue(weight.contains("access.storagePlanet(feet.x(),feet.z())"));
        assertTrue(weight.contains("at(model.body(planet),access.chart(feet).y())"));
        for (String file : new String[] {"SableFrames", "LiftServer", "Airstair"})
            assertTrue(Sources.text(file).contains("HullWeight.at("));
        var server = Sources.text("SpaceServer");
        assertTrue(
                server.contains(
                        "align(model.body(destination),entryHeight,entryClosing,value.inbound)"));
        assertTrue(server.contains("entryHeight<model.body(destination).hullIn()+300"));
        assertTrue(server.contains("inbound(model.body(destination),"));
        assertTrue(server.contains("inbound(model.body(nearest),"));
        assertTrue(
                server.contains("govern(model.body(nearest),drift,toward,relative,height,true)"));
        var client = Sources.text("SpaceNavigationClient");
        assertTrue(client.contains("height-model.body(planet).hullIn()"));
        assertTrue(client.contains("SpaceServer.arrivalLimit(model.body(planet),height)"));
        assertTrue(client.contains("SpaceServer.nearLimit(model.body(planet),height)"));
    }
}
