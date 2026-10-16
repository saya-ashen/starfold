package dev.starfold.sky;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundSolar;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.planet.GroundTopology;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.PlanetStorage;
import dev.starfold.planet.PlanetSystem;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

public class SpaceTest {
    static final double EPS = 1e-6;
    public static final Point ZERO = new Point(0, 0, 0);
    public static final PlanetShape[] SHAPES = {new PlanetShape(2048), new PlanetShape(1024)};

    public static void point(Point expected, Point actual) {
        assertEquals(expected.x(), actual.x(), EPS);
        assertEquals(expected.y(), actual.y(), EPS);
        assertEquals(expected.z(), actual.z(), EPS);
    }

    public static Vector3d vector(Point p) {
        return new Vector3d(p.x(), p.y(), p.z());
    }

    public static void vector(Vector3d expected, Vector3d actual) {
        assertEquals(expected.x, actual.x, EPS);
        assertEquals(expected.y, actual.y, EPS);
        assertEquals(expected.z, actual.z, EPS);
    }

    public static void aim(Quaterniond expected, Quaterniond actual) {
        assertEquals(1, Math.abs(expected.dot(actual)), EPS);
        for (var axis :
                new Vector3d[] {
                    new Vector3d(1, 0, 0), new Vector3d(0, 1, 0), new Vector3d(0, 0, 1)
                })
            vector(expected.transform(new Vector3d(axis)), actual.transform(new Vector3d(axis)));
    }

    public static void motion(Space.Motion expected, Space.Motion actual) {
        point(expected.position(), actual.position());
        point(expected.velocity(), actual.velocity());
        aim(expected.orientation(), actual.orientation());
        vector(expected.spin(), actual.spin());
    }

    public static GroundEphemeris.Model tilted() {
        var a =
                new GroundEphemeris.Spin(
                        true,
                        new Point(.6, .8, 0),
                        new Quaterniond().rotationXYZ(.2, -.4, .6),
                        .7,
                        48000,
                        12);
        var b =
                new GroundEphemeris.Spin(
                        true,
                        new Point(0, .8, .6),
                        new Quaterniond().rotationXYZ(-.3, .5, -.2),
                        -.4,
                        72000,
                        12);
        return new GroundEphemeris.Model(
                java.util.List.of(
                        new GroundEphemeris.Body(
                                new GroundEphemeris.Fixed(new Point(100, -200, 300)),
                                512,
                                true,
                                -1),
                        new GroundEphemeris.Body(
                                new GroundEphemeris.Orbit(
                                        0,
                                        new Point(0, 1, 0),
                                        new Point(1, 0, 0),
                                        7000,
                                        30000,
                                        .3 + Math.PI,
                                        12,
                                        a),
                                0,
                                false,
                                0),
                        new GroundEphemeris.Body(
                                new GroundEphemeris.Orbit(
                                        0,
                                        new Point(0, 1, 0),
                                        new Point(1, 0, 0),
                                        7000,
                                        30000,
                                        .3,
                                        12,
                                        b),
                                0,
                                false,
                                1)));
    }

    public static Space.Motion chart(Point p) {
        return new Space.Motion(
                p,
                new Quaterniond().rotationXYZ(.3, -.5, .7),
                new Point(2, -3, 4),
                new Vector3d(.01, -.02, .03));
    }

    public static Space.Motion resting(Point p) {
        return new Space.Motion(p, new Quaterniond(), ZERO, new Vector3d());
    }

    @Test
    void identityTranslatesFromTheReferenceCentre() {
        var model = tilted();
        assertEquals(
                new Point(-16384, 8192, -16384), Space.level(model.at(0, 0).center(), model, 0));
        for (double tick : new double[] {0, 1234, 9876})
            for (var p :
                    new Point[] {
                        ZERO, new Point(123.25, -456.5, 789.75), new Point(-100000, 32768, 100000)
                    }) {
                point(p, Space.universe(Space.level(p, model, tick), model, tick));
                point(p, Space.level(Space.universe(p, model, tick), model, tick));
            }
        var a = new Point(12, 34, 56);
        var b = new Point(-1, -2, -3);
        point(a.sub(b), Space.level(a, model, 0).sub(Space.level(b, model, 0)));
    }

    @Test
    void regionFrameRoundTripsAndRotatesMotionWithoutShiftingVectors() {
        var model = tilted();
        for (double tick : new double[] {0, 1234, 9876})
            for (int planet = 0; planet < 2; planet++)
                for (var q :
                        new Quaterniond[] {
                            new Quaterniond(), new Quaterniond().rotationXYZ(.4, -.9, 1.2)
                        }) {
                    var frame = new Space.Frame(q, new Point(-123456, 45123, -456789), planet);
                    var m = chart(new Point(12, -34, 56));
                    motion(m, frame.universe(frame.level(m, model, tick), model, tick));
                    motion(m, frame.level(frame.universe(m, model, tick), model, tick));
                    point(
                            m.position(),
                            frame.universe(frame.level(m.position(), model, tick), model, tick));
                    point(m.velocity(), frame.universeVector(frame.levelVector(m.velocity())));
                    var rotated =
                            q.transform(
                                    vector(m.velocity().sub(model.at(planet, tick).velocity())));
                    vector(rotated, vector(frame.level(m, model, tick).velocity()));
                    vector(q.transform(m.spin()), frame.level(m, model, tick).spin());
                    aim(
                            new Quaterniond(q).mul(m.orientation()),
                            frame.level(m, model, tick).orientation());
                    point(
                            frame.levelVector(m.position().sub(model.at(planet, tick).center())),
                            frame.level(m.position(), model, tick).sub(frame.shift()));
                }
    }

    @Test
    void emptyRegionKeepsEveryDepartureOrientationAndPlacesTheAnchorAtOrigin() {
        var model = tilted();
        for (int id = 0; id < 2; id++)
            for (var shape : SHAPES)
                for (var face : Face.values()) {
                    var pose =
                            new PlanetShape.Pose(
                                    face, new Point(.2 * shape.size(), 2049, .7 * shape.size()));
                    var chart = chart(pose.point());
                    var universe = Space.out(model, id, shape, pose, chart, 1234);
                    var frame = Space.Frame.keeping(universe, chart.orientation(), model, id, 1234);
                    var placed = frame.level(universe, model, 1234);
                    point(ZERO, placed.velocity());
                    point(new Point(Space.X, Space.Y, Space.Z), placed.position());
                    aim(chart.orientation(), placed.orientation());
                    motion(universe, frame.universe(placed, model, 1234));
                }
    }

    @Test
    void framesValidateAndCopyTheirMutableRotation() {
        var q = new Quaterniond().rotationX(.4);
        var frame = new Space.Frame(q, ZERO, 0);
        q.identity();
        frame.rotation().identity();
        aim(new Quaterniond().rotationX(.4), frame.rotation());
        assertThrows(
                IllegalArgumentException.class,
                () -> new Space.Frame(new Quaterniond(0, 0, 0, 0), ZERO, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Space.Frame(new Quaterniond(), new Point(Double.NaN, 0, 0), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> frame.levelOrientation(new Quaterniond(0, 0, 0, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> frame.universeVector(new Point(0, Double.POSITIVE_INFINITY, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        Space.Frame.keeping(
                                chart(ZERO), new Quaterniond(0, 0, 0, 0), tilted(), 0, 0));
        Space.Frame.IDENTITY.rotation().rotateX(1);
        aim(new Quaterniond(), Space.Frame.IDENTITY.rotation());
    }

    @Test
    void levelRestFollowsOnlyTheCentreNotTheSpin() {
        var model = tilted();
        for (int planet = 0; planet < 2; planet++) {
            var frame =
                    new Space.Frame(
                            new Quaterniond().rotationXYZ(.4, -.9, 1.2), Space.ORIGIN, planet);
            var still = resting(Space.ORIGIN.add(new Point(100, 200, 300)));
            Point offset = null;
            for (double tick : new double[] {0, 1234, 9876}) {
                var universe = frame.universe(still, model, tick);
                point(model.at(planet, tick).velocity(), universe.velocity());
                vector(new Vector3d(), universe.spin());
                var relative = universe.position().sub(model.at(planet, tick).center());
                if (offset != null) point(offset, relative);
                offset = relative;
            }
        }
    }

    @Test
    void changingReferencePreservesPositionsAndUniverseMotionAtEachEpoch() {
        var model = tilted();
        for (double tick : new double[] {0, 1234, 9876})
            for (int planet = 0; planet < 2; planet++) {
                var old =
                        new Space.Frame(
                                new Quaterniond().rotationXYZ(.4, -.9, 1.2), Space.ORIGIN, planet);
                var next = old.toward(1 - planet, model, tick);
                assertEquals(1 - planet, next.planet());
                aim(old.rotation(), next.rotation());
                for (var universe :
                        new Space.Motion[] {
                            chart(new Point(12, 34, 56)), chart(new Point(-9876, 5432, 1234))
                        }) {
                    var level = old.level(universe, model, tick);
                    point(level.position(), next.level(universe.position(), model, tick));
                    motion(level, next.level(universe, model, tick));
                    motion(universe, next.universe(level, model, tick));
                }
            }
    }

    @Test
    void shiftedMovesOnlyLevelPositions() {
        var model = tilted();
        var delta = new Point(123, -456, 789);
        for (double tick : new double[] {0, 1234, 9876}) {
            var old = new Space.Frame(new Quaterniond().rotationXYZ(.4, -.9, 1.2), Space.ORIGIN, 1);
            var next = old.shifted(delta);
            var universe = chart(new Point(12, 34, 56));
            var a = old.level(universe, model, tick);
            var b = next.level(universe, model, tick);
            point(a.position().add(delta), b.position());
            point(a.velocity(), b.velocity());
            aim(a.orientation(), b.orientation());
            vector(a.spin(), b.spin());
            assertEquals(old.planet(), next.planet());
            motion(universe, next.universe(b, model, tick));
        }
    }

    @Test
    void bodyAnchorIsAChartPointNotFeetAndAgreesWithOut() {
        PlanetShape.assign(1, SHAPES[1]);
        try {
            var model = tilted();
            for (var access : new PlanetAccess[] {PlanetAccess.SHARED})
                for (int id = 0; id < 2; id++)
                    for (var face : Face.values()) {
                        var shape = SHAPES[id];
                        var pose =
                                new PlanetShape.Pose(
                                        face,
                                        new Point(.2 * shape.size(), 2049, .7 * shape.size()));
                        var anchor = access.bodyAnchor(id, pose);
                        point(pose.point(), access.chart(anchor));
                        point(access.feet(id, pose).add(new Point(0, .9, 0)), anchor);
                        var recovered = new PlanetShape.Pose(face, access.chart(anchor));
                        var out =
                                Space.out(
                                        model,
                                        id,
                                        shape,
                                        recovered,
                                        chart(recovered.point()),
                                        1234);
                        point(model.at(id, 1234).world(shape.position(pose)), out.position());
                        var arrival = Space.in(model, id, shape, out, 1234);
                        point(anchor, access.bodyAnchor(id, arrival.pose()));
                    }
        } finally {
            PlanetSystem.reset();
        }
    }

    @Test
    void remoteSquareUsesHalfOpenBoundaries() {
        assertEquals(-16384, Space.X);
        assertEquals(-16384, Space.Z);
        assertEquals(13312, Space.REACH);
        assertEquals(-29696, Space.X - Space.REACH);
        assertEquals(-3072, Space.X + Space.REACH);
        double left = Space.X - Space.REACH, right = Space.X + Space.REACH;
        double near = Space.Z - Space.REACH, far = Space.Z + Space.REACH;
        assertTrue(Space.holds(Space.X, Space.Z));
        assertTrue(Space.holds(left, near));
        assertTrue(Space.holds(Math.nextDown(right), Math.nextDown(far)));
        assertFalse(Space.holds(Math.nextDown(left), near));
        assertFalse(Space.holds(left, Math.nextDown(near)));
        assertFalse(Space.holds(right, near));
        assertFalse(Space.holds(left, far));
        assertFalse(Space.holds(0, 0));
        assertFalse(Space.holds(Double.NaN, near));
        assertFalse(Space.holds(left, Double.POSITIVE_INFINITY));
    }

    @Test
    void noPossibleStorageChartOrHaloMeetsSpace() {
        // Every column/row is nonnegative. The largest face's halo extends one face width below its
        // origin.
        double halo = PlanetShape.MAX;
        assertEquals(-2048, -halo);
        for (int id = 0; id < PlanetStorage.MAX_PLANETS; id++) {
            double lowestX = PlanetStorage.column(id) * (double) PlanetStorage.PLANET_STRIDE - halo;
            double lowestZ = PlanetStorage.row(id) * (double) PlanetStorage.ROW_STRIDE - halo;
            assertTrue(Space.X + Space.REACH < lowestX);
            assertTrue(Space.Z + Space.REACH < lowestZ);
        }
        assertEquals(PlanetStorage.LAYOUT, "six_face_square_v2");
        assertEquals(0, PlanetStorage.find(-halo, -halo));
        for (double x :
                new double[] {
                    Space.X - Space.REACH, Space.X, Math.nextDown((double) Space.X + Space.REACH)
                })
            for (double z :
                    new double[] {
                        Space.Z - Space.REACH,
                        Space.Z,
                        Math.nextDown((double) Space.Z + Space.REACH)
                    }) assertEquals(-1, PlanetStorage.find(x, z));
    }

    @Test
    void allSizesFacesHeightsAndMovingSpinningTicksRoundTrip() {
        for (var model :
                new GroundEphemeris.Model[] {
                    GroundEphemeris.spinning(dev.starfold.planet.PlanetSystem.model(), false, 0),
                    tilted()
                })
            for (int id = 0; id < model.count(); id++)
                for (var shape : SHAPES)
                    for (double tick : new double[] {0, 1234.5, 24000, 98765})
                        for (var face : Face.values())
                            for (double h : new double[] {1536, 1600, 2048, 3000, 4000})
                                for (var p :
                                        new Point[] {
                                            new Point(shape.half(), h, shape.half()),
                                            new Point(.07 * shape.size(), h, .96 * shape.size()),
                                            new Point(shape.size() - .125, h, 17.5)
                                        }) {
                                    var pose = new PlanetShape.Pose(face, p);
                                    var input = chart(p);
                                    var universe = Space.out(model, id, shape, pose, input, tick);
                                    var arrival = Space.in(model, id, shape, universe, tick);
                                    assertEquals(face, arrival.pose().face());
                                    point(pose.point(), arrival.pose().point());
                                    motion(input, arrival.motion());
                                    motion(
                                            universe,
                                            Space.out(
                                                    model,
                                                    id,
                                                    shape,
                                                    arrival.pose(),
                                                    arrival.motion(),
                                                    tick));
                                    // Independent position assertion prevents mutually inverse
                                    // fixed-size functions passing.
                                    point(
                                            model.at(id, tick).world(shape.position(pose)),
                                            universe.position());
                                    assertEquals(
                                            h,
                                            Space.height(
                                                    model, id, shape, universe.position(), tick),
                                            EPS);
                                }
    }

    @Test
    void restingBodyHasTheMovingPointsVelocityAndPlanetSpin() {
        var model = tilted();
        double tick = 5432.5;
        for (int id = 0; id < 2; id++)
            for (var shape : SHAPES)
                for (var face : Face.values()) {
                    var pose =
                            new PlanetShape.Pose(
                                    face, new Point(.3 * shape.size(), 1800, .6 * shape.size()));
                    var body = resting(pose.point());
                    var out = Space.out(model, id, shape, pose, body, tick);
                    var f = model.at(id, tick);
                    var offset = out.position().sub(f.center());
                    point(
                            Space.drift(model, id, tick).add(f.angular().cross(offset)),
                            out.velocity());
                    vector(vector(f.angular()), out.spin());
                    // Centred one-tick difference approximates the instantaneous analytic velocity
                    // to EPS.
                    var before = Space.out(model, id, shape, pose, body, tick - .5).position();
                    var after = Space.out(model, id, shape, pose, body, tick + .5).position();
                    point(after.sub(before), out.velocity());
                    point(
                            model.at(id, tick + .5).center().sub(model.at(id, tick - .5).center()),
                            Space.drift(model, id, tick));
                }
    }

    @Test
    void uprightBodyAndOneBlockPerTickAscentPointRadiallyUp() {
        var model = tilted();
        double tick = 2468;
        for (int id = 0; id < 2; id++)
            for (var shape : SHAPES)
                for (var face : Face.values()) {
                    var pose =
                            new PlanetShape.Pose(
                                    face, new Point(.1 * shape.size(), 1800, .83 * shape.size()));
                    var rest = Space.out(model, id, shape, pose, resting(pose.point()), tick);
                    var rising =
                            Space.out(
                                    model,
                                    id,
                                    shape,
                                    pose,
                                    new Space.Motion(
                                            pose.point(),
                                            new Quaterniond(),
                                            new Point(0, 1, 0),
                                            new Vector3d()),
                                    tick);
                    var up = Space.up(model, id, rest.position(), tick);
                    assertEquals(1, up.length(), EPS);
                    vector(up, vector(rising.velocity().sub(rest.velocity())));
                    vector(up, rest.orientation().transform(new Vector3d(0, 1, 0)));
                    // Horizontal chart units require the Jacobian rather than the orientation
                    // rotation.
                    double dt = .0001;
                    var delta = new Point(dt, 0, 0);
                    var rightPose = new PlanetShape.Pose(face, pose.point().add(delta));
                    var leftPose = new PlanetShape.Pose(face, pose.point().sub(delta));
                    var right =
                            Space.out(
                                    model, id, shape, rightPose, resting(rightPose.point()), tick);
                    var left =
                            Space.out(model, id, shape, leftPose, resting(leftPose.point()), tick);
                    var moving =
                            Space.out(
                                    model,
                                    id,
                                    shape,
                                    pose,
                                    new Space.Motion(
                                            pose.point(),
                                            new Quaterniond(),
                                            new Point(1, 0, 0),
                                            new Vector3d()),
                                    tick);
                    point(
                            right.position().sub(left.position()).scale(1 / (2 * dt)),
                            moving.velocity().sub(rest.velocity()));
                }
    }

    @Test
    void nearestUsesMovingCentresAndHeightUsesTheSuppliedShape() {
        var model = tilted();
        for (double tick : new double[] {0, 1000.5, 30000}) {
            var a = model.at(0, tick).center();
            var b = model.at(1, tick).center();
            var mid = a.add(b).scale(.5);
            assertEquals(0, Space.nearest(model, mid.add(a.sub(b).scale(.001)), tick));
            assertEquals(1, Space.nearest(model, mid.add(b.sub(a).scale(.001)), tick));
            for (int id = 0; id < 2; id++)
                for (var shape : SHAPES) {
                    var f = model.at(id, tick);
                    assertEquals(id, Space.nearest(model, f.center(), tick));
                    assertEquals(
                            -shape.radius(), Space.height(model, id, shape, f.center(), tick), EPS);
                    for (double h : new double[] {-100, 0, 1536, 4000}) {
                        var local = new Point(0, shape.radius() + h, 0);
                        var world = f.world(local);
                        assertEquals(
                                GroundTopology.locate(shape, local).point().y(),
                                Space.height(model, id, shape, world, tick),
                                EPS);
                    }
                }
        }
        // Exact equidistance (identical centres), not a floating-point almost-midpoint.
        var bodies = new java.util.ArrayList<>(model.bodies());
        bodies.add(
                new GroundEphemeris.Body(
                        new GroundEphemeris.Fixed(model.at(0, 0).center()), 0, false, 2));
        var tied = new GroundEphemeris.Model(bodies);
        assertEquals(0, Space.nearest(tied, tied.at(0, 0).center(), 0));
        var single = GroundSolar.defaults();
        assertEquals(
                0, Space.nearest(single, single.at(0, 0).center().add(new Point(100000, 0, 0)), 0));
        assertThrows(IllegalArgumentException.class, () -> Space.drift(single, 1, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> Space.up(model, 0, model.at(0, 0).center(), 0));
    }

    @Test
    void convenienceOverloadsUseInstalledNonstandardShapeAndResetIt() {
        PlanetShape.assign(1, SHAPES[1]);
        try {
            var model = tilted();
            var pose = new PlanetShape.Pose(Face.WEST, new Point(123, 2000, 789));
            var body = chart(pose.point());
            var explicit = Space.out(model, 1, SHAPES[1], pose, body, 1234);
            motion(explicit, Space.out(model, 1, pose, body, 1234));
            var arrival = Space.in(model, 1, explicit, 1234);
            assertEquals(pose.face(), arrival.pose().face());
            motion(body, arrival.motion());
            assertEquals(2000, Space.height(model, 1, explicit.position(), 1234), EPS);
        } finally {
            PlanetSystem.reset();
        }
    }

    @Test
    void canonicalSeamsAndArbitraryUniverseMotionPreserveTheBody() {
        var model = tilted();
        double tick = 6543.25;
        for (var shape : SHAPES)
            for (var face : Face.values()) {
                var pose = new PlanetShape.Pose(face, new Point(0, 1800, .3 * shape.size()));
                var original = Space.out(model, 0, shape, pose, chart(pose.point()), tick);
                var arrival = Space.in(model, 0, shape, original, tick);
                motion(
                        original,
                        Space.out(model, 0, shape, arrival.pose(), arrival.motion(), tick));
            }
        for (int id = 0; id < 2; id++)
            for (var shape : SHAPES) {
                var p = model.at(id, tick).world(new Point(800, 2900, -600));
                var input =
                        new Space.Motion(
                                p,
                                new Quaterniond().rotationXYZ(-.8, .2, 1.1),
                                new Point(-5, 7, 9),
                                new Vector3d(.1, .2, -.3));
                var arrival = Space.in(model, id, shape, input, tick);
                motion(input, Space.out(model, id, shape, arrival.pose(), arrival.motion(), tick));
            }
    }

    @Test
    void ownershipTransitionsAreStrictAndHysteretic() {
        var body = dev.starfold.chart.GroundSolar.defaults().body(0);
        assertEquals(2048, body.hullOut());
        assertEquals(1536, body.hullIn());
        assertTrue(body.hullIn() < body.hullOut());
        assertFalse(Space.leaves(body, 2048));
        assertTrue(Space.leaves(body, Math.nextUp(2048.0)));
        assertFalse(Space.leaves(body, Math.nextDown(2048.0)));
        assertFalse(Space.enters(body, 1536));
        assertTrue(Space.enters(body, Math.nextDown(1536.0)));
        assertFalse(Space.enters(body, Math.nextUp(1536.0)));
        for (double h : new double[] {1536, 1800, 2048}) {
            assertFalse(Space.leaves(body, h));
            assertFalse(Space.enters(body, h));
        }
        assertTrue(Space.enters(body, -100));
        assertTrue(Space.leaves(body, 10000));
    }

    @Test
    void mutableJomlValuesAreDefensivelyCopied() {
        var q = new Quaterniond().rotationX(.4);
        var spin = new Vector3d(1, 2, 3);
        var m = new Space.Motion(ZERO, q, ZERO, spin);
        q.identity();
        spin.zero();
        aim(new Quaterniond().rotationX(.4), m.orientation());
        vector(new Vector3d(1, 2, 3), m.spin());
        m.orientation().identity();
        m.spin().zero();
        aim(new Quaterniond().rotationX(.4), m.orientation());
        vector(new Vector3d(1, 2, 3), m.spin());
    }

    @Test
    void malformedMotionsAndUnavailableChartsAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Space.Motion(
                                new Point(Double.NaN, 0, 0),
                                new Quaterniond(),
                                ZERO,
                                new Vector3d()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Space.Motion(ZERO, new Quaterniond(0, 0, 0, 0), ZERO, new Vector3d()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Space.Motion(
                                ZERO,
                                new Quaterniond(),
                                ZERO,
                                new Vector3d(Double.POSITIVE_INFINITY, 0, 0)));
        var model = tilted();
        var shape = SHAPES[1];
        var pose = new PlanetShape.Pose(Face.TOP, new Point(500, 1800, 500));
        assertThrows(
                IllegalArgumentException.class,
                () -> Space.in(model, 0, shape, chart(model.at(0, 0).center()), 0));
        assertThrows(IllegalArgumentException.class, () -> Space.nearest(model, ZERO, -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> Space.out(model, 2, shape, pose, chart(pose.point()), 0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        Space.out(
                                model,
                                0,
                                shape,
                                pose,
                                chart(pose.point().add(new Point(1, 0, 0))),
                                0));
        var outside = new PlanetShape.Pose(Face.TOP, new Point(1500, 1800, 500));
        assertThrows(
                IllegalArgumentException.class,
                () -> Space.out(model, 0, shape, outside, chart(outside.point()), 0));
    }
}
