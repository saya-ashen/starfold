package dev.starfold.space;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetAccess;
import dev.starfold.planet.GroundTopology;
import dev.starfold.ship.ShipRecord;
import dev.starfold.ship.ShipParking;

/** Catalog roots and recorded hull positions, independent of Minecraft client state. */
public final class MapNavigation {
    public static int system(GroundEphemeris.Model model, int body) {
        while (model.catalogBody(body).motion() instanceof GroundEphemeris.Orbit orbit) body = orbit.parent();
        return body;
    }

    public static int nearestSystem(GroundEphemeris.Model model, Point position, double tick) {
        return model.catalogIndices().stream()
                .filter(i -> model.catalogBody(i).light() && model.catalogBody(i).motion() instanceof GroundEphemeris.Fixed)
                .min(java.util.Comparator.comparingDouble(i -> model.frame(i, tick).center().sub(position).length()))
                .orElseThrow();
    }

    public static double systemRadius(GroundEphemeris.Model model, int root) {
        double extent = 8192;
        for (int i : model.catalogIndices()) if (system(model, i) == root) {
            var body = model.catalogBody(i);
            double reach = 0;
            int index = i;
            while (model.catalogBody(index).motion() instanceof GroundEphemeris.Orbit orbit) {
                reach += orbit.radius(); index = orbit.parent();
            }
            extent = Math.max(extent, reach + Math.max(8192, body.gas().map(g -> body.radius() + g.outerHeight()).orElse(0.0)));
        }
        return extent;
    }

    public static PlanetShape.Pose surface(ShipRecord record) {
        if (!(record.state() instanceof ShipRecord.OnSlot slot) || slot.place().isEmpty()) return null;
        var position = slot.place().orElseThrow().position();
        return PlanetShape.of(slot.slot()).canonical(
                dev.starfold.geometry.CubeSurfaceTopology.Face.VALUES[PlanetAccess.SHARED.face(position.x())],
                PlanetAccess.SHARED.chart(position)).pose();
    }

    public static Point position(GroundEphemeris.Model model, ShipRecord record, double tick) {
        if (record.state() instanceof ShipRecord.InSpace space)
            return ShipParking.evaluate(model, space.place(), tick).position();
        var slot = (ShipRecord.OnSlot) record.state();
        var pose = surface(record);
        return pose == null ? null : model.at(slot.slot(), tick).world(GroundTopology.planet(PlanetShape.of(slot.slot()), pose));
    }

    public static Point velocity(GroundEphemeris.Model model, ShipRecord record, double tick) {
        if (record.state() instanceof ShipRecord.InSpace space)
            return ShipParking.evaluate(model, space.place(), tick).velocity();
        var slot = (ShipRecord.OnSlot) record.state();
        var pose = surface(record);
        return pose == null ? GroundEphemeris.ZERO : model.at(slot.slot(), tick)
                .worldVelocity(GroundTopology.planet(PlanetShape.of(slot.slot()), pose), GroundEphemeris.ZERO);
    }

    private MapNavigation() {}
}
