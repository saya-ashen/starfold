package dev.starfold.render;

import dev.starfold.chart.GroundAir;
import dev.starfold.chart.GroundEphemeris;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.planet.PlanetAccess;
import net.minecraft.core.BlockPos;
import java.util.List;

/** Observer metadata and derived star selection; contains no rendering or event registration. */
final class GroundObserverState {
    GroundEphemeris.Model model;
    ObserverFrame chartFrame;
    long tick, received;
    GroundAir.Config air = GroundAir.DEFAULT;
    boolean repair = true, heldF8;
    List<GroundEphemeris.Star> suns = List.of();
    long sunsChosen = Long.MIN_VALUE;
    GroundEphemeris.Model sunsOf;

    boolean accepts(Point feet, Point eye, boolean controlled) {
        if (!inFrame(feet) || !inFrame(eye)) return false;
        if (!controlled) {
            try {
                PlanetAccess.SHARED.body(feet);
            } catch (IllegalArgumentException outsideNativeShell) {
                return false;
            }
        }
        return true;
    }

    private boolean inFrame(Point p) {
        var access = PlanetAccess.SHARED;
        var origin = access.origin(chartFrame.planet(), chartFrame.root());
        return p.finite()
                && access.inChart(BlockPos.containing(p.x(), p.y(), p.z()))
                && access.storagePlanet(p.x(), p.z()) == chartFrame.planet()
                && access.face(p.x()) == chartFrame.root()
                && PlanetShape.of(chartFrame.planet()).topology()
                        .inHalo(p.x() - origin.x(), p.z() - origin.z());
    }

    void clear() {
        chartFrame = null;
        model = null;
        sunsOf = null;
        suns = List.of();
        heldF8 = false;
        repair = true;
        air = GroundAir.DEFAULT;
    }
}
