package dev.starfold.render;

import dev.starfold.chart.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundLodBoundsTest {
    @Test void cornerHullPlusMarginEnclosesRepairedGroups() {
        double worst = 0;
        for (int size : new int[] {256, 512, 2048})
            for (var root : Face.values())
                for (double height : new double[] {0, 80, 160})
                    for (var eye : new Point[] {new Point(24, height, 40), new Point(size / 2.0, height, size / 2.0),
                            new Point(size - 8, height, size / 3.0)}) {
                        var gap = new PlanetGap(new PlanetShape(size), root, eye, 256, true, GroundAir.DEFAULT, true);
                        for (var tile : gap.chart.tiles())
                            for (int x0 = 0; x0 < size; x0 += 64)
                                for (int z0 = 0; z0 < size; z0 += size / 4) {
                                    var source = new AABB(x0 - .0625, -192, z0, x0 + 64, 48, z0 + 64.0625);
                                    var bounds = GroundLodBounds.planar(source, gap, tile.face(), new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>());
                                    if (bounds == null) continue; // Pole/hidden half deliberately stays unculled.
                                    bounds = bounds.inflate(-GroundLodBounds.MARGIN, 0, -GroundLodBounds.MARGIN);
                                    for (int x = 0; x <= 16; x++)
                                        for (int z = 0; z <= 16; z++) {
                                            var p = gap.point(tile.face(), new Point(
                                                    source.minX + source.getXsize() * x / 16, 0,
                                                    source.minZ + source.getZsize() * z / 16));
                                            double out = Math.max(
                                                    Math.max(bounds.minX - p.x(), p.x() - bounds.maxX),
                                                    Math.max(bounds.minZ - p.z(), p.z() - bounds.maxZ));
                                            worst = Math.max(worst, out);
                                        }
                                }
                    }
        assertTrue(worst <= GroundLodBounds.MARGIN / 2, "bulge " + worst);
    }
}
