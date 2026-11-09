package dev.starfold.atlas;

import dev.starfold.api.SystemMapApi.Orbit;
import dev.starfold.api.SystemMapApi.Position;
import dev.starfold.api.SystemMapApi.Kind;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Schematic orbital coordinates; navigation continues to use catalog IDs, never these distances. */
final class SystemProjection {
    private final List<Orbit> orbits;
    private final Map<Integer, Orbit> bodies = new HashMap<>();
    private final Map<Integer, Position> positions = new HashMap<>();
    private final double unit;
    SystemProjection(List<Orbit> orbits) {
        this.orbits = orbits;
        orbits.forEach(o -> bodies.put(o.body(), o));
        unit = orbits.stream().filter(o -> o.parent() >= 0 && bodies.get(o.parent()).kind() == Kind.STAR)
                .mapToDouble(Orbit::radius).min().orElse(1);
        orbits.forEach(o -> position(o.body()));
    }
    Position position(int id) {
        var cached = positions.get(id);
        if (cached != null) return cached;
        var o = bodies.get(id);
        var p = o.parent() < 0 ? new Position(0, 0, 0) : relative(o.position(), o.parent());
        positions.put(id, p);
        return p;
    }
    Position relative(Position p, int parent) {
        var o = bodies.get(parent);
        var centre = position(parent);
        var offset = compress(p, o.position(), unit, o.kind() != Kind.STAR);
        return new Position(centre.x()+offset.x(), centre.y()+offset.y(), centre.z()+offset.z());
    }
    /** Radial compression preserves 3D bearings and has an exact inverse. */
    static Position compress(Position p, Position origin, double unit, boolean moon) {
        double dx = p.x()-origin.x(), dy = p.y()-origin.y(), dz = p.z()-origin.z();
        double r = Math.sqrt(dx*dx + dy*dy + dz*dz);
        double radius = moon ? 3*Math.sqrt(r/unit) : Math.log1p(r/unit);
        double factor = r == 0 ? 0 : radius/r;
        return new Position(dx*factor, dy*factor, dz*factor);
    }
    static Position expand(Position p, Position origin, double unit, boolean moon) {
        double r = Math.sqrt(p.x()*p.x() + p.y()*p.y() + p.z()*p.z());
        double radius = unit*(moon ? Math.pow(r/3, 2) : Math.expm1(r));
        double factor = r == 0 ? 0 : radius/r;
        return new Position(origin.x()+p.x()*factor, origin.y()+p.y()*factor, origin.z()+p.z()*factor);
    }
    static Position orbit(Position parent, Position axis, Position radial, double radius, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        double dot = axis.x() * radial.x() + axis.y() * radial.y() + axis.z() * radial.z();
        return new Position(parent.x() + radius * (radial.x() * c + (axis.y()*radial.z()-axis.z()*radial.y())*s + axis.x()*dot*(1-c)),
                parent.y() + radius * (radial.y() * c + (axis.z()*radial.x()-axis.x()*radial.z())*s + axis.y()*dot*(1-c)),
                parent.z() + radius * (radial.z() * c + (axis.x()*radial.y()-axis.y()*radial.x())*s + axis.z()*dot*(1-c)));
    }
    Position position(Position p) {
        // Inside a satellite envelope use the same parent-local scale as its moons.
        var parent = orbits.stream().filter(o -> o.kind() != Kind.STAR)
                .filter(o -> orbits.stream().anyMatch(m -> m.parent() == o.body()
                        && p.distance(o.position()) <= m.radius()*2))
                .min(java.util.Comparator.comparingDouble(o -> p.distance(o.position()))).orElse(null);
        if (parent != null) return relative(p, parent.body());
        return relative(p, orbits.stream().filter(o -> o.parent() < 0).findFirst().orElseThrow().body());
    }
}
