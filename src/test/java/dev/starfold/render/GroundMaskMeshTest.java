package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import java.nio.file.Files;
import java.nio.file.Path;

class GroundMaskMeshTest {
    @Test
    void rawMaskSignedEdgesDoNotAliasAnySupportedPositiveTerrainCoordinate() {
        long p = MemoryUtil.nmemAlloc(12);
        try {
            for (float x : new float[] {-1000, -16, -0.0625f, 0, 2048, 3048}) {
                GroundCompact.put(p, x, 128, x, 17, GroundMaskMesh.TAG, 0xf0f0);
                double decoded = Short.toUnsignedInt(MemoryUtil.memGetShort(p)) / 16.0;
                if (decoded > 3072) decoded -= 4096;
                assertEquals(x, decoded);
                assertEquals(255, Short.toUnsignedInt(MemoryUtil.memGetShort(p + 8)) & 255);
            }
            String unpack = GroundCompact.unpack("cpNativePackedBounds=UV2;cpNativeLightUV2=UV2;");
            assertTrue(unpack.contains("if((Packed.y&255u)==255u)"));
            assertTrue(unpack.contains("if(Position.x>3072.0)Position.x-=4096.0;"));
        } finally {
            MemoryUtil.nmemFree(p);
        }
    }

    @Test
    void actualSolidShaderRetainsRawMaskUvColorAndLightWithoutOwnershipDiscard() throws Exception {
        String name = GroundCompact.Variant.SOLID.name;
        var root = Path.of("src/main/resources/assets/starfold/shaders/core");
        String vertex = CoreShaderSource.patch(name, true,
                Files.readString(root.resolve("ground_compact_solid.vsh")));
        assertTrue(vertex.contains("if(tag!=255.0){"));
        assertTrue(vertex.contains("gl_ClipDistance[0]=cpNativeFacing;"));
        assertTrue(vertex.contains("cpNativeSkyVisibility=clamp(float(cpNativeLightUV2.y)/240.0"));
        String fragment = CoreShaderSource.patch(name, false,
                Files.readString(root.resolve("ground_compact_solid.fsh")));
        assertFalse(fragment.matches("(?s).*\\bdiscard\\b.*"));
    }
}
