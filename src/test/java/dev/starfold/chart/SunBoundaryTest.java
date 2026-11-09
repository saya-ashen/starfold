package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

class SunBoundaryTest {
    private static final Point ZERO = new Point(0, 0, 0);

    private static void same(Point expected, Point actual) {
        assertEquals(expected.x(), actual.x(), 1e-8);
        assertEquals(expected.y(), actual.y(), 1e-8);
        assertEquals(expected.z(), actual.z(), 1e-8);
    }

    @Test
    void enlargedDefaultUsesTheVisibleBodyRadiusWithExistingMarginAndHullPadding() {
        var model = GroundSolar.defaults();
        var sun = model.primary();
        assertEquals(768, sun.radius());
        for (double padding : new double[] {0, 1, 20}) {
            double wall = sun.radius() + SunBoundary.MARGIN + padding;
            var start = sun.position().add(new Point(wall + 3, 0, 0));
            var step = SunBoundary.clip(model, start, new Point(-10000, 0, 0), 0, padding);
            same(new Point(-3, 0, 0), step);
            assertEquals(wall, start.add(step).sub(sun.position()).length(), 1e-8);
            assertTrue(start.add(step).sub(sun.position()).length() > sun.radius());
            var contact = sun.position().add(new Point(wall, 0, 0));
            var tangent = new Point(0, 30, 4);
            same(tangent, SunBoundary.clip(model, contact, tangent, 0, padding));
            var outward = new Point(20, 3, 0);
            same(outward, SunBoundary.clip(model, start, outward, 0, padding));
            var grazingStart = sun.position().add(new Point(wall + 12, wall / 2, 0));
            var grazing =
                    SunBoundary.clip(model, grazingStart, new Point(-4 * wall, 0, 0), 0, padding);
            var offset = grazingStart.sub(sun.position());
            double t = Math.clamp(-offset.dot(grazing) / grazing.dot(grazing), 0, 1);
            assertTrue(offset.add(grazing.scale(t)).length() >= wall - 1e-7);
        }
    }

    @Test
    void inwardStopsAtBoundaryEvenWhenWholeSunWouldBeCrossed() {
        same(
                new Point(-2, 0, 0),
                SunBoundary.clip(new Point(12, 0, 0), new Point(-100, 0, 0), ZERO, 10));
        same(ZERO, SunBoundary.clip(new Point(10, 0, 0), new Point(-5, 0, 0), ZERO, 10));
    }

    @Test
    void tangentIsKeptAndOutwardIsFree() {
        same(
                new Point(0, 3, -4),
                SunBoundary.clip(new Point(10, 0, 0), new Point(-8, 3, -4), ZERO, 10));
        var tangent = new Point(0, 20, 3);
        same(tangent, SunBoundary.clip(new Point(10, 0, 0), tangent, ZERO, 10));
        var outward = new Point(100, 7, -3);
        same(outward, SunBoundary.clip(new Point(11, 0, 0), outward, ZERO, 10));
    }

    @Test
    void insideIsNotEjectedOrAllowedFurtherInward() {
        same(
                new Point(0, 3, 0),
                SunBoundary.clip(new Point(5, 0, 0), new Point(-8, 3, 0), ZERO, 10));
        var outward = new Point(2, 1, 0);
        same(outward, SunBoundary.clip(new Point(5, 0, 0), outward, ZERO, 10));
        same(outward, SunBoundary.clip(ZERO, outward, ZERO, 10));
    }

    @Test
    void grazingSegmentWithBothEndpointsOutsideCannotTunnel() {
        var start = new Point(12, 5, 0);
        var result = SunBoundary.clip(start, new Point(-30, 0, 0), ZERO, 10);
        assertTrue(start.add(result).length() >= 10 - 1e-8);
        assertTrue(result.y() > 0, "slide along the contact plane, not freeze all motion");
    }

    @Test
    void clippedStraightSegmentsStayOutsideTheSphere() {
        var random = new java.util.Random(71);
        for (int i = 0; i < 10000; i++) {
            var start = new Point(10 + random.nextDouble() * 100, 0, 0);
            var wish =
                    new Point(
                            -random.nextDouble() * 1000,
                            (random.nextDouble() - .5) * 100,
                            (random.nextDouble() - .5) * 100);
            var step = SunBoundary.clip(start, wish, ZERO, 10);
            double t =
                    step.dot(step) == 0 ? 0 : Math.clamp(-start.dot(step) / step.dot(step), 0, 1);
            assertTrue(start.add(step.scale(t)).length() >= 10 - 1e-7);
        }
    }

    @Test
    void shortUnobstructedMotionIsUnchanged() {
        var motion = new Point(-1, 0, 0);
        same(motion, SunBoundary.clip(new Point(12, 0, 0), motion, ZERO, 10));
        same(ZERO, SunBoundary.clip(new Point(12, 0, 0), ZERO, ZERO, 10));
    }

    @Test
    void allSunsAndPaddingAreUsedNotOnlyThePrimary() {
        var model = dev.starfold.planet.PlanetSystem.model(new int[] {1, 1});
        var star = model.everyStar().get(1);
        double wall = star.radius() + SunBoundary.MARGIN + 20;
        var at = star.position().add(new Point(wall + 3, 0, 0));
        same(new Point(-3, 0, 0), SunBoundary.clip(model, at, new Point(-10000, 0, 0), 0, 20));
    }

    @Test
    void personalFlightKernelUsesTheSameWallForPredictionAndAuthority() {
        var model = GroundSolar.defaults();
        var star = model.primary();
        var position = star.position().add(new Point(star.radius() + SunBoundary.MARGIN + 3, 0, 0));
        var old =
                new dev.starfold.planet.GroundDynamics.State(
                        0,
                        true,
                        position,
                        new Point(-20, 0, 0),
                        new Quaterniond(),
                        null,
                        true,
                        false,
                        dev.starfold.planet.GroundDynamics.OFF,
                        0);
        var terrain =
                new dev.starfold.planet.GroundDynamics.Terrain() {
                    public boolean clear(PlanetShape.Pose pose) {
                        return true;
                    }

                    public double clearance(PlanetShape.Pose pose) {
                        return 10000;
                    }
                };
        var next =
                dev.starfold.planet.GroundDynamics.step(
                        model,
                        terrain,
                        old,
                        dev.starfold.geometry.PlanetMotion.Input.idle(0, new Quaterniond()),
                        false);
        assertTrue(next.space());
        assertEquals(
                star.radius() + SunBoundary.MARGIN + 1,
                next.position().sub(star.position()).length(),
                1e-8);
        assertEquals(-2, next.velocity().x(), 1e-8);
    }

    @Test
    void productionHooksCoverPacketAcceptanceAndBothKindsOfHullMotion() throws Exception {
        var network = dev.starfold.Sources.text("GroundFlightNetworkMixin");
        assertTrue(network.contains("starfold$sunPacket"));
        assertTrue(network.contains("newServerboundMovePlayerPacket.PosRot("));
        var entity = dev.starfold.Sources.text("GroundTopologyEntityMixin");
        assertTrue(entity.contains("SpaceServer.sunMotion((Entity)(Object)this,motion)"));
        var server = dev.starfold.Sources.text("SpaceServer");
        assertTrue(server.contains("sunPadding(lead)"));
        assertTrue(server.contains("sunPadding(body)"));
        assertTrue(server.contains("ForgeSablePrePhysicsTickEvent"));
        assertTrue(server.contains("EventPriority.LOWEST"));
        assertTrue(server.contains("sunPhysics(event.getPhysicsSystem(),event.getTimeStep())"));
        assertTrue(server.contains("pipeline.addLinearAndAngularVelocity(body,"));
        var client = dev.starfold.Sources.text("SpaceClient");
        assertTrue(client.contains("SpaceServer.clientSunMotion="));
    }

    @Test
    void repeatedPhysicsSubstepsCannotCreepInwardUnderSustainedThrust() {
        var model = GroundSolar.defaults();
        var star = model.primary();
        double radius = star.radius() + SunBoundary.MARGIN;
        var world = star.position().add(new Point(radius, 0, 0));
        var frame =
                new Space.Frame(new Quaterniond(), Space.ORIGIN, 0, ZERO, new Point(-10, 0, 0), 0);
        var level = frame.level(world, model, 0);
        double span = .1;
        for (int i = 0; i < 2000; i++) {
            double tick = i * span;
            var step =
                    SunBoundary.inFrame(model, frame, level, new Point(-.02, 0, 0), tick, 0, span);
            level = level.add(step);
            assertTrue(
                    frame.universe(level, model, tick + span).sub(star.position()).length()
                            >= radius - 1e-7);
        }
    }

    @Test
    void frameTranslationIsIncludedAndRotatedBack() {
        var model = GroundSolar.defaults();
        var star = model.primary();
        var at = star.position().add(new Point(star.radius() + SunBoundary.MARGIN + 2, 0, 0));
        var frame =
                new Space.Frame(
                        new Quaterniond().rotationZ(.7),
                        Space.ORIGIN,
                        0,
                        ZERO,
                        new Point(-10, 0, 0),
                        0);
        var level = frame.level(at, model, 0);
        var limited = SunBoundary.inFrame(model, frame, level, ZERO, 0, 0);
        var next = frame.universe(level.add(limited), model, 1);
        assertTrue(next.sub(star.position()).length() >= star.radius() + SunBoundary.MARGIN - 1e-8);
    }
}
