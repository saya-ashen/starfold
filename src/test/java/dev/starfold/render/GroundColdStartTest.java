package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.*;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTerrainTest;

import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

class GroundColdStartTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    @Test
    void startupSamplesOnlySixteenColumnsPerPatchAndAllFineRequestsHaveACoarseAncestor() {
        var calls = new AtomicInteger();
        var mip =
                GroundSurfaceMip.initial(
                        256,
                        256,
                        (x, z) -> {
                            calls.incrementAndGet();
                            assertEquals(32, x % 64);
                            assertEquals(32, z % 64);
                            return new GroundSurfaceMip.Column(
                                    x + z, Blocks.STONE.defaultBlockState());
                        });
        assertEquals(16, calls.get());
        assertEquals(128, mip.bytes());
        for (int z = 0; z < 256; z++)
            for (int x = 0; x < 256; x++)
                for (int step : new int[] {1, 2, 4, 8, 16, 32, 64})
                    assertEquals(mip.column(x, z, 64), mip.column(x, z, step));
        assertEquals(16, calls.get());
    }

    @Test
    void anyFaceCanGetFirstCoverageWithoutRunningOrQueuingATrueMip() {
        var terrain = new PlanetTerrain(42, dev.starfold.worldgen.PlanetTerrain.VERSION);
        var shape = terrain.shape();
        for (var face : Face.values()) {
            var cache = new GroundMipCache();
            var camera = shape.direction(face, 1024, 1500).scale(shape.radius() + 102);
            var order = GroundViewPolicy.nearestPatches(shape, camera);
            assertEquals(face.ordinal(), GroundViewPolicy.face(order.getFirst()));
            assertEquals(384, new HashSet<>(order).size());
            double previous = -1;
            for (int p : order) {
                double distance = GroundFarMesh.distance(shape, camera, p, 0);
                assertTrue(distance >= previous);
                previous = distance;
            }
            var field = cache.initialField(terrain);
            assertFalse(GroundFarMesh.build(shape, order.getFirst(), 64, field).isEmpty());
            assertTrue(
                    cache.coarseBytes() >= 0 && cache.coarseBytes() < 512L * 1024 * 1024,
                    "run arrays are allocated only as patches are published");
            assertEquals(0, cache.fineBytes());
            cache.clear();
            GroundBaseCache.clear();
        }
    }

    @Test
    void vanillaFrustumStaysVisibleWithoutRetiredOwnershipRejection() throws Exception {
        var fragment =
                GroundShaderSource.patch(
                        "rendertype_solid",
                        false,
                        "#version 150\nuniform sampler2D Sampler0;in vec2 texCoord0;out vec4 fragColor;void main(){fragColor=texture(Sampler0,texCoord0);}");
        assertFalse(fragment.contains("if(CPNativeCoarse<0.5&&cpNativeDither()<far)discard;"));
        assertFalse(fragment.contains("owner>0.1"));
        assertFalse(fragment.contains("CPNativeCoverage"));
        assertFalse(fragment.contains("cpNativeDither"));
    }
}
