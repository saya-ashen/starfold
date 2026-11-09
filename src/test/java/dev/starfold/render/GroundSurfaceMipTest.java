package dev.starfold.render;

import dev.starfold.universe.PlanetTypeFixtures;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.VoxelMesher;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.*;

import java.util.*;

class GroundSurfaceMipTest {
    static final BlockState STONE = Blocks.STONE.defaultBlockState(),
            GOLD = Blocks.GOLD_BLOCK.defaultBlockState(),
            WHITE = Blocks.WHITE_CONCRETE.defaultBlockState(),
            LEAVES = Blocks.OAK_LEAVES.defaultBlockState();

    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    static GroundSurfaceMip.Column c(int y, BlockState state) {
        return new GroundSurfaceMip.Column(y, state);
    }

    @Test
    void recursiveMeanMaterialTiesAndFluidLevel() {
        var children = List.of(c(127, STONE), c(128, GOLD), c(128, GOLD), c(190, STONE));
        assertEquals(c(143, GOLD), GroundSurfaceMip.reduce(children));
        assertEquals(
                c(128, GOLD),
                GroundSurfaceMip.reduce(
                        List.of(c(127, STONE), c(128, GOLD), c(128, GOLD), c(129, STONE))));
        assertEquals(
                c(5, LEAVES),
                GroundSurfaceMip.reduce(
                        List.of(c(0, STONE), c(3, GOLD), c(7, LEAVES), c(8, LEAVES))));
        var water = Blocks.WATER.defaultBlockState();
        assertEquals(
                c(127, water),
                GroundSurfaceMip.reduce(
                        List.of(c(200, GOLD), c(180, STONE), c(127, water), c(127, water))));
    }

    @Test
    void padAndCrossAreRecursivelyDownscaledNotPointSampled() {
        // 48-wide disc, deliberately off the mip grid; cross is part of the base image.
        var mip =
                new GroundSurfaceMip(
                        256,
                        256,
                        (x, z) -> {
                            double dx = x + 0.5 - 127, dz = z + 0.5 - 134;
                            return c(
                                    127,
                                    dx * dx + dz * dz > 24 * 24
                                            ? STONE
                                            : Math.abs(dx) < 4 || Math.abs(dz) < 4 ? WHITE : GOLD);
                        });
        var previous = new GroundSurfaceMip.Column[256][256];
        for (int z = 0; z < 256; z++)
            for (int x = 0; x < 256; x++) previous[z][x] = mip.column(x, z, 1);
        for (int s = 2; s <= 64; s *= 2) {
            int count = 256 / s, area = 0;
            var next = new GroundSurfaceMip.Column[count][count];
            for (int z = 0; z < count; z++)
                for (int x = 0; x < count; x++) {
                    next[z][x] =
                            GroundSurfaceMip.reduce(
                                    List.of(
                                            previous[z * 2][x * 2],
                                            previous[z * 2][x * 2 + 1],
                                            previous[z * 2 + 1][x * 2],
                                            previous[z * 2 + 1][x * 2 + 1]));
                    assertEquals(next[z][x], mip.column(x * s, z * s, s));
                    if (!next[z][x].material().equals(STONE)) area += s * s;
                }
            if (s <= 16) {
                System.out.printf(
                        "disc step=%d area=%d error=%.3f%%%n",
                        s, area, 100 * Math.abs(area - Math.PI * 24 * 24) / (Math.PI * 24 * 24));
                assertEquals(WHITE, mip.column(127, 134, s).material());
                assertEquals(Math.PI * 24 * 24, area, Math.PI * 24 * 24 * .03);
            }
            previous = next;
        }
        assertEquals(699008, mip.bytes());
        assertEquals(2688, mip.coarse().bytes());
    }

    @Test
    void sparseTreesFadeAndDenseCanopyStaysByTheSameReduction() {
        var sparse =
                new GroundSurfaceMip(
                        256,
                        256,
                        (x, z) -> c(x < 3 && z < 3 ? 134 : 127, x < 3 && z < 3 ? LEAVES : STONE));
        assertEquals(STONE, sparse.column(0, 0, 16).material());
        assertEquals(127, sparse.column(0, 0, 64).y());
        var forest =
                new GroundSurfaceMip(
                        256,
                        256,
                        (x, z) ->
                                c(
                                        x % 5 == 0 && z % 5 == 0 ? 127 : 134,
                                        x % 5 == 0 && z % 5 == 0 ? STONE : LEAVES));
        for (int s = 2; s <= 64; s *= 2)
            for (int z = 0; z < 256; z += s)
                for (int x = 0; x < 256; x += s)
                    assertEquals(LEAVES, forest.column(x, z, s).material());
    }

    @Test
    void biomeScatterPlantsStayAboveTheSampledBaseWithoutBuildingFullTerrainColumns()throws Exception {
        PlanetTypeFixtures.boot();
        dev.starfold.worldgen.surface.SurfaceTestTags.bind();
        var type = PlanetTypeFixtures.type("lush");
        var terrain=type.planet(42,java.util.Optional.of(type.minSize())).terrain();int plants=0;
        search: for(var face:Face.VALUES)for(int z=96;z<terrain.shape().size()-96;z+=3)for(int x=96;x<terrain.shape().size()-96;x+=3) {
            var runs=terrain.scatter().column(face,x,z);
            if(runs.stream().noneMatch(r->VoxelMesher.plant(r.block())))continue;
            var c=GroundSurfaceMip.sample(terrain,face,x,z,null,null);
            assertFalse(c.material().isAir());assertEquals(terrain.surfaceY(face,x,z),c.y());
            for(var run:runs)assertTrue(run.bottom()>c.y());
            if(++plants==32)break search;
        }
        assertTrue(plants>0);assertEquals(0,terrain.cachedColumns());
    }

    @Test
    void servedRealAirIsNotReplacedByProceduralGroundOrFakeTrees() {
        var terrain = new PlanetTerrain(PlanetTerrain.TEST_SEED,dev.starfold.worldgen.PlanetTerrain.VERSION);
        var real = image(-65, Blocks.AIR.defaultBlockState());
        var actual =
                GroundSurfaceMip.sample(
                        terrain,
                        Face.TOP,
                        512,
                        512,
                        real,
                        new dev.starfold.worldgen.surface.SurfaceFunction(terrain));
        assertEquals(-65, actual.y());
        assertTrue(actual.material().isAir());
    }

    @Test
    void coarseDiscAreaIsQuantizedAndDependsOnGridPhaseNotASilhouetteSpecialCase() {
        var mip =
                new GroundSurfaceMip(
                        256,
                        256,
                        (x, z) ->
                                c(
                                        127,
                                        Math.pow(x + .5 - 128, 2) + Math.pow(z + .5 - 128, 2)
                                                        <= 24 * 24
                                                ? GOLD
                                                : STONE));
        int area = 0;
        for (int z = 0; z < 256; z += 16)
            for (int x = 0; x < 256; x += 16)
                if (mip.column(x, z, 16).material().equals(GOLD)) area += 256;
        // Plain categorical mips cannot promise a few-percent area bound for every subcell phase.
        assertEquals(2048, area);
    }

    static GroundSurface image(int y, BlockState state) {
        int[] h = new int[256], m = new int[256];
        Arrays.fill(h, y);
        Arrays.fill(m, Block.BLOCK_STATE_REGISTRY.getId(state));
        return new GroundSurface(1, h, m);
    }

    @Test
    void capturedStructureWinsOverTerrainAndTreesAtEveryLevel() {
        var terrain = new PlanetTerrain(PlanetTerrain.TEST_SEED, PlanetTerrain.VERSION);
        var trees = new dev.starfold.worldgen.surface.SurfaceFunction(terrain);
        var image = image(210, GOLD);
        var mip =
                new GroundSurfaceMip(
                        256,
                        256,
                        (x, z) ->
                                GroundSurfaceMip.sample(
                                        terrain,
                                        Face.TOP,
                                        512 + x,
                                        512 + z,
                                        x < 128 && z < 128 ? image : null,
                                        trees));
        for (int s = 1; s <= 64; s *= 2) {
            assertEquals(c(210, GOLD), mip.column(64, 64, s));
            assertNotEquals(GOLD, mip.column(192, 192, s).material());
        }
        int leaves = 0;
        for (int z = 600; z < 616; z++)
            for (int x = 600; x < 664; x++) {
                var plain = GroundSurfaceMip.sample(terrain, Face.TOP, x, z, null, null);
                var tree = GroundSurfaceMip.sample(terrain, Face.TOP, x, z, null, trees);
                assertTrue(tree.y() >= plain.y());
                if (tree.canopy() != null && tree.canopy().material().equals(LEAVES)) leaves++;
            }
        assertEquals(0,leaves,"Dry volcanic terrain must not acquire approximate trees");
    }
}
