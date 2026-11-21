package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrain;
import dev.starfold.worldgen.PlanetTerrainTest;
import dev.starfold.worldgen.lod.VoxelColumn;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import java.util.*;

class GroundSkyInputsTest {
    @Test
    void preparedFallbackMatchesEveryVertexIncludingWallRimsAndDoesNotCrossTerrainIdentity() {
        PlanetTerrainTest.registry();
        var terrain = new PlanetTerrain(PlanetTerrain.TEST_SEED);
        var other = new PlanetTerrain(PlanetTerrain.TEST_SEED + 1);
        for (var face : List.of(Face.TOP, Face.NORTH)) {
            var built = GroundQuadtreeMesher.build(face, 512, 512, 16, 4, 0,
                    (x, z) -> new VoxelColumn(List.of(new VoxelColumn.Run(0, 100,
                            Blocks.STONE.defaultBlockState()), new VoxelColumn.Run(100, 128,
                            Blocks.WATER.defaultBlockState()))));
            var sky = GroundMeshContext.prepareSky(built, terrain, () -> true);
            assertFalse(sky.levels().isEmpty());
            assertTrue(sky.levels().size() < built.vertexCount());
            assertThrows(UnsupportedOperationException.class, () -> sky.levels().clear());
            var data = built.vertices();
            for (int off = 0; off < data.limit(); off += 12) {
                int tag = Short.toUnsignedInt(data.getShort(off + 8));
                int id = Short.toUnsignedInt(data.getShort(off + 6)) | (tag >>> 8) << 16;
                var material = built.materials().get(id);
                double y = Short.toUnsignedInt(data.getShort(off + 2)) / 16. - 2048
                        + ((tag & 4) == 0 ? material.lowerHeightOffset() : material.upperHeightOffset());
                var pos = GroundMeshContext.lightPosition(face.ordinal(), material,
                        Short.toUnsignedInt(data.getShort(off)) / 16., y,
                        Short.toUnsignedInt(data.getShort(off + 4)) / 16.);
                int x = pos.getX() - face.ordinal() * 4096, z = pos.getZ();
                assertEquals(GroundMeshContext.skyLevel(terrain, face.ordinal(), x, z),
                        sky.level(terrain, face.ordinal(), x, z));
                assertEquals(GroundMeshContext.skyLevel(other, face.ordinal(), x, z),
                        sky.level(other, face.ordinal(), x, z));
                if (material.normalY() == 0)
                    assertEquals((int) Math.ceil(128 + material.topY()), pos.getY());
            }
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> GroundMeshContext.prepareSky(built, terrain, () -> false));
        }
        var wet = new GroundMeshContext.Sky(terrain, Map.of(0L, 129));
        assertEquals(129, wet.level(terrain, 0, 0, 0));
        assertEquals(GroundMeshContext.skyLevel(other, 0, 0, 0), wet.level(other, 0, 0, 0));
        assertEquals(Integer.MIN_VALUE, GroundMeshContext.skyLevel(null, 0, 0, 0));
    }
}
