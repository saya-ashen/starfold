package dev.starfold.chart;

import dev.starfold.geometry.BentStripGeometry.Point;

import java.util.ArrayList;
import java.util.List;

/** Physical gas-cloud occluders, in universe or explicitly transformed body-local coordinates. */
public final class GasOcclusion {
    public record Sphere(Point center, double radius) {
        public Sphere {
            GroundEphemeris.point(center);
            if (!Double.isFinite(radius) || radius <= 0 || radius > GasGiant.MAX_RADIUS)
                throw new IllegalArgumentException("Gas occluder radius");
        }
    }

    public static boolean present(GroundEphemeris.Model model) {
        return model.bodies().stream().anyMatch(b -> b.gas().isPresent());
    }

    public static List<Sphere> spheres(GroundEphemeris.Model model, double tick) {
        return relative(model, tick, null, -1);
    }

    /** Omit only a gas body's own shadow; a surface-slot value is never accepted as the omitted identity. */
    public static List<Sphere> relative(
            GroundEphemeris.Model model,
            double tick,
            GroundEphemeris.Frame frame,
            int omitBodyIndex) {
        var result = new ArrayList<Sphere>();
        for (int index : model.catalogIndices()) {
            var body = model.catalogBody(index);
            if (index == omitBodyIndex || body.gas().isEmpty()) continue;
            var center = model.frame(index, tick).center();
            result.add(new Sphere(frame == null ? center : frame.local(center), body.radius()));
        }
        return List.copyOf(result);
    }

    /** Same soft silhouette convention as existing planet light occlusion; finite sun segment only. */
    public static double transmission(List<Sphere> spheres, Point eye, Point sun) {
        GroundEphemeris.point(eye);
        GroundEphemeris.point(sun);
        var delta = sun.sub(eye);
        double length = delta.length();
        if (length < 1e-9) return 1;
        var ray = delta.scale(1 / length);
        double light = 1;
        for (var sphere : spheres) {
            var offset = sphere.center().sub(eye);
            if (offset.length() <= sphere.radius()) return 0;
            double along = offset.dot(ray);
            if (along <= 0 || along >= length) continue;
            light =
                    Math.min(
                            light,
                            GroundGap.smooth(
                                    sphere.radius() * .95,
                                    sphere.radius() * 1.1,
                                    offset.sub(ray.scale(along)).length()));
        }
        return light;
    }

    public static double sunlight(GroundEphemeris.Model model, Point eye, double tick) {
        return transmission(spheres(model, tick), eye, model.star(eye, tick).position());
    }

    /** Scalar float-array spelling is intentional: Minecraft uploads this as glUniform1fv, not vec4. */
    public static float[] packed(List<Sphere> spheres) {
        if (spheres.size() > GasGiant.MAX_COUNT)
            throw new IllegalArgumentException("Gas occluder capacity");
        var data = new float[GasGiant.MAX_COUNT * 4];
        for (int i = 0; i < spheres.size(); i++) {
            var sphere = spheres.get(i);
            data[i * 4] = (float) sphere.center().x();
            data[i * 4 + 1] = (float) sphere.center().y();
            data[i * 4 + 2] = (float) sphere.center().z();
            data[i * 4 + 3] = (float) sphere.radius();
            for (int component = 0; component < 4; component++)
                if (!Float.isFinite(data[i * 4 + component]))
                    throw new IllegalArgumentException("Nonfinite gas shader coordinate");
        }
        return data;
    }

    private GasOcclusion() {}
}
