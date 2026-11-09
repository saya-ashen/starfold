package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.blaze3d.vertex.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import java.nio.file.*;
import java.util.*;

class GroundCompactTest {
    @Test void twelveBytePositionsTagsAndLightRoundTripIncludingLastPlanetEdge() {
        assertEquals(12, GroundCompact.FORMAT.getVertexSize());
        assertEquals(12, GroundExteriorVertices.FORMAT.getVertexSize());
        long p = MemoryUtil.nmemAlloc(12);
        try {
            GroundCompact.put(p, 2048, -192.0625, 256.5, 65535, 243, 0xf0f0);
            assertEquals(2048, Short.toUnsignedInt(MemoryUtil.memGetShort(p)) / 16.0);
            assertEquals(-192.0625, Short.toUnsignedInt(MemoryUtil.memGetShort(p + 2)) / 16.0 - 2048);
            assertEquals(256.5, Short.toUnsignedInt(MemoryUtil.memGetShort(p + 4)) / 16.0);
            assertEquals(65535, Short.toUnsignedInt(MemoryUtil.memGetShort(p + 6)));
            assertEquals(243, Short.toUnsignedInt(MemoryUtil.memGetShort(p + 8)));
            assertEquals(0xf0f0, Short.toUnsignedInt(MemoryUtil.memGetShort(p + 10)));
            GroundCompact.put(p,0,0,0,0x123456,243,0xf0f0);
            int packed=Short.toUnsignedInt(MemoryUtil.memGetShort(p+8));
            assertEquals(243,packed&255);
            assertEquals(0x123456,Short.toUnsignedInt(MemoryUtil.memGetShort(p+6))|(packed>>>8)<<16);
        } finally { MemoryUtil.nmemFree(p); }
        for (var n : List.of(new Point(1,0,0), new Point(0,1,0), new Point(0,0,-1), new Point(-.3,.4,.8660254))) {
            int oct = GroundExteriorVertices.oct(n);
            double x = (byte)oct / 127.0, y = (byte)(oct >> 8) / 127.0, z = 1 - Math.abs(x) - Math.abs(y);
            if (z < 0) { double old = x; x = (1 - Math.abs(y)) * (x >= 0 ? 1 : -1); y = (1 - Math.abs(old)) * (y >= 0 ? 1 : -1); }
            assertTrue(new Point(x,y,z).scale(1 / new Point(x,y,z).length()).dot(n) > .999);
        }
    }

    @Test void lodPositionRetainsItsEmittingTileOnBothEdgesAndTheLastPlanetEdge() {
        for (int tile = 0; tile < 128; tile++)
            for (int local = 0; local <= 256; local++) {
                double position = tile * 16 + local / 16.0;
                int packed = Short.toUnsignedInt((short)GroundCompact.tilePosition(position, tile));
                assertEquals(tile, packed >>> 9);
                assertEquals(position, (packed >>> 9) * 16 + (packed & 511) / 16.0);
            }
        assertNotEquals(GroundCompact.tilePosition(16, 0), GroundCompact.tilePosition(16, 1));
        assertEquals(127, GroundCompact.tilePosition(2048, 127) >>> 9);
    }

    @Test void closureLightSamplesTheExposedRimRatherThanTheBuriedWallCentre() {
        var vertices = java.nio.ByteBuffer.allocate(128).order(java.nio.ByteOrder.nativeOrder());
        var points = dev.starfold.worldgen.lod.VoxelMesher.points(0,48,-640,64,64,10,80);
        for (int i=0;i<4;i++) {
            var p=points.get(i);
            vertices.putFloat(i*32,(float)p.x()).putFloat(i*32+4,(float)p.y()).putFloat(i*32+8,(float)p.z());
        }
        assertEquals(new net.minecraft.core.BlockPos(4*4096+47,138,72),
                GroundCompact.lightPosition(4,vertices,0));
    }

    @Test void surfaceLightChecksActualClientChunkResidencyAndLightBeforeReadingLight() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/starfold/render/GroundRenderer.java"));
        String sample = source.substring(source.indexOf("static int surfaceLight("), source.indexOf("private GroundRenderer()"));
        assertTrue(sample.contains("level.getChunkSource().hasChunk(storage.getX() >> 4, storage.getZ() >> 4)"));
        assertTrue(sample.contains("level.getLightEngine().lightOnInSection("));
        assertFalse(sample.contains("level.hasChunkAt("));
        assertTrue(sample.contains("return GroundFarTexture.sky(depth) * 4096;"));
    }

    @Test void compactShaderUsesTheSameGroundWrapper() throws Exception {
        var dir = Path.of("build/match/shaders");
        Files.createDirectories(dir);
        for (String suffix : List.of("vsh", "fsh")) {
            String source = Files.readString(Path.of("src/main/resources/assets/starfold/shaders/core/ground_compact." + suffix));
            source = GroundFarTextureTest.expand(source);
            String result = CoreShaderSource.patch(GroundCompact.NAME, suffix.equals("vsh"), source);
            if (suffix.equals("vsh")) {
                assertTrue(result.contains("in vec3 PackedPosition;"));
                assertTrue(result.indexOf("Position=PackedPosition/16.0") < result.indexOf("cpNativePackedBounds=UV2;"));
                assertFalse(result.contains("in vec4 Color;"));
                assertFalse(result.contains("CPNativeLodTile"));
                assertTrue(result.contains("if(CPNativeLodPass>0.5)Position.xz="));
                assertTrue(result.contains("vec2 tile=floor(PackedPosition.xz/512.0)*16.0;"));
                assertEquals(1, result.split("uniform float CPNativeLodPass;", -1).length - 1);
            } else assertTrue(result.contains("if(CPNativeCover>0.5&&owner>0.1)discard;"));
            io.github.ocelot.glslprocessor.api.GlslParser.preprocessParse(result, new HashMap<>());
            Files.writeString(dir.resolve("compact." + (suffix.equals("vsh") ? "vert" : "frag")), result);
        }
    }
}
