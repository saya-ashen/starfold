package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundEphemeris.*;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import org.joml.Quaterniond;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

class ShipParkingTest {
    static final Point X = new Point(1, 0, 0), Y = GroundEphemeris.Y;
    static final Point ANCHOR = new Point(20_481_031, 27, 20_481_081);

    @BeforeEach
    void shapes() {
        PlanetShape.reset();
    }

    @AfterEach
    void reset() {
        PlanetShape.reset();
    }

    static Model model() {
        var spin = new Spin(true, Y, new Quaterniond().rotationX(.4), .2, 48000, 91);
        return new Model(
                List.of(
                        new Body(new Fixed(new Point(10, 20, 30)), 512, true, -1),
                        new Body(new Orbit(0, Y, X, 20000, 120000, .3, 11, spin), 0, false, 0),
                        new Body(new Fixed(new Point(-80000, 0, 0)), 256, true, -1),
                        new Body(
                                new Orbit(
                                        1,
                                        X,
                                        Y,
                                        5000,
                                        24000,
                                        .7,
                                        57,
                                        new Spin(
                                                true,
                                                X,
                                                new Quaterniond().rotationZ(.6),
                                                .4,
                                                72000,
                                                77)),
                                0,
                                false,
                                1)));
    }

    static void close(Point expected, Point actual, double tolerance) {
        assertTrue(expected.sub(actual).length() <= tolerance, expected + " != " + actual);
    }

    static void same(Quaterniond expected, Quaterniond actual) {
        assertEquals(1, Math.abs(expected.dot(actual)), 1e-12);
    }

    @Test
    void fixedLightSpinningPlanetAndMoonRoundTripAndFollowTheirOwnFrame() {
        var model = model();
        var local = new Point(1800, 210, -350);
        var orientation = new Quaterniond().rotationXYZ(.2, -.4, .7);
        for (long captured : new long[] {1234, GroundEphemeris.MAX_TICK - 10000}) {
            for (int body : new int[] {0, 1, 3}) {
                var frame = model.frame(body, captured);
                var position = frame.world(local);
                var place =
                        ShipParking.capture(model, body, ANCHOR, position, orientation, captured);
                close(local, place.position(), 1e-10);
                assertEquals(ANCHOR, place.anchorPlot());
                assertEquals(captured, place.tick());
                var back = ShipParking.evaluate(model, place, captured);
                close(position, back.position(), 1e-10);
                same(orientation, back.orientation());
                for (long later : new long[] {captured, captured + 100, captured + 9999}) {
                    var next = ShipParking.evaluate(model, place, later);
                    var bodyFrame = model.frame(body, later);
                    close(local, bodyFrame.local(next.position()), 1e-10);
                    assertEquals(
                            local.length(),
                            next.position().sub(bodyFrame.center()).length(),
                            1e-10);
                    same(
                            place.attitude(),
                            bodyFrame.rotation().conjugate().mul(next.orientation()));
                    close(
                            bodyFrame.angular(),
                            new Point(next.spin().x, next.spin().y, next.spin().z),
                            1e-12);
                    var before = ShipParking.evaluate(model, place, later - 1);
                    var after = ShipParking.evaluate(model, place, later + 1);
                    var difference = after.position().sub(before.position()).scale(.5);
                    close(
                            next.velocity(),
                            difference,
                            Math.max(1e-10, next.velocity().length() * 1e-6));
                }
            }
        }
    }

    @Test
    void shiftedAndTurnedLevelFramesPreserveTheSameUniversePlaceAndMotion() {
        var model = model();
        long tick = 9876;
        var frame =
                new Space.Frame(
                        new Quaterniond().rotationXYZ(.1, .2, .3),
                        Space.ORIGIN,
                        0,
                        new Point(100, 200, 300),
                        new Point(3, 2, 1),
                        8000);
        var position = model.frame(3, tick).world(new Point(1700, 40, 20));
        var attitude = new Quaterniond().rotationXYZ(.7, .9, -.2);
        var place =
                ShipParking.fromLevel(
                        model,
                        frame,
                        3,
                        ANCHOR,
                        frame.level(position, model, tick),
                        frame.levelOrientation(attitude),
                        tick);
        var changed =
                frame.shifted(new Point(1024, -700, 90))
                        .turned(new Quaterniond().rotationY(.8), Space.ORIGIN)
                        .toward(1, model, tick);
        var level = ShipParking.toLevel(model, changed, place, tick);
        var back = changed.universe(level, model, tick);
        close(position, back.position(), 1e-9);
        same(attitude, back.orientation());
        var evaluated = ShipParking.evaluate(model, place, tick);
        close(evaluated.velocity(), back.velocity(), 1e-12);
        assertTrue(evaluated.spin().distance(back.spin()) < 1e-12);
        var recaptured =
                ShipParking.fromLevel(
                        model, changed, 3, ANCHOR, level.position(), level.orientation(), tick);
        close(place.position(), recaptured.position(), 1e-9);
        same(place.attitude(), recaptured.attitude());
    }

    @Test
    void nearestUsesSurfaceGapAllBodiesAndListOrderNotSlots() {
        var model = model();
        assertEquals(0, ShipParking.nearestBody(model, new Point(10, 20, 550), 100));
        assertEquals(
                3,
                ShipParking.nearestBody(
                        model, model.frame(3, 100).world(new Point(1400, 0, 0)), 100));
        var tie =
                new Model(
                        List.of(
                                new Body(new Fixed(new Point(-1000, 0, 0)), 512, true, -1),
                                new Body(new Fixed(new Point(1000, 0, 0)), 512, true, -1)));
        assertEquals(0, ShipParking.nearestBody(tie, GroundEphemeris.ZERO, 0));
        var radii =
                new Model(
                        List.of(
                                new Body(new Fixed(GroundEphemeris.ZERO), 32, true, -1),
                                new Body(new Fixed(new Point(2000, 0, 0)), 0, false, 0)));
        // The sun is closer by centre, but the planet is closer by nominal surface.
        assertEquals(1, ShipParking.nearestBody(radii, new Point(600, 0, 0), 0));
        PlanetShape.assign(0, new PlanetShape(512));
        assertEquals(0, ShipParking.nearestBody(radii, new Point(600, 0, 0), 0));
    }

    @Test
    void quaternionsAreCopiedAndInvalidValuesRefused() {
        var q = new Quaterniond().rotationY(.4);
        var place = ShipParking.capture(model(), 0, ANCHOR, new Point(1, 2, 3), q, 12);
        var expected = place.attitude();
        q.identity();
        place.attitude().identity();
        same(expected, place.attitude());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ShipRecord.Place(
                                0, ANCHOR, new Point(Double.NaN, 0, 0), new Quaterniond(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> ShipParking.evaluate(model(), place, GroundEphemeris.MAX_TICK + 1d));
    }
}
