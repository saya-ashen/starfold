package dev.starfold.space;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;
import org.joml.Quaterniond;

/** Pure region flight calculations; velocities remain blocks per tick. */
public final class SpaceFlightMath {
    /** Cruise has no down; near a planet the deck must be ready before hand-over. Degrees per second. */
    static final double PILOT_TURN = 45,
            MOUSE_TURN = 180,
            ALIGN_TURN = 30,
            ALIGN_TIME = 12,
            ASSIST = 1.5;

    static final double ARRIVE = 150,
            ARRIVE_TIME = 6,
            NEAR_TIME = 3;
    static final double MISS_IN = 500, MISS_OUT = 1000;
    /** Closing is radial blocks/second; equality leaves attitude free. The floor also covers retreat.
     * 12v lead leaves about 7v after the 4s notice + 1s blend, before the 6(v-150)
     * governor boundary. Arrival directly projects velocity onto its envelope (not BRAKE-limited),
     * so the existing linear lead remains sufficient at 2400; no quadratic lead is needed. */
    static boolean align(GroundEphemeris.Body body, double height, double closing) {
        return align(body, height, closing, true);
    }

    static boolean align(
            GroundEphemeris.Body body, double height, double closing, boolean inbound) {
        return height < body.hullIn() + 300
                || inbound && closing > 0 && height - body.hullIn() < closing * ALIGN_TIME;
    }

    /** Straight-line closest approach in the nearest planet's velocity frame; recession has no future miss. */
    static double missHeight(Point position, Point relative, double radius) {
        double speed2 = relative.dot(relative), dot = position.dot(relative);
        return speed2 > 0 && dot < 0
                ? position.add(relative.scale(-dot / speed2)).length() - radius
                : Double.POSITIVE_INFINITY;
    }

    static boolean inbound(
            GroundEphemeris.Body body, boolean previous, double closing, double miss) {
        return closing > 0
                && (previous ? miss <= body.hullIn() + MISS_OUT : miss < body.hullIn() + MISS_IN);
    }

    static boolean governed(GroundEphemeris.Body body, double height, boolean inbound) {
        return inbound || height < body.hullIn() + 300;
    }

    static Point govern(
            GroundEphemeris.Body body,
            Point drift,
            Point toward,
            Point relative,
            double height,
            boolean inbound) {
        if (!governed(body, height, inbound)) return drift;
        var arrived = arrival(body, drift, toward, relative, height);
        return near(body, arrived, toward, relative.add(arrived.sub(drift)), height);
    }

    /** Inverse ship rotation: nose up brings old +Y ahead; roll right brings old right upward. */
    static Quaterniond attitude(
            Space.Frame frame,
            GroundEphemeris.Model model,
            double tick,
            Point pivot,
            double height,
            double closing,
            boolean steadied,
            double yaw,
            double pitch,
            double roll) {
        return attitude(
                frame, model, tick, pivot, height, closing, steadied, yaw, pitch, roll, 0, 0);
    }

    static Quaterniond attitude(
            Space.Frame frame,
            GroundEphemeris.Model model,
            double tick,
            Point pivot,
            double height,
            double closing,
            boolean steadied,
            double yaw,
            double pitch,
            double roll,
            double mouseYaw,
            double mousePitch) {
        return attitude(
                frame,
                model,
                tick,
                pivot,
                align(
                        model.body(Space.nearest(model, frame.universe(pivot, model, tick), tick)),
                        height,
                        closing),
                steadied,
                yaw,
                pitch,
                roll,
                mouseYaw,
                mousePitch);
    }

    public static Quaterniond attitude(
            Space.Frame frame,
            GroundEphemeris.Model model,
            double tick,
            Point pivot,
            boolean arriving,
            boolean steadied,
            double yaw,
            double pitch,
            double roll,
            double mouseYaw,
            double mousePitch) {
        if (!steadied) return new Quaterniond();
        if (arriving)
            return Space.Frame.turn(frame, model, tick, pivot, Math.toRadians(ALIGN_TURN) / 20);
        if (!Double.isFinite(yaw)
                || !Double.isFinite(pitch)
                || !Double.isFinite(roll)
                || !Double.isFinite(mouseYaw)
                || !Double.isFinite(mousePitch)) return new Quaterniond();
        double a = Math.toRadians(yaw),
                p = Math.clamp(pitch, -1, 1),
                r = Math.clamp(roll, -1, 1),
                length = Math.max(1, Math.hypot(p, r));
        p *= PILOT_TURN / 20 / length;
        r *= PILOT_TURN / 20 / length;
        // Mouse pitch is vanilla's down-positive angle. R maps universe to Level: inverse nose yaw
        // is +Y.
        double scale =
                Math.min(1, (MOUSE_TURN / 20) / Math.max(1e-300, Math.hypot(mouseYaw, mousePitch)));
        p -= mousePitch * scale;
        double x = Math.cos(a) * p + Math.sin(a) * r,
                y = mouseYaw * scale,
                z = Math.sin(a) * p - Math.cos(a) * r;
        length = Math.sqrt(x * x + y * y + z * z);
        return length == 0
                ? new Quaterniond()
                : new Quaterniond()
                        .rotationAxis(Math.toRadians(length), x / length, y / length, z / length);
    }

    /** Production axes are the controller bow and hull up carried by the body's pose, never pilot look. */
    public static Quaterniond attitude(
            Space.Frame frame,
            GroundEphemeris.Model model,
            double tick,
            Point pivot,
            boolean arriving,
            boolean steadied,
            Point forward,
            Point up,
            double pitch,
            double roll,
            double mouseYaw,
            double mousePitch) {
        if (!steadied) return new Quaterniond();
        if (arriving)
            return Space.Frame.turn(frame, model, tick, pivot, Math.toRadians(ALIGN_TURN) / 20);
        if (!Double.isFinite(pitch)
                || !Double.isFinite(roll)
                || !Double.isFinite(mouseYaw)
                || !Double.isFinite(mousePitch)) return new Quaterniond();
        double p = Math.clamp(pitch, -1, 1),
                r = Math.clamp(roll, -1, 1),
                length = Math.max(1, Math.hypot(p, r));
        p *= PILOT_TURN / 20 / length;
        r *= PILOT_TURN / 20 / length;
        double scale =
                Math.min(1, (MOUSE_TURN / 20) / Math.max(1e-300, Math.hypot(mouseYaw, mousePitch)));
        p -= mousePitch * scale;
        var f = new org.joml.Vector3d(forward.x(), forward.y(), forward.z());
        var u = new org.joml.Vector3d(up.x(), up.y(), up.z());
        var axis = new org.joml.Vector3d(f).cross(u).mul(-p).fma(-r, f).fma(mouseYaw * scale, u);
        length = axis.length();
        return length == 0
                ? new Quaterniond()
                : new Quaterniond()
                        .rotationAxis(
                                Math.toRadians(length),
                                axis.x / length,
                                axis.y / length,
                                axis.z / length);
    }

    /** Only forward thrust steers drift onto the unit nose. Keep speed unless flying sideways or backward. */
    static Point assist(Point drift, Point nose, boolean forward) {
        if (!forward) return drift;
        double along = drift.dot(nose);
        var parallel = nose.scale(along);
        var turned = parallel.add(drift.sub(parallel).scale(1 / (1 + ASSIST / 20)));
        return along > 0 ? turned.scale(drift.length() / turned.length()) : turned;
    }

    /** Assist and cruise use the lock's centre velocity offset; arrival stays nearest-relative. */
    static Point assist(Point drift, Point nose, boolean forward, Point targetVelocity) {
        if (!forward) return drift;
        if (targetVelocity == null || targetVelocity.length() == 0)
            return assist(drift, nose, true);
        return assist(drift.sub(targetVelocity), nose, true).add(targetVelocity);
    }

    /** Arrival keeps only signed radial thrust; outside it return the exact original vector. */
    public static Point blendPush(Point impulse, Point toward, double blend) {
        return impulse.add(arrivalPush(impulse, toward, true).sub(impulse).scale(blend));
    }

    public static Point arrivalPush(Point impulse, Point toward, boolean arriving) {
        if (!arriving) return impulse;
        double length = toward.length();
        if (length == 0) return GroundEphemeris.ZERO;
        var radial = toward.scale(1 / length);
        return radial.scale(impulse.dot(radial));
    }

    /** Velocities are blocks/tick; folding adds only the impulse actually produced. */
    static Point gain(Point drift, Point impulse) {
        return drift.add(impulse);
    }

    /** Opposing thrust requests a paid stop in the selected reference frame. */
    static Point brake(Point before, Point drift, Point push, Point targetVelocity) {
        var reference = targetVelocity == null ? GroundEphemeris.ZERO : targetVelocity;
        var relative = before.sub(reference);
        double speed = relative.length();
        return speed > 1.0 / 20 && push.dot(relative.scale(1 / speed)) < -.5
                ? reference : drift;
    }

    /** Only nearest-planet closing is governed; reference velocity and tangential drift are untouched.
     * This is a kinematic safety cap, not the assisted manual brake. Tracking it requires v/6 b/s².
     * At a discrete crossing the prior cap is at most ARRIVE/(1-1/(20*ARRIVE_TIME)). */
    // Temporary atmosphere/gravity capture: arrival/near speed reduction consumes no fuel.
    static double arrivalLimit(GroundEphemeris.Body body, double height) {
        return ARRIVE + Math.max(0, height - body.hullIn()) / ARRIVE_TIME;
    }

    static double nearLimit(GroundEphemeris.Body body, double height) {
        return ARRIVE + Math.max(0, height - body.hullIn()) / NEAR_TIME;
    }

    static Point arrival(
            GroundEphemeris.Body body, Point drift, Point toward, Point relative, double height) {
        double length = toward.length();
        if (length == 0) return drift;
        var radial = toward.scale(1 / length);
        double closing = relative.dot(radial), limit = arrivalLimit(body, height) / 20;
        return closing > limit ? drift.sub(radial.scale(closing - limit)) : drift;
    }

    /** After radial governing, spend the whole-speed budget on radial motion first; trim only tangent. */
    static Point near(
            GroundEphemeris.Body body, Point drift, Point toward, Point relative, double height) {
        double length = toward.length();
        if (length == 0) return drift;
        var radial = toward.scale(1 / length);
        double along = relative.dot(radial), limit = nearLimit(body, height) / 20;
        var tangent = relative.sub(radial.scale(along));
        double speed = tangent.length(),
                allowed = Math.sqrt(Math.max(0, limit * limit - along * along));
        return speed > allowed ? drift.add(tangent.scale(allowed / speed).sub(tangent)) : drift;
    }

    static boolean nearer(
            GroundEphemeris.Model model, Point universe, int current, int nearest, double tick) {
        return nearest != current
                && universe.sub(model.at(current, tick).center()).length()
                                - universe.sub(model.at(nearest, tick).center()).length()
                        > Space.HYSTERESIS;
    }

    public static Point anchor(Point point) {
        var delta = Space.ORIGIN.sub(point);
        return Math.abs(delta.x()) > Space.ANCHOR_REACH
                        || Math.abs(delta.y()) > Space.ANCHOR_REACH
                        || Math.abs(delta.z()) > Space.ANCHOR_REACH
                ? delta
                : null;
    }

    private SpaceFlightMath() {}
}
