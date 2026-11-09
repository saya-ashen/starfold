package dev.starfold.planet;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.GroundOrbit;
import dev.starfold.chart.GroundSpace;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;

/** Frozen full-catalog collision/substep calculation from main 4964c2b7. */
final class GroundDynamicsReference {
    private static boolean clear(
            GroundEphemeris.Model model, GroundDynamics.Terrain terrain, Point world, double time) {
        boolean many = model.count() > 1;
        for (int id = 0; id < model.count(); id++) {
            if (many && model.least(id, world) - PlanetShape.of(id).radius() > GroundOrbit.LIMIT)
                continue;
            var local = model.at(id, time).local(world);
            double height = local.length() - PlanetShape.of(id).radius();
            if (height < GroundSpace.MIN + 1) return false;
            if (height > GroundOrbit.LIMIT) continue;
            if (!terrain.clear(id, GroundTopology.locate(PlanetShape.of(id), local))) return false;
        }
        return true;
    }

    private static int steps(GroundEphemeris.Model model, Point velocity) {
        double motion =
                velocity.length()
                        + model.drift()
                        + model.turn() * (GroundOrbit.RADIUS + GroundOrbit.LIMIT);
        return Math.max(1, (int) Math.ceil(motion / .2));
    }

    static Point sweep(GroundEphemeris.Model model, GroundDynamics.Terrain terrain,
            GroundDynamics.State state, Point velocity) {
        Point p = state.position();
        int count = steps(model, velocity);
        Point part = velocity.scale(1.0 / count);
        for (int i = 0; i < count; i++) {
            Point next = p.add(part);
            double time = state.tick() + (i + 1.0) / count;
            Point world = state.space() ? next : model.at(state.planet(), time).world(next);
            if (!clear(model, terrain, world, time)) break;
            p = next;
        }
        return p;
    }

    private GroundDynamicsReference() {}
}
