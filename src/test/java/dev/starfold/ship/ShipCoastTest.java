package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ShipCoastTest {
    static final Point ZERO = GroundEphemeris.ZERO;
    static final double FACTOR = 1 / (1 + .09 / 20);

    @AfterEach
    void reset() {
        dev.starfold.chart.PlanetShape.reset();
    }

    @Test
    void eitherResidualAboveThresholdDefersCaptureEvenAtHighUniverseSpeed() {
        var target =
                new Space.Motion(
                        ZERO,
                        new Quaterniond(),
                        new Point(100, 200, 300),
                        new Vector3d(.1, .2, .3));
        assertTrue(ShipParking.slow(target, target));
        assertFalse(ShipParking.slow(offset(target, ShipParking.CAPTURE_SPEED * 1.001, 0), target));
        assertFalse(ShipParking.slow(offset(target, 0, ShipParking.CAPTURE_SPIN * 1.001), target));
        assertTrue(
                ShipParking.slow(
                        offset(
                                target,
                                ShipParking.CAPTURE_SPEED * .999,
                                ShipParking.CAPTURE_SPIN * .999),
                        target));
    }

    private static Space.Motion offset(Space.Motion m, double speed, double spin) {
        return new Space.Motion(
                m.position(),
                m.orientation(),
                m.velocity().add(new Point(speed, 0, 0)),
                m.spin().add(spin, 0, 0));
    }

    @Test
    void captureOnceThenUnsaturatedHoldHasOnlyTinyExcursion() {
        var model = ShipParkingTest.model();
        var target = ShipParking.coastTarget(model, 0, ZERO, 100);
        var motion = offset(target, ShipParking.CAPTURE_SPEED, ShipParking.CAPTURE_SPIN);
        var observer = new ShipObserver(ShipSave.fresh(false));
        var id = UUID.randomUUID();
        var captures = new AtomicInteger();
        var place = ShipParking.capture(model, 0, ZERO, ZERO, new Quaterniond(), 100);
        for (int n = 0; n < 1201; n++) {
            var current = motion;
            assertSame(
                    place,
                    observer.bind(
                            id,
                            false,
                            () -> {
                                assertTrue(ShipParking.slow(current, target));
                                captures.incrementAndGet();
                                return place;
                            }));
            var next = ShipParking.settle(motion, target, target);
            assertTrue(next.velocity().length() < ShipParking.MAX_SPEED);
            assertTrue(next.spin().length() < ShipParking.MAX_SPIN);
            motion =
                    new Space.Motion(
                            motion.position().add(next.velocity()),
                            ShipHoldTest.integrate(motion.orientation(), next.spin()),
                            next.velocity(),
                            next.spin());
            assertTrue(motion.position().length() < .02);
            assertTrue(ShipHoldTest.degrees(new Quaterniond(), motion.orientation()) < .001);
            if (n >= 100) {
                assertTrue(motion.position().length() < .000001);
                assertTrue(ShipHoldTest.degrees(new Quaterniond(), motion.orientation()) < .000001);
            }
        }
        assertEquals(1, captures.get());
    }

    @Test
    void coastRefreshAndFinalSerializationSaveCurrentPoseThenWakeUsesItImmediately() {
        var model = ShipParkingTest.model();
        var saved = ShipSave.fresh(false);
        var observer = new ShipObserver(saved);
        var id = UUID.randomUUID();
        var current = new AtomicReference<>(sample(model, 0));
        observer.added(id, current::get);
        for (int tick = 1; tick <= 100; tick++) {
            assertNull(observer.bind(id, false, () -> null));
            current.set(sample(model, tick));
            if (tick % 20 == 0) {
                observer.refresh();
                assertEquals(current.get().state(), saved.find(id).state());
                assertNull(observer.hold(id));
            }
        }
        var last = sample(model, 101);
        observer.serialized(id, last);
        observer.removed(id, false);
        assertEquals(last.state(), saved.find(id).state());
        var place = ((ShipRecord.InSpace) last.state()).place();
        observer.added(
                id,
                () -> {
                    assertSame(place, observer.hold(id));
                    return last;
                });
        assertSame(
                place,
                observer.bind(
                        id,
                        false,
                        () -> {
                            throw new AssertionError("Wake must not coast");
                        }));
        observer.bind(id, true, null); // A large displacement uses the same invalidation.
        assertNull(observer.bind(id, false, () -> null));
        assertNull(observer.hold(id));
        assertSame(place, observer.bind(id, false, () -> place));
    }

    private static ShipObserver.Sample sample(GroundEphemeris.Model model, long tick) {
        return new ShipObserver.Sample(
                ShipSaveTest.DIMENSION,
                Optional.empty(),
                new ShipRecord.InSpace(
                        ShipParking.capture(
                                model,
                                0,
                                ZERO,
                                new Point(tick * 4, 0, 0),
                                new Quaterniond(),
                                tick)));
    }

    @Test
    void leadAndFollowerCoastMatchHistoricalDragEveryStepUntilFirstSlowTick() {
        var model = ShipParkingTest.model();
        for (int body : new int[] {0, 1, 3}) {
            for (boolean lead : new boolean[] {false, true}) compare(model, body, lead);
        }
    }

    private static void compare(GroundEphemeris.Model model, int body, boolean lead) {
        long start = 1234;
        var position = model.frame(body, start).world(new Point(1800, 210, -350));
        var target = historicalTarget(model, body, position, start);
        var frame =
                new Space.Frame(
                        new Quaterniond(),
                        Space.ORIGIN,
                        0,
                        position.sub(model.at(0, start).center()),
                        target.velocity().add(new Point(4, 0, 0)),
                        start);
        var motion =
                new Space.Motion(Space.ORIGIN, new Quaterniond(), ZERO, new Vector3d(.0001, 0, 0));
        var oldFrame = frame;
        var oldMotion = motion;
        Point turn = ZERO, oldTurn = ZERO;
        var observer = new ShipObserver(ShipSave.fresh(false));
        var id = UUID.randomUUID();
        boolean captured = false;
        for (long tick = start; tick < start + 3000; tick++) {
            var universe = frame.universe(motion, model, tick);
            universe =
                    new Space.Motion(
                            universe.position(),
                            universe.orientation(),
                            universe.velocity(),
                            universe.spin().add(turn.x(), turn.y(), turn.z()));
            target = historicalTarget(model, body, universe.position(), tick);
            boolean slow = ShipParking.slow(universe, target);
            var candidate =
                    ShipParking.capture(
                            model, body, ZERO, universe.position(), universe.orientation(), tick);
            var held = observer.bind(id, false, () -> slow ? candidate : null);
            if (slow) {
                assertSame(candidate, held);
                assertTrue(tick > start + 1000, "Not an instant stop");
                captured = true;
                break;
            }
            assertNull(held);
            if (lead) {
                frame = frame.folded(motion.velocity(), tick);
                oldFrame = oldFrame.folded(oldMotion.velocity(), tick);
                var expectedTarget =
                        historicalTarget(
                                model,
                                body,
                                oldFrame.universe(motion.position(), model, tick),
                                tick);
                frame = ShipParking.coastFrame(frame, model, body, motion.position(), FACTOR, tick);
                oldFrame = historicalFrame(oldFrame, model, body, motion.position(), tick);
                assertEquals(oldFrame, frame);
                if (target.spin().lengthSquared() != 0) {
                    var spin = new Point(target.spin().x, target.spin().y, target.spin().z);
                    var impulse =
                            frame.universeVector(
                                    new Point(motion.spin().x, motion.spin().y, motion.spin().z));
                    var next =
                            ShipParking.nextCoastTarget(
                                    model, body, frame, motion.position(), ZERO, tick);
                    var oldNext =
                            historicalTarget(
                                    model,
                                    body,
                                    oldFrame.universe(motion.position(), model, tick + 1),
                                    tick + 1);
                    turn =
                            ShipParking.decay(
                                    turn.add(impulse),
                                    spin,
                                    new Point(next.spin().x, next.spin().y, next.spin().z),
                                    FACTOR);
                    oldTurn =
                            spin.add(oldTurn.add(impulse).sub(spin).scale(FACTOR))
                                    .add(
                                            new Point(
                                                            oldNext.spin().x,
                                                            oldNext.spin().y,
                                                            oldNext.spin().z)
                                                    .sub(spin));
                    assertEquals(oldTurn, turn);
                    frame = ShipParking.turnFrame(frame, turn, motion.position());
                    oldFrame = ShipParking.turnFrame(oldFrame, oldTurn, motion.position());
                    motion =
                            new Space.Motion(
                                    motion.position(), motion.orientation(), ZERO, new Vector3d());
                } else
                    motion =
                            new Space.Motion(
                                    motion.position(),
                                    motion.orientation(),
                                    ZERO,
                                    motion.spin().mul(FACTOR));
                oldMotion = motion;
            } else {
                var localTarget = frame.level(target, model, tick);
                var next = localTarget;
                if (target.spin().lengthSquared() != 0) {
                    var damped =
                            localTarget
                                    .velocity()
                                    .add(
                                            motion.velocity()
                                                    .sub(localTarget.velocity())
                                                    .scale(FACTOR));
                    next =
                            frame.level(
                                    historicalTarget(
                                            model,
                                            body,
                                            frame.universe(
                                                    motion.position().add(damped), model, tick + 1),
                                            tick + 1),
                                    model,
                                    tick + 1);
                }
                motion = ShipParking.coast(motion, localTarget, next, FACTOR);
                oldMotion =
                        new Space.Motion(
                                oldMotion.position(),
                                oldMotion.orientation(),
                                historicalDecay(oldMotion.velocity(), localTarget.velocity())
                                        .add(next.velocity().sub(localTarget.velocity())),
                                next.spin()
                                        .add(oldMotion.spin().sub(localTarget.spin()).mul(FACTOR)));
                assertEquals(oldMotion, motion);
            }
            motion =
                    new Space.Motion(
                            motion.position().add(motion.velocity()),
                            ShipHoldTest.integrate(motion.orientation(), motion.spin()),
                            motion.velocity(),
                            motion.spin());
            oldMotion =
                    new Space.Motion(
                            oldMotion.position().add(oldMotion.velocity()),
                            ShipHoldTest.integrate(oldMotion.orientation(), oldMotion.spin()),
                            oldMotion.velocity(),
                            oldMotion.spin());
            assertEquals(oldMotion, motion);
            assertEquals(oldFrame, frame);
        }
        assertTrue(captured, "body=" + body + " lead=" + lead);
    }

    // Frozen pre-S6 arithmetic (d1a9f3c parent), independent of the production coast helpers.
    private static Space.Motion historicalTarget(
            GroundEphemeris.Model model, int body, Point position, double tick) {
        var f = model.frame(body, tick);
        var w = f.angular();
        return new Space.Motion(
                position,
                new Quaterniond(),
                f.worldVelocity(f.local(position), ZERO),
                new Vector3d(w.x(), w.y(), w.z()));
    }

    private static Point historicalDecay(Point current, Point target) {
        return target.equals(ZERO)
                ? current.scale(FACTOR)
                : target.add(current.sub(target).scale(FACTOR));
    }

    private static Space.Frame historicalFrame(
            Space.Frame frame, GroundEphemeris.Model model, int body, Point point, long tick) {
        var target = historicalTarget(model, body, frame.universe(point, model, tick), tick);
        var drift = target.velocity().sub(model.at(frame.planet(), tick).velocity());
        var damped = historicalDecay(frame.drift(), drift);
        if (target.spin().lengthSquared() != 0) {
            var prediction =
                    new Space.Frame(
                            frame.rotation(),
                            frame.shift(),
                            frame.planet(),
                            frame.rebased(tick).offset(),
                            damped,
                            tick);
            var next =
                    historicalTarget(
                            model, body, prediction.universe(point, model, tick + 1), tick + 1);
            damped =
                    damped.add(
                            next.velocity()
                                    .sub(model.at(frame.planet(), tick + 1).velocity())
                                    .sub(drift));
        } else if (drift.equals(ZERO)) return frame.dragged(FACTOR, tick);
        var f = frame.rebased(tick);
        return new Space.Frame(f.rotation(), f.shift(), f.planet(), f.offset(), damped, tick);
    }
}
