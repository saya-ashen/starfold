package dev.starfold.planet;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.geometry.PlanetMotion;
import dev.starfold.geometry.PlanetSpace;
import java.util.List;
import java.util.Random;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;

class GroundDynamicsSweepTest {
    static final GroundDynamics.Terrain TERRAIN = new GroundDynamics.Terrain() {
        public boolean clear(PlanetShape.Pose pose) {
            var p = pose.point();
            // A solid surface and a raised strip on both sides of every seam.
            double top = p.x() < 3 || p.x() > 2045 || p.z() < 3 || p.z() > 2045 ? 180 : 170;
            return p.y() >= top;
        }
        public double clearance(PlanetShape.Pose pose) { return pose.point().y() - 180; }
    };

    GroundEphemeris.Model model() {
        return model(7000);
    }

    GroundEphemeris.Model model(double separation) {
        var spin = new GroundEphemeris.Spin(true, GroundEphemeris.Y, new Quaterniond(), 0, 48000, 0);
        return new GroundEphemeris.Model(List.of(
                new GroundEphemeris.Body(new GroundEphemeris.Fixed(new Point(0, -80000, 0)),
                        100, true, -1, GroundEphemeris.Appearance.LIGHT),
                new GroundEphemeris.Body(new GroundEphemeris.Orbit(0, GroundEphemeris.Y,
                        new Point(1, 0, 0), 80000, 100000, 0, 0, spin),
                        PlanetShape.STANDARD.radius(), false, 0, GroundEphemeris.Appearance.SURFACE),
                new GroundEphemeris.Body(new GroundEphemeris.Orbit(1, GroundEphemeris.Y,
                        new Point(1, 0, 0), separation, 25000, 0, 0, spin),
                        PlanetShape.STANDARD.radius(), false, 1, GroundEphemeris.Appearance.SURFACE)));
    }

    GroundDynamics.State local(long tick, Face face, double u, double h, double v) {
        return GroundDynamics.local(tick, new PlanetShape.Pose(face, new Point(u, h, v)),
                GroundEphemeris.ZERO, new Quaterniond(), true, GroundDynamics.OFF);
    }

    Point run(GroundEphemeris.Model model, GroundDynamics.State state, Point velocity) {
        var sweep = new GroundDynamics.Sweep(model, TERRAIN, state, velocity);
        var p = state.position();
        var part = velocity.scale(1.0 / sweep.steps);
        for (int i = 0; i < sweep.steps; i++) {
            var next = p.add(part);
            if (!sweep.clear(next, state.tick() + (i + 1.0) / sweep.steps)) break;
            p = next;
        }
        assertTrue(sweep.clear(p, state.tick() + 1), "no penetration at end of tick");
        return p;
    }

    void compare(GroundEphemeris.Model model, GroundDynamics.State state, Point velocity, String label) {
        var expected = GroundDynamicsReference.sweep(model, TERRAIN, state, velocity);
        var actual = run(model, state, velocity);
        var end = state.position().add(velocity);
        boolean oldCollision = expected.sub(end).length() > 1e-7;
        boolean newCollision = actual.sub(end).length() > 1e-7;
        assertEquals(oldCollision, newCollision, label);
        assertTrue(expected.sub(actual).length() <= .200001,
                () -> label + " endpoint error " + expected.sub(actual).length());
        // Independent dense whole-model check, including bodies rejected by broad phase.
        for (int id = 0; id < model.count(); id++) {
            Point world = state.space() ? actual : model.at(state.planet(), state.tick() + 1).world(actual);
            Point p = model.at(id, state.tick() + 1).local(world);
            double h = p.length() - PlanetShape.of(id).radius();
            assertTrue(h >= GroundSpace.MIN + 1, label + " core");
            if (h <= GroundOrbit.LIMIT)
                assertTrue(TERRAIN.clear(id, GroundTopology.locate(PlanetShape.of(id), p)), label + " terrain");
        }
    }

    @Test
    void approachingGrazingBetweenBodiesAndEverySeamMatchFullOracle() {
        var m = model();
        for (Face face : Face.values()) {
            var state = local(1200, face, 1024, 195, 1024);
            compare(m, state, PlanetSpace.unit(state.position()).scale(-80), "approach " + face);
            compare(m, state, new Point(150, 0, 80), "graze " + face);
            for (double u : new double[] {3.1, 2044.9}) {
                var seam = local(1200, face, u, 195, 1024);
                compare(m, seam, GroundTopology.planetVelocity(PlanetShape.STANDARD, seam.local(),
                        new Point(u < 100 ? -100 : 100, -30, 0)), "seam " + face + " " + u);
            }
        }
        var a = m.at(0, 1200).center();
        var b = m.at(1, 1200).center();
        var state = new GroundDynamics.State(1200, true, a.add(b).scale(.5),
                GroundEphemeris.ZERO, new Quaterniond(), null, true, false, GroundDynamics.OFF);
        compare(m, state, PlanetSpace.unit(b.sub(a)).scale(300), "between planets");
    }

    @Test
    void fixedSeed2400RandomTrajectoriesThroughSurfaceSeamsAndBetweenBodies() {
        var near = model();
        var far = model(90000);
        var random = new Random(0x4964c2b7L);
        for (int i = 0; i < 2400; i++) {
            var m = i % 2 == 0 ? near : far;
            long tick = random.nextInt(100000);
            var face = Face.values()[random.nextInt(6)];
            boolean seam = i % 3 == 1;
            double u = seam ? (random.nextBoolean() ? 2 : 2046) : 50 + random.nextDouble() * 1948;
            var state = local(tick, face, u, 185 + random.nextDouble() * 200, 1024);
            if (i % 3 == 2) {
                var a = m.at(0, tick).center();
                var b = m.at(1, tick).center();
                var p = a.add(b.sub(a).scale(.43 + random.nextDouble() * .14));
                state = new GroundDynamics.State(tick, true, p, GroundEphemeris.ZERO,
                        new Quaterniond(), null, true, false, GroundDynamics.OFF);
            }
            var axis = new Point(random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1,
                    random.nextDouble() * 2 - 1);
            double speed = i == 0 ? 0 : i == 1 ? 300 : random.nextDouble() * 300;
            compare(m, state, PlanetSpace.unit(axis).scale(speed), "trajectory " + i);
        }
    }

    @Test
    void bodySpeedBoundsIncludeAncestorsAndRejectDistantSweeps() {
        var m = model(90000);
        double expected = 2 * Math.PI * (80000.0 / 100000 + 90000.0 / 25000);
        assertEquals(expected, m.speed(1), 1e-12);
        for (int tick = 0; tick < 100000; tick += 997)
            assertTrue(m.at(1, tick + 1).center().sub(m.at(1, tick).center()).length()
                    <= m.speed(1) + 1e-8);
        var state = local(1200, Face.TOP, 1024, 300, 1024);
        assertEquals(1, new GroundDynamics.Sweep(m, TERRAIN, state, GroundEphemeris.ZERO).steps);
    }

    @Test
    void hoverUsesOneStepAndSharedKernelIsExactlyDeterministic() {
        var m = model();
        var state = local(1200, Face.TOP, 1024, 300, 1024);
        // With a second nearby planet the conservative bound must still include its motion.
        var fixed = new GroundEphemeris.Model(List.of(
                new GroundEphemeris.Body(new GroundEphemeris.Fixed(new Point(0, -80000, 0)),
                        100, true, -1, GroundEphemeris.Appearance.LIGHT),
                new GroundEphemeris.Body(new GroundEphemeris.Fixed(GroundEphemeris.ZERO),
                        PlanetShape.STANDARD.radius(), false, 0, GroundEphemeris.Appearance.SURFACE)));
        assertEquals(1, new GroundDynamics.Sweep(fixed, TERRAIN, state, GroundEphemeris.ZERO).steps);
        for (int i = 0; i < 100; i++) {
            var motion = PlanetMotion.Input.idle(i + 1, GroundDynamics.orientation(m, state, state.tick()));
            var a = GroundDynamics.step(m, TERRAIN, state, motion, false);
            var b = GroundDynamics.step(m, TERRAIN, state, motion, false);
            assertEquals(a, b);
            state = a;
        }
    }
}
