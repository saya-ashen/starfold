package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
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
                    assertEquals(Math.max(16, VoxelLodPolicy.screenStep(distance, 64, fov, height)),
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

    @Test
    void rebuildFreezesTheRequestedLevelAndKeepsResidentUntilWholeCohortIsReady() throws Exception {
        var source = Sources.text("RemotePlanetMeshes");
        var renderer = Sources.text("PlanetRemoteRenderer");
        assertTrue(renderer.contains("GroundMeshScheduler.lodFov,mc.getWindow().getHeight()"));
        assertTrue(Sources.text("GroundRenderer").contains(
                "event.usedConfiguredFov() && event.getFOV()>0"),
                "the first zero-FOV frame must not poison either LOD selector");
        assertTrue(renderer.contains("distance-terrain.shape().radius()"));
        assertTrue(source.contains("residentStep!=requestedStep"));
        assertTrue(source.contains("buildingStep=requestedStep"));
        assertTrue(source.contains("residentStep==buildingStep"), "inputs alone must not reuse a coarser mesh");
        assertTrue(source.contains("new Job(building,cursor,buildingStep,mask,inputCover,pyramids)"));
        assertTrue(source.contains("shape,patch,step,pyramids.field(shape,snapshot.terrain(),cover,patch)"));
        assertTrue(source.contains("if(cursor==384){for(int i=0;i<384;i++)"));
        assertTrue(source.contains("resident=building;residentStep=buildingStep;building=null"));
        assertTrue(renderer.contains("System.nanoTime()+2_000_000"));
        assertFalse(source.contains("shape,patch,64,snapshot.terrain()"));
        assertFalse(source.contains("view.snapshot().planet()"), "do not copy captures every frame");
    }
}
