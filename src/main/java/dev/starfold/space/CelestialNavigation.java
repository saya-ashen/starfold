package dev.starfold.space;

import dev.starfold.chart.CelestialTarget;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Catalog navigation without smuggling a gas body index into any surface-slot API. */
public final class CelestialNavigation {
    public record Candidate(CelestialTarget target, SpaceNavigation.Marker marker, double angle) {}

    public record GasReading(double depth, double pressure, double remaining) {}

    public record Reading(double distance, double closing, Optional<GasReading> gas) {}

    public static boolean knownTarget(GroundEphemeris.Model model, int bodyIndex) {
        if (bodyIndex < 0 || bodyIndex >= model.bodies().size()) return false;
        return true;
    }

    public static List<CelestialTarget> targets(GroundEphemeris.Model model) {
        var targets = new ArrayList<CelestialTarget>();
        for (int index : model.catalogIndices()) {
            var kind = model.catalogBody(index).appearance();
            if (kind == GroundEphemeris.Appearance.SURFACE
                    || kind == GroundEphemeris.Appearance.GAS)
                targets.add(CelestialTarget.resolve(model, index));
        }
        return List.copyOf(targets);
    }

    /** Distance then catalog order, retaining the current surface reference as the final choice. */
    public static List<CelestialTarget> cycle(
            GroundEphemeris.Model model, Point ship, double tick, int referenceSlot) {
        var targets = new ArrayList<>(targets(model));
        targets.sort(
                Comparator.comparingDouble(
                                (CelestialTarget t) ->
                                        t.frame(model, tick).center().sub(ship).length())
                        .thenComparingInt(CelestialTarget::bodyIndex));
        if (referenceSlot >= 0) {
            var reference = CelestialTarget.resolve(model, model.index(referenceSlot));
            if (targets.remove(reference)) targets.add(reference);
        }
        return List.copyOf(targets);
    }

    /** Empty is the explicit clear state after the last target; it is never a sentinel surface slot. */
    public static Optional<CelestialTarget> next(
            GroundEphemeris.Model model,
            Point ship,
            double tick,
            CelestialTarget selected,
            int referenceSlot) {
        var targets = cycle(model, ship, tick, referenceSlot);
        int next = selected == null ? 0 : targets.indexOf(selected) + 1;
        return next < targets.size() ? Optional.of(targets.get(next)) : Optional.empty();
    }

    public static Optional<CelestialTarget> choose(
            List<Candidate> candidates, int width, int height) {
        CelestialTarget best = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (var candidate : candidates) {
            if (!Double.isFinite(candidate.angle())
                    || candidate.angle() < 0
                    || candidate.angle() > SpaceNavigation.LOCK_CONE) continue;
            double distance =
                    Math.hypot(
                            (candidate.marker().x() - .5) * width,
                            (candidate.marker().y() - .5) * height);
            if (distance < nearest) {
                nearest = distance;
                best = candidate.target();
            }
        }
        return Optional.ofNullable(best);
    }

    public static Point velocity(
            GroundEphemeris.Model model, Space.Frame frame, CelestialTarget target, double tick) {
        var velocity =
                target == null
                        ? model.at(frame.planet(), tick).velocity()
                        : target.frame(model, tick).velocity();
        return frame.universeVelocity(GroundEphemeris.ZERO, model, tick).sub(velocity);
    }

    /** Hull padding is supplied by the adapter; zero is an eye/point reading, not whole-hull proof. */
    public static Reading read(
            GroundEphemeris.Model model,
            Space.Frame frame,
            Point position,
            CelestialTarget target,
            double tick,
            double hullPadding) {
        if (!Double.isFinite(hullPadding) || hullPadding < 0)
            throw new IllegalArgumentException("Hull padding");
        var body = target.body(model);
        var placed = target.frame(model, tick);
        var toward = placed.center().sub(position);
        double closing =
                SpaceNavigation.closing(
                        toward,
                        frame.universeVelocity(GroundEphemeris.ZERO, model, tick),
                        placed.velocity());
        if (body.gas().isPresent()) {
            var policy = body.gas().orElseThrow();
            double depth = body.radius() - Math.max(0, toward.length() - hullPadding);
            return new Reading(
                    toward.length() - body.radius(),
                    closing,
                    Optional.of(
                            new GasReading(
                                    depth,
                                    policy.pressure(depth),
                                    Math.max(0, policy.maxDepth() - depth))));
        }
        return new Reading(target.surfaceSlot(model).isPresent()
                ? Space.height(model, target.requireSurfaceSlot(model), position, tick)
                : toward.length() - body.radius(), closing, Optional.empty());
    }

    /** A physical gas envelope can block either kind of selected target; returned identity stays typed. */
    public static Optional<CelestialTarget> blocker(
            GroundEphemeris.Model model, Point ship, CelestialTarget target, double tick) {
        var end = target.frame(model, tick).center();
        CelestialTarget blocker = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (var candidate : targets(model)) {
            if (candidate.equals(target)) continue;
            var body = candidate.body(model);
            var center = candidate.frame(model, tick).center();
            double distance = center.sub(ship).length();
            double radius =
                    body.gas()
                            .map(g -> body.radius() + g.outerHeight())
                            .orElseGet(
                                    () ->
                                            PlanetShape.of(candidate.requireSurfaceSlot(model))
                                                            .radius()
                                                    + body.hullIn());
            if (distance < nearest && SpaceNavigation.occludes(ship, end, center, radius)) {
                blocker = candidate;
                nearest = distance;
            }
        }
        return Optional.ofNullable(blocker);
    }

    private CelestialNavigation() {}
}
