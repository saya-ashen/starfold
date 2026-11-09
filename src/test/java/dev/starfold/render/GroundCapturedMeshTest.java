package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.*;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

class GroundCapturedMeshTest {
    @BeforeAll
    static void boot() {
        PlanetTerrainTest.registry();
    }

    private static GroundSurface slab() {
        var capture = new VoxelColumns.Capture(-64, 320);
        while (!capture.done())
            capture.step(
                    (x, y, z) ->
                            y == 128
                                    ? Blocks.STONE.defaultBlockState()
                                    : Blocks.AIR.defaultBlockState(),
                    8192);
        int[] heights = new int[256], states = new int[256];
        Arrays.fill(heights, 128);
        Arrays.fill(states, Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState()));
        return new GroundSurface(1, heights, states, capture.result().orElseThrow());
    }

    @Test
    void coarseCapturedCellsRetainThinBuiltStructures() {
        var capture = new VoxelColumns.Capture(120, 150);
        while (!capture.done())
            capture.step(
                    (x, y, z) ->
                            y <= 128
                                    ? Blocks.STONE.defaultBlockState()
                                    : x == 1 && z == 1 && y < 145
                                            ? Blocks.GOLD_BLOCK.defaultBlockState()
                                            : Blocks.AIR.defaultBlockState(),
                    8192);
        for (int step : new int[] {1, 2, 4, 8}) {
            var quads = new ArrayList<VoxelMesher.Quad>();
            try (var mesh =
                    new GroundCapturedMesh(
                            capture.result().orElseThrow(),
                            Face.BOTTOM,
                            0,
                            0,
                            step,
                            4096,
                            new PlanetShape(416),
                            k -> null)) {
                while (!mesh.done()) mesh.step(Long.MAX_VALUE, quads::add);
                assertFalse(mesh.fallback());
            }
            assertTrue(
                    quads.stream().anyMatch(q -> q.material().is(Blocks.GOLD_BLOCK)),
                    "LOD " + step);
            assertTrue(quads.stream().flatMap(q -> q.points().stream()).anyMatch(p -> p.y() == 17));
        }
    }

    @Test
    void availableNeighbourCullsSharedBoundaryIncludingFaceSeams() {
        var shape = PlanetShape.STANDARD;
        var image = slab();
        for (var face : Face.values())
            for (int x : new int[] {32, shape.size() - 16})
                for (int step : new int[] {1, 2}) {
                    int z = 48;
                    var c =
                            shape.topology()
                                    .canonical(new CubeSurfaceTopology.Cell(face, x + 16, 0, z))
                                    .cell();
                    var surfaces =
                            Map.of(
                                    GroundOwnership.key(shape, face.ordinal(), x, z),
                                    image,
                                    GroundOwnership.key(shape, c.face().ordinal(), c.u(), c.v()),
                                    image);
                    var quads = new ArrayList<VoxelMesher.Quad>();
                    try (var mesh =
                            new GroundCapturedMesh(
                                    image.voxels(), face, x, z, step, 4096, shape, surfaces::get)) {
                        while (!mesh.done()) mesh.step(Long.MAX_VALUE, quads::add);
                        assertFalse(mesh.fallback());
                    }
                    assertFalse(quads.isEmpty());
                    assertTrue(
                            quads.stream().noneMatch(q -> q.axis() == 1),
                            "Shared +X boundary: " + face);
                    assertTrue(
                            quads.stream().anyMatch(q -> q.axis() == 0),
                            "Unknown -X neighbour stays open");
                }
    }
}
