package dev.starfold.render;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrainTest;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class GroundMipBenchmarkTest {
    @BeforeAll static void boot() { PlanetTerrainTest.registry(); }
    @Test void voxelBuildAndMeshStayWithinTheRetainedBudget() throws Exception {
        var terrain=GroundFarReadinessTest.terrain();
        var entry=GroundFarReadinessTest.install(terrain);
        try {
            long began=System.nanoTime();
            entry.cache.fill(entry.function,2,Face.TOP,64,64,p->entry.ready.set(p,1));
            assertTrue(entry.cache.complete());
            assertTrue(entry.cache.retainedBytes()>0 && entry.cache.retainedBytes()<GroundBaseCache.BUDGET);
            GroundFarMesh.Field field=(f,x,z,s)->entry.surface(f,x,z,s);
            for(int step:new int[]{4,8,16,32,64})
                assertFalse(GroundFarMesh.build(terrain.shape(),0,step,field).isEmpty());
            if(Boolean.getBoolean("starfold.lodBenchmark"))
                System.out.printf("VOXEL size512 build+mesh %.3f ms retained %d%n",(System.nanoTime()-began)/1e6,entry.cache.retainedBytes());
        } finally { GroundBaseCache.clear(); }
    }
}
