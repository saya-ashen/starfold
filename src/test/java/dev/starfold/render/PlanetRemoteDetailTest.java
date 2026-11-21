package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.worldgen.lod.VoxelLodPolicy;

import org.junit.jupiter.api.Test;

class PlanetRemoteDetailTest {
    @Test
    void remoteCoverUsesTheExistingPixelTargetAndBoundedPlanetLevels() {
        assertEquals(64, GroundFarMesh.level(30000, 64, 70, 720));
        assertEquals(32, GroundFarMesh.level(30000, 64, 70, 1440));
        assertEquals(16, GroundFarMesh.level(30000, 64, 7, 720));
        assertEquals(16, GroundFarMesh.level(100, 64, 7, 2160));
        for (double distance : new double[] {100, 1000, 10000, 30000, 100000})
            for (double fov : new double[] {7, 30, 70, 110})
                for (int height : new int[] {720, 1080, 2160})
                    assertEquals(
                            Math.max(16, VoxelLodPolicy.screenStep(distance, 64, fov, height)),
                            GroundFarMesh.level(distance, 64, fov, height));
    }

    @Test
    void remoteLevelsKeepTheSameHysteresisAsGroundCover() {
        double focal = 720 / (2 * Math.tan(Math.toRadians(70) / 2));
        assertEquals(64, GroundFarMesh.level(focal * 64 / 1.4, 64, 70, 720));
        assertEquals(32, GroundFarMesh.level(focal * 64 / 1.6, 64, 70, 720));
        assertEquals(32, GroundFarMesh.level(focal * 64 / 1.2, 32, 70, 720));
        assertEquals(64, GroundFarMesh.level(focal * 64, 32, 70, 720));
    }
}
