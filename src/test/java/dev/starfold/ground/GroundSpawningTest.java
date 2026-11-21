package dev.starfold.ground;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.PlanetShape;
import dev.starfold.planet.PlanetAccess;

import net.minecraft.world.level.ChunkPos;

import org.junit.jupiter.api.Test;

class GroundSpawningTest {
    @Test
    void vanillaRadiusIsStrictAndHorizontalOutsidePlanetCharts() {
        var access = PlanetAccess.SHARED;
        var chunk = new ChunkPos(-1000, -1000);
        double x = chunk.getMiddleBlockX(), z = chunk.getMiddleBlockZ();
        assertTrue(GroundSpawning.close(access, x + 127.99, z, chunk));
        assertFalse(GroundSpawning.close(access, x + 128, z, chunk));
        assertFalse(GroundSpawning.close(access, x + 100, z + 100, chunk));
    }

    @Test
    void adjacentFacesCountNearbyObserversButOtherPlanetsDoNot() {
        try {
            PlanetShape.system(new int[] {1024, 1024}, new int[] {191, 191});
            var access = PlanetAccess.SHARED;
            int seamCases = 0;
            for (int face = 0; face < 6; face++) {
                var base = access.origin(0, face);
                for (int other = 0; other < 6; other++) {
                    if (face == other) continue;
                    var target = access.origin(0, other);
                    for (int u : new int[] {8, 504, 1016})
                        for (int v : new int[] {8, 504, 1016}) {
                            var chunk =
                                    new ChunkPos(
                                            (int) (target.x() + u) >> 4,
                                            (int) (target.z() + v) >> 4);
                            var image =
                                    access.image(
                                            base.x() + 512,
                                            base.z() + 512,
                                            new dev.starfold.geometry.BentStripGeometry.Point(
                                                    chunk.getMiddleBlockX(),
                                                    128,
                                                    chunk.getMiddleBlockZ()));
                            if (image == null) continue;
                            double x = Math.clamp(image.x(), base.x() + 8, base.x() + 1016);
                            double z = Math.clamp(image.z(), base.z() + 8, base.z() + 1016);
                            if (Math.hypot(x - image.x(), z - image.z()) >= 64) continue;
                            assertTrue(
                                    GroundSpawning.close(access, x, z, chunk), face + "→" + other);
                            assertTrue(
                                    Math.hypot(
                                                    x - chunk.getMiddleBlockX(),
                                                    z - chunk.getMiddleBlockZ())
                                            > 128);
                            seamCases++;
                        }
                }
                var foreign = access.origin(1, face);
                assertFalse(
                        GroundSpawning.close(
                                access,
                                base.x() + 512,
                                base.z() + 512,
                                new ChunkPos(
                                        (int) (foreign.x() + 512) >> 4,
                                        (int) (foreign.z() + 512) >> 4)));
            }
            assertTrue(seamCases >= 24, "Exercise every oriented face edge");
        } finally {
            PlanetShape.reset();
        }
    }
}
