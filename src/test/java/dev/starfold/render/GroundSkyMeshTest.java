package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.PlanetSpace;
import dev.starfold.sky.AtmosphereScattering;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

class GroundSkyMeshTest {
    @Test
    void cachedDirectionsAndTriangleOrderExactlyMatchTheImmediateMesh() {
        for (var normal : new Point[] {new Point(0, 1, 0), new Point(1, 0, 0),
                new Point(.3, -.8, .2), new Point(1e-9, 1, 1e-9)}) {
            var up = PlanetSpace.unit(normal);
            var east = AtmosphereScattering.horizon(up, new Point(1, 0, 0));
            var north = up.cross(east);
            int index = 0;
            for (int y = 0; y <= GroundSkyMesh.NY; y++)
                for (int x = 0; x <= GroundSkyMesh.NX; x++) {
                    double a = x * 2 * Math.PI / GroundSkyMesh.NX,
                            b = y * Math.PI / GroundSkyMesh.NY;
                    var original = east.scale(Math.sin(b) * Math.cos(a))
                            .add(up.scale(Math.cos(b)))
                            .add(north.scale(Math.sin(b) * Math.sin(a)));
                    assertEquals(original, GroundSkyMesh.direction(index++, east, up, north));
                }
            assertEquals(GroundSkyMesh.VERTICES, index);
        }
        int at = 0;
        for (int y = 0; y < GroundSkyMesh.NY; y++)
            for (int x = 0; x < GroundSkyMesh.NX; x++) {
                int i = y * (GroundSkyMesh.NX + 1) + x;
                for (int k : new int[] {i, i + 1, i + GroundSkyMesh.NX + 2,
                        i, i + GroundSkyMesh.NX + 2, i + GroundSkyMesh.NX + 1}) {
                    assertEquals(k, GroundSkyMesh.INDICES[at++]);
                    assertTrue(k >= 0 && k < GroundSkyMesh.VERTICES);
                    assertTrue(k < 65536);
                }
            }
        assertEquals(GroundSkyMesh.INDICES.length, at);
    }

    @Test
    void persistentVertexBytesMatchVanillaPositionColorTruncation() {
        var out = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        var direction = new Point(.123456789, -.87654321, 1e-12);
        for (var color : new Point[] {new Point(0, 0, 0), new Point(1, 1, 1),
                new Point(.0031308, .4613561295, .999999), new Point(.5, .25, .75)}) {
            out.clear();
            GroundSkyMesh.vertex(out, direction, color);
            assertEquals(16, out.position());
            out.flip();
            var original = direction.scale(128);
            assertEquals((float) original.x(), out.getFloat());
            assertEquals((float) original.y(), out.getFloat());
            assertEquals((float) original.z(), out.getFloat());
            for (double c : new double[] {color.x(), color.y(), color.z(), 1})
                assertEquals((byte) (int) ((float) c * 255f), out.get());
        }
    }

    @Test
    void meshOwnsReusableStorageAndReleasesItOnReloadAndShutdown() throws Exception {
        var mesh = Files.readString(Path.of("src/main/java/dev/starfold/render/GroundSkyMesh.java"));
        assertTrue(mesh.contains("glBufferSubData"));
        assertFalse(mesh.contains("glGet"));
        var upload = mesh.substring(mesh.indexOf("void upload()"), mesh.indexOf("void drawWithShader"));
        assertFalse(upload.contains("memAlloc"));
        assertFalse(upload.contains("glBufferData"));
        var draw = mesh.substring(mesh.indexOf("void drawWithShader"), mesh.indexOf("public void close"));
        assertTrue(draw.indexOf("setDefaultUniforms") < draw.indexOf("shader.apply()"));
        assertTrue(draw.indexOf("shader.apply()") < draw.indexOf("drawElements"));
        assertTrue(draw.contains("VertexBuffer.unbind()"));
        assertTrue(mesh.contains("MemoryUtil.memFree(vertices)"));
        for (var name : new String[] {"vbo", "ebo"})
            assertTrue(mesh.contains("_glDeleteBuffers(" + name + ")"));
        var sky = Files.readString(Path.of("src/main/java/dev/starfold/render/GroundAtmosphere.java"));
        var broad = sky.substring(sky.indexOf("public static void drawSky("), sky.indexOf("public static void rim("));
        assertFalse(broad.contains("new Point["));
        assertFalse(broad.contains("new int["));
        assertFalse(broad.contains("Tesselator"));
        assertFalse(broad.contains("Math.sin"));
        assertTrue(sky.contains("skyMesh.close()"));
        assertTrue(Files.readString(Path.of("src/main/java/dev/starfold/render/GroundCelestials.java"))
                .contains("GroundAtmosphere.clear()"));
    }
}
