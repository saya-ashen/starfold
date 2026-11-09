package dev.starfold.atlas;

import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Orbit camera in arbitrary world units. Angles are radians, viewport coordinates are GUI pixels.
 * Rendering and picking share the same perspective; focus-relative math retains large-world precision. */
public final class MapCamera {
    public record ScreenPoint(double x, double y, double depth) {}
    public record Ray(Vector3d origin, Vector3d direction) {
        /** Nearest forward intersection, or null on a miss (also works with an origin inside). */
        public Vector3d sphere(Vector3dc centre, double radius) {
            var offset = new Vector3d(origin).sub(centre);
            double b = offset.dot(direction), c = offset.lengthSquared() - radius * radius;
            double discriminant = b * b - c;
            if (discriminant < 0) return null;
            double t = -b - Math.sqrt(discriminant);
            if (t < 0) t = -b + Math.sqrt(discriminant);
            return t < 0 ? null : new Vector3d(direction).mul(t).add(origin);
        }
    }
    public static final double FOV = Math.toRadians(45);
    private final Vector3d focus = new Vector3d();
    private double yaw, pitch, distance = 3.5, desiredDistance = 3.5, yawSpeed, pitchSpeed;
    private double x, y, width = 1, height = 1;

    public void viewport(double x, double y, double width, double height) {
        this.x = x; this.y = y; this.width = width; this.height = height;
    }
    public void focus(Vector3dc point) { focus.set(point); }
    public Vector3d focus() { return new Vector3d(focus); }
    public void pose(double yaw, double pitch, double distance) {
        this.yaw = yaw; this.pitch = pitch;
        this.distance = desiredDistance = distance; stop();
    }
    public void face(Vector3dc outward) {
        yaw = Math.atan2(outward.x(), outward.z());
        pitch = Math.asin(Math.clamp(outward.y() / outward.length(), -1, 1));
        stop();
    }
    public double distance() { return distance; }
    public double desiredDistance() { return desiredDistance; }
    public void distance(double distance) { desiredDistance = distance; }
    public void zoom(double steps, double min, double max) {
        desiredDistance = Math.clamp(desiredDistance * Math.pow(1.2, -steps), min, max);
    }
    public void stop() { yawSpeed = pitchSpeed = 0; }
    public void drag(double dx, double dy) {
        yaw -= dx * .008; pitch = Math.clamp(pitch + dy * .008, -Math.PI / 2, Math.PI / 2);
        yawSpeed = -dx * .008 * 15; pitchSpeed = dy * .008 * 15;
    }
    /** dt in seconds; call once per rendered frame. Dragging suspends inertia, not distance smoothing. */
    public void update(double dt, boolean dragging) {
        if (!dragging) {
            double decay = Math.exp(-7 * dt), integral = (1 - decay) / 7;
            yaw += yawSpeed * integral;
            pitch = Math.clamp(pitch + pitchSpeed * integral, -Math.PI / 2, Math.PI / 2);
            yawSpeed *= decay; pitchSpeed *= decay;
        }
        distance += (desiredDistance - distance) * (1 - Math.exp(-12 * dt));
    }
    private Vector3d outward() { return new Vector3d(Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)); }
    private Vector3d right() { return new Vector3d(Math.cos(yaw), 0, -Math.sin(yaw)); }
    private Vector3d up() { return outward().cross(right()); }
    public Vector3d eye() { return outward().mul(distance).add(focus); }
    public ScreenPoint project(Vector3dc world) {
        var offset = new Vector3d(world).sub(focus);
        var outward = outward(); var right = right();
        double depth = distance - offset.dot(outward);
        double scale = height / (2 * Math.tan(FOV / 2) * depth);
        return new ScreenPoint(x + width / 2 + offset.dot(right) * scale,
                y + height / 2 - offset.dot(outward.cross(right)) * scale, depth);
    }
    public Ray ray(double screenX, double screenY) {
        double scale = 2 * Math.tan(FOV / 2) / height;
        var direction = outward().negate().add(right().mul((screenX - x - width / 2) * scale))
                .add(up().mul((y + height / 2 - screenY) * scale)).normalize();
        return new Ray(eye(), direction);
    }
    public Matrix4f projection(double near, double far) {
        return new Matrix4f().perspective((float) FOV, (float) (width / height), (float) near, (float) far);
    }
    /** Vertex transform for positions relative to focus; subtract focus in double precision first. */
    public Matrix4f view() {
        var eye = outward().mul(distance); var up = up();
        return new Matrix4f().lookAt((float) eye.x, (float) eye.y, (float) eye.z, 0, 0, 0,
                (float) up.x, (float) up.y, (float) up.z);
    }
}
