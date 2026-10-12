package dev.starfold.flight;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.geometry.OrbitAttitude;
import dev.starfold.geometry.PlanetMotion;
import dev.starfold.ground.GroundIntegrationKernelTest;
import dev.starfold.ground.GroundTopology;
import dev.starfold.sky.GroundEphemeris;
import dev.starfold.sky.GroundSolar;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.*;

/** Flight controls between the ground handoff and free space turn gradually from vanilla creative flight into
 * direct flight. Found by the in-game A→B→A run: diving at a planet with W stopped dead at 1536 and slid sideways. */
class DiveControlsTest {
    @BeforeAll
    static void registry() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    final GroundEphemeris.Model model = GroundSolar.defaults();
    static final GroundDynamics.Terrain OPEN = GroundIntegrationKernelTest.FLAT;

    GroundDynamics.State hover(double height) {
        return GroundDynamics.local(
                900,
                new GroundTopology.Pose(Face.TOP, new Point(1024, height, 1024)),
                new Point(0, 0, 0),
                OrbitAttitude.upright(27, 0),
                true,
                GroundDynamics.OFF);
    }

    /** Universe aim looking at the planet's centre, optionally nudged off the exact vertical. */
    Quaterniond down(GroundDynamics.State s, double nudgeX, double nudgeZ) {
        var d = model.at(0, s.tick()).center().sub(GroundDynamics.position(model, s, s.tick()));
        return new Quaterniond()
                .rotationTo(
                        new Vector3d(0, 0, 1),
                        new Vector3d(
                                        d.x() + nudgeX * d.length(),
                                        d.y(),
                                        d.z() + nudgeZ * d.length())
                                .normalize());
    }

    /** Chart displacement after holding the given keys for some ticks with a fixed aim. */
    Point fly(
            GroundDynamics.State s,
            Quaterniond q,
            float forward,
            float rise,
            boolean direct,
            int ticks) {
        var start = s.local().point();
        for (int i = 0; i < ticks; i++)
            s =
                    GroundDynamics.step(
                            model,
                            OPEN,
                            s,
                            new PlanetMotion.Input(
                                    i, q.x, q.y, q.z, q.w, forward, 0, rise, direct, false),
                            false);
        return s.local().point().sub(start);
    }

    static double level(Point d) {
        return Math.hypot(d.x(), d.z());
    }

    @Test
    void divingWithForwardKeepsDescendingBelowTheOldSwitchHeight() {
        for (double height : new double[] {1500, 1535, 1537, 1100}) {
            var s = hover(height);
            var moved = fly(s, down(s, 0, 0), 1, 0, false, 10);
            assertTrue(moved.y() < -20, height + " " + moved);
            assertTrue(level(moved) < .05 * -moved.y(), height + " " + moved);
        }
    }

    @Test
    void nearTheGroundForwardStaysLevelLikeVanillaFlight() {
        var s = hover(200);
        var moved = fly(s, down(s, 0, 0), 1, 0, false, 10);
        assertEquals(0, moved.y(), .05);
        assertTrue(level(moved) > 2, moved.toString());
    }

    @Test
    void lookingStraightDownHasOneStableHeading() {
        var s = hover(200);
        var base = fly(s, down(s, 1e-3, 0), 1, 0, false, 10);
        // The old projection normalised a vanishing vector: a hair of aim change picked an
        // unrelated direction at full speed.
        for (double[] nudge :
                new double[][] {{1.2e-3, 0}, {1e-3, 2e-4}, {1e-3, -2e-4}, {2e-2, 0}}) {
            var other = fly(s, down(s, nudge[0], nudge[1]), 1, 0, false, 10);
            double cos =
                    (base.x() * other.x() + base.z() * other.z()) / (level(base) * level(other));
            assertTrue(cos > .95, nudge[0] + "," + nudge[1] + " " + cos);
        }
    }

    @Test
    void descentFadesInSmoothlyWithHeight() {
        double previous = -1;
        for (double height = 180; height <= 1500; height += 20) {
            var s = hover(height);
            var moved = fly(s, down(s, 0, 0), 1, 0, false, 1);
            double path = Math.hypot(level(moved), moved.y());
            double share = path < 1e-9 ? 0 : -moved.y() / path;
            assertTrue(share >= previous - 1e-3, height + " " + share);
            assertTrue(
                    previous < 0 || share - previous < .12, height + " jump " + (share - previous));
            previous = share;
        }
        assertEquals(1, previous, 1e-3);
        assertEquals(0, GroundDynamics.directness(GroundDynamics.DIVE_START, false));
        assertEquals(1, GroundDynamics.directness(GroundDynamics.DIVE_END, false));
    }

    @Test
    void directKeyDivesAtAnyHeightAndShiftAlwaysDescendsNearTheGround() {
        var s = hover(200);
        assertTrue(fly(s, down(s, 0, 0), 1, 0, true, 10).y() < -2);
        var level = GroundDynamics.orientation(model, s, s.tick());
        var sunk = fly(s, level, 0, -1, false, 10);
        assertTrue(sunk.y() < -2);
        assertTrue(level(sunk) < .05);
    }

    @Test
    void levelFlightIsUnchangedAtEveryHeight() {
        for (double height : new double[] {200, 600, 1400}) {
            var s = hover(height);
            var moved = fly(s, GroundDynamics.orientation(model, s, s.tick()), 1, 0, false, 10);
            // Low down the craft holds its height exactly. Higher up thrust follows the view, and a
            // fixed view over a
            // curved planet leaves the tangent a little: it may drift, but must stay essentially
            // level.
            assertTrue(level(moved) > 2);
            assertTrue(
                    Math.abs(moved.y())
                            < (height <= GroundDynamics.DIVE_START ? .002 : .08) * level(moved),
                    height + " " + moved);
        }
    }
}
