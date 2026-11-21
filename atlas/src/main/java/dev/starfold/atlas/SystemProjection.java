package dev.starfold.atlas;

import dev.starfold.api.SystemMapApi.Kind;
import dev.starfold.api.SystemMapApi.Orbit;
import dev.starfold.api.SystemMapApi.Position;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Schematic orbital coordinates; navigation continues to use catalog IDs, never these distances. */
final class SystemProjection {
    private final List<Orbit> orbits;
    private final Map<Integer, Orbit> bodies = new HashMap<>();
    private final Map<Integer, Position> positions = new HashMap<>();
    private final Map<Integer, Double> moonScales = new HashMap<>();
    private final double unit;
    private final Orbit localRoot;

    SystemProjection(List<Orbit> orbits) {
        this(orbits, -1);
    }

    SystemProjection(List<Orbit> orbits, int root) {
        this.orbits = orbits;
        orbits.forEach(o -> bodies.put(o.body(), o));
        localRoot = bodies.get(root);
        if (localRoot != null) {
            unit = orbits.stream().filter(o -> o.parent() == root)
                    .mapToDouble(Orbit::radius).max().orElse(localRoot.bodyRadius());
            return;
        }
        unit =
                orbits.stream()
                        .filter(o -> o.parent() >= 0 && bodies.get(o.parent()).kind() == Kind.STAR)
                        .mapToDouble(Orbit::radius)
                        .min()
                        .orElse(1);
        for (var moon : orbits)
            if (moon.kind() == Kind.MOON)
                moonScales.merge(moon.parent(), moon.radius(), Math::max);
        // Keep each family near its parent even when the schematic scale enlarges the moons.
        moonScales.replaceAll(
                (parent, outerRadius) -> {
                    double parentRadius = Math.log1p(bodies.get(parent).radius() / unit);
                    double outerMoonRadius = 3 * Math.sqrt(outerRadius / unit);
                    return outerMoonRadius == 0
                            ? 1
                            : Math.min(1, parentRadius / (3 * outerMoonRadius));
                });
        orbits.forEach(o -> position(o.body()));
    }

    double bodyScale() {
        return localRoot == null
                ? .12 / orbits.stream().mapToDouble(Orbit::bodyRadius).max().orElse(1)
                : 1 / unit;
    }

    boolean includes(Orbit o) {
        return localRoot == null || o.body() == localRoot.body() || o.parent() == localRoot.body();
    }

    Position position(int id) {
        if (localRoot != null) return position(bodies.get(id).position());
        var cached = positions.get(id);
        if (cached != null) return cached;
        var o = bodies.get(id);
        var p = o.parent() < 0 ? new Position(0, 0, 0) : relative(o.position(), o.parent());
        positions.put(id, p);
        return p;
    }

    Position relative(Position p, int parent) {
        if (localRoot != null) return position(p);
        var o = bodies.get(parent);
        var centre = position(parent);
        var offset = compress(p, o.position(), unit, o.kind() != Kind.STAR);
        double scale = moonScales.getOrDefault(parent, 1.0);
        return new Position(
                centre.x() + offset.x() * scale,
                centre.y() + offset.y() * scale,
                centre.z() + offset.z() * scale);
    }

    /** Radial compression preserves 3D bearings and has an exact inverse. */
    static Position compress(Position p, Position origin, double unit, boolean moon) {
        double dx = p.x() - origin.x(), dy = p.y() - origin.y(), dz = p.z() - origin.z();
        double r = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double radius = moon ? 3 * Math.sqrt(r / unit) : Math.log1p(r / unit);
        double factor = r == 0 ? 0 : radius / r;
        return new Position(dx * factor, dy * factor, dz * factor);
    }

    static Position expand(Position p, Position origin, double unit, boolean moon) {
        double r = Math.sqrt(p.x() * p.x() + p.y() * p.y() + p.z() * p.z());
        double radius = unit * (moon ? Math.pow(r / 3, 2) : Math.expm1(r));
        double factor = r == 0 ? 0 : radius / r;
        return new Position(
                origin.x() + p.x() * factor,
                origin.y() + p.y() * factor,
                origin.z() + p.z() * factor);
    }

    static Position orbit(
            Position parent, Position axis, Position radial, double radius, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        double dot = axis.x() * radial.x() + axis.y() * radial.y() + axis.z() * radial.z();
        return new Position(
                parent.x()
                        + radius
                                * (radial.x() * c
                                        + (axis.y() * radial.z() - axis.z() * radial.y()) * s
                                        + axis.x() * dot * (1 - c)),
                parent.y()
                        + radius
                                * (radial.y() * c
                                        + (axis.z() * radial.x() - axis.x() * radial.z()) * s
                                        + axis.y() * dot * (1 - c)),
                parent.z()
                        + radius
                                * (radial.z() * c
                                        + (axis.x() * radial.y() - axis.y() * radial.x()) * s
                                        + axis.z() * dot * (1 - c)));
    }

    Position position(Position p) {
        if (localRoot != null) {
            var origin = localRoot.position();
            return new Position((p.x() - origin.x()) / unit, (p.y() - origin.y()) / unit,
                    (p.z() - origin.z()) / unit);
        }
        // Inside a satellite envelope use the same parent-local scale as its moons.
        var parent =
                orbits.stream()
                        .filter(o -> o.kind() != Kind.STAR)
                        .filter(
                                o ->
                                        orbits.stream()
                                                .anyMatch(
                                                        m ->
                                                                m.parent() == o.body()
                                                                        && p.distance(o.position())
                                                                                <= m.radius() * 2))
                        .min(java.util.Comparator.comparingDouble(o -> p.distance(o.position())))
                        .orElse(null);
        if (parent != null) return relative(p, parent.body());
        return relative(
                p, orbits.stream().filter(o -> o.parent() < 0).findFirst().orElseThrow().body());
    }
}
