package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.*;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.*;

import net.minecraft.world.level.block.*;

import org.junit.jupiter.api.*;

import java.util.*;

class GroundMipMeshTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    static GroundFarMesh.Surface surface(double h) {
        return new GroundFarMesh.Surface(h, Blocks.STONE.defaultBlockState());
    }

    @Test
    void differentLevelsShareClosedWallsIncludingEveryFaceSeam() {
        var shape = new PlanetShape(512);
        GroundFarMesh.Field field =
                (f, x, z, s) -> surface(f.ordinal() * 35 + (x / 8 % 3) * 4 - s * 3);
        var cached = GroundFarMesh.cached(shape, field);
        for (var face : Face.values())
            for (int step : new int[] {1, 2, 4, 8, 16, 32, 64}) {
                int width = Math.max(16, step);
                var quads = GroundFarMesh.build(shape, face, 0, 64, width, step, field);
                for (int z = 64; z < 64 + width; z++) {
                    int cellZ = z / step * step;
                    double own = cached.cell(face, 0, cellZ, step).height();
                    double lower = own;
                    for (int other : new int[] {1, 2, 4, 8, 16, 32, 64})
                        lower = Math.min(lower, cached.cell(face, -1, z, other).height());
                    final double low = lower, at = z + .5;
                    assertTrue(
                            quads.stream()
                                    .filter(q -> GroundFarMesh.side(q) == 0)
                                    .anyMatch(
                                            q ->
                                                    q.points().stream().allMatch(p -> p.x() == 0)
                                                            && GroundFarMeshTest.min(q) < own
                                                            && GroundFarMeshTest.max(q) == own
                                                            && q.points().stream()
                                                                            .mapToDouble(Point::z)
                                                                            .min()
                                                                            .orElseThrow()
                                                                    <= at
                                                            && q.points().stream()
                                                                            .mapToDouble(Point::z)
                                                                            .max()
                                                                            .orElseThrow()
                                                                    >= at));
                    // Map the very same boundary vertex to the incident face, not just its column.
                    var neighbour =
                            shape.topology().canonical(new Cell(face, -1, 0, z)).cell().face();
                    var cube =
                            face.normal
                                    .scale(shape.half())
                                    .add(face.u.scale(-shape.half()))
                                    .add(face.v.scale(z - shape.half()));
                    double u = shape.half() + cube.dot(neighbour.u),
                            v = shape.half() + cube.dot(neighbour.v);
                    assertTrue(
                            shape.direction(face, 0, z)
                                            .sub(shape.direction(neighbour, u, v))
                                            .length()
                                    < 1e-12);
                    // Closed radial intervals on that common edge overlap even at a >64-block jump.
                    var worldHigh = GroundExteriorMesh.position(shape, face, 0, z, own);
                    var worldLow = GroundExteriorMesh.position(shape, neighbour, u, v, low - 64);
                    assertTrue(worldHigh.length() > worldLow.length());
                }
            }
    }

    @Test
    void fineCellsAreNotAliasedByTheFormerSixteenBlockCacheKey() {
        var cached =
                GroundFarMesh.cached(PlanetShape.STANDARD, (f, x, z, s) -> surface(x + z * 2048));
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++)
                assertEquals(x + z * 2048, cached.cell(Face.TOP, x, z, 1).height());
    }

    @Test
    void everyResidentStepHasCompleteBoundedTopsAndWalls() {
        var shape = PlanetShape.STANDARD;
        for (int step : new int[] {1, 2, 4, 8}) {
            var mesh =
                    GroundFarMesh.build(
                            shape,
                            Face.TOP,
                            512,
                            512,
                            16,
                            step,
                            (f, x, z, s) -> surface((x + z) % 29));
            assertEquals(2 * 256 / (step * step), mesh.stream().filter(q -> q.axis() == 1).count());
            assertTrue(mesh.size() <= VoxelLodPolicy.RESIDENT_TILE_QUADS);
            assertTrue(mesh.size() * 128L < VoxelLodPolicy.TILE_BYTES);
        }
    }

    @Test
    void nativeOwnershipIsPreservedAndResidentNeighboursDoNotCullMixedWalls() throws Exception {
        var shape = new PlanetShape(416);
        var complete = new BitSet();
        for (var f : Face.values())
            complete.set((int) GroundOwnership.key(shape, f.ordinal(), 0, 0));
        var owners = GroundOwnership.atlas(shape, GroundOwnership.empty(), Set.of(0L));
        var packed = GroundLodAtlas.pack(shape, owners, complete);
        for (int i = 0; i < owners.length; i++) assertEquals(owners[i], packed[i * 2]);
        int n = shape.chunks(), side = GroundOwnership.SIDE;
        for (var f : Face.values())
            for (int z = -1; z <= n; z++)
                for (int x = -1; x <= n; x++) {
                    long key = GroundOwnership.key(shape, f.ordinal(), x * 16 + 8, z * 16 + 8);
                    assertEquals(
                            complete.get((int) key) ? 255 : 0,
                            packed[2 * (x + 1 + side * (z + 1 + side * f.ordinal())) + 1] & 255);
                }
        var shader = Sources.text("GroundShaderSource");
        assertTrue(shader.contains("if(border&&cpNativeOwner(neighbour)>0.1)discard"));
        assertFalse(shader.contains("CPNativeLodPass>0.5&&cpNativeResident(neighbour)>0.5"));
        assertTrue(
                Sources.text("GroundRenderer")
                        .contains("GroundShaderSource.patchTag(false,GroundFarMesh.side(q))"));
    }

    @Test
    void capturedVoxelPlantsDropToTheirActualGroundWithoutPinningVoxels() {
        var capture = new VoxelColumns.Capture(-64, 320);
        while (!capture.done())
            capture.step(
                    (x, y, z) ->
                            y == 128
                                    ? Blocks.DEAD_BUSH.defaultBlockState()
                                    : y == 127
                                            ? Blocks.GOLD_BLOCK.defaultBlockState()
                                            : Blocks.AIR.defaultBlockState(),
                    8192);
        int[] h = new int[256], m = new int[256];
        Arrays.fill(h, 128);
        Arrays.fill(m, Block.BLOCK_STATE_REGISTRY.getId(Blocks.DEAD_BUSH.defaultBlockState()));
        var image =
                new GroundSurface(1, h, m, capture.result().orElseThrow()).surfaceOnly();
        assertEquals(127, image.height(3, 7));
        assertTrue(image.state(3, 7).is(Blocks.GOLD_BLOCK));
        assertNull(image.voxels());
    }
}
