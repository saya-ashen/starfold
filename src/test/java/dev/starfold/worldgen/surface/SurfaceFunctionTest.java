package dev.starfold.worldgen.surface;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worlds.fixtures.ExperienceFixtures;
import dev.starfold.universe.PlanetType;
import dev.starfold.worldgen.PlanetTerrain;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceFunctionTest {
    static PlanetTerrain home;
    static PlanetType homeType;
    @BeforeAll static void boot() throws Exception {
        ExperienceFixtures.boot();
        var type = ExperienceFixtures.registryLookup().lookupOrThrow(PlanetType.KEY).listElements()
                .filter(h -> h.key().location().getPath().equals("home")).findFirst().orElseThrow().value();
        homeType = type;
        home = type.planet(42,Optional.of(type.minSize())).terrain();
    }

    @Test void constructorDoesNotScanAndMatchingPlansShareOneBackgroundSummary() throws Exception {
        var firstTerrain = homeType.planet(921,Optional.of(homeType.minSize())).terrain();
        var secondTerrain = homeType.planet(921,Optional.of(homeType.minSize())).terrain();
        var first = new SurfaceFunction(firstTerrain);
        var coarse = SurfaceFunction.class.getDeclaredField("coarse"); coarse.setAccessible(true);
        assertNull(coarse.get(first), "pointwise construction never starts a planet scan");
        var a = first.summary();
        var b = new SurfaceFunction(secondTerrain).summary();
        assertSame(a,b,"full plan and surface identity shares a single build");
        var summary = a.get(60,java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(summary);
        // Tick/render readers only poll; they never wait on a future.
        var ready = firstTerrain.readySurface();
        long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while(ready==null && System.nanoTime()<deadline) {
            Thread.onSpinWait(); ready=firstTerrain.readySurface();
        }
        assertNotNull(ready);
        assertSame(summary,firstTerrain.surfaceSummary(ready));
    }

    @Test void chunkColumnsMatchWithSharedTerrainAcrossWorkers() throws Exception {
        var serial = new SurfaceFunction(homeType.planet(42,Optional.of(homeType.minSize())).terrain());
        var parallel = new SurfaceFunction(homeType.planet(42,Optional.of(homeType.minSize())).terrain());
        var expected = new ArrayList<BlockState[]>();
        for (var face : Face.values()) for (int i = 0; i < 256; i++)
            expected.add(serial.blocks(face,320+(i&15),320+(i>>4)));
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var work = new ArrayList<java.util.concurrent.Callable<Void>>();
            for (int repeat = 0; repeat < 2; repeat++) for (var face : Face.values()) for (int i = 0; i < 256; i++) {
                final int column = i;
                work.add(() -> {
                    assertArrayEquals(expected.get(face.ordinal()*256+column),
                            parallel.blocks(face,320+(column&15),320+(column>>4)));
                    return null;
                });
            }
            Collections.shuffle(work,new Random(42));
            for (var result : pool.invokeAll(work)) result.get();
        }
    }

    @Test void comparisonBandMatchesFullColumnsWithoutBuildingBuriedLayers() {
        var random = new Random(42);
        for (int version : new int[]{PlanetTerrain.VERSION}) {
            var fullTerrain = new PlanetTerrain(42,version);
            var bandTerrain = new PlanetTerrain(42,version);
            var full = new SurfaceFunction(fullTerrain);
            var band = new SurfaceFunction(bandTerrain);
            for (var face : Face.values()) for (int i = 0; i < 32; i++) {
                int size = bandTerrain.shape().size();
                int u = i < 4 ? (i%2 == 0 ? 0 : size-1) : random.nextInt(size);
                int v = i < 4 ? u : random.nextInt(size);
                int low = band.groundTop(face,u,v);
                for (int[] d : new int[][]{{-1,0},{1,0},{0,-1},{0,1}})
                    low = Math.min(low,band.groundTop(face,u+d[0],v+d[1]));
                // Include lowered actual neighbours as well as natural cliffs and closure edges.
                int bottom = Math.max(bandTerrain.floor(),low-(i%2 == 0 ? 4 : 40));
                var expected = full.blocks(face,u,v);
                var actual = band.blocks(face,u,v,bottom);
                for (int y = bottom; y < PlanetTerrain.MAX_Y; y++)
                    assertEquals(expected[y-fullTerrain.floor()],actual[y-bandTerrain.floor()],
                            version+" "+face+" "+u+","+v+","+y);
            }
            assertEquals(0,bandTerrain.cachedColumns(),"band queries do not fill the full-column memo");
        }
    }


    @Test void exactCoverageSumsToOneAndRejectsInvalidLevels() {
        var function = new SurfaceFunction(home);
        for (int level : new int[]{1,2,4,8,16,32,64}) {
            var cell = function.sample(Face.NORTH,300,300,level);
            assertEquals(1,cell.materials().stream().mapToDouble(SurfaceFunction.Coverage::share).sum(),1e-12);
            assertTrue(cell.canopyCoverage() >= 0 && cell.canopyCoverage() <= 1);
        }
        assertThrows(IllegalArgumentException.class,() -> function.sample(Face.NORTH,0,0,3));
    }
}
