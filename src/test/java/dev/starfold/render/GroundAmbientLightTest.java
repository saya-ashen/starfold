package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.geometry.BentStripGeometry.Point;

import org.junit.jupiter.api.Test;

class GroundAmbientLightTest {
    private double light(Point sun, Point up, Point normal) {
        return .12 + .88 * (.6 * Math.max(0, sun.dot(up)) + .4 * Math.max(0, sun.dot(normal)));
    }

    @Test
    void noonSidesReceiveSkyButNightRetainsTheFloor() {
        var up = new Point(0, 1, 0);
        var side = new Point(1, 0, 0);
        double ratio = light(up, up, side) / light(up, up, up);
        assertTrue(ratio >= .6 && ratio <= .8);
        assertEquals(.12, light(up.scale(-1), up, side), 1e-9);
        assertEquals(.12, light(up.scale(-1), up, up), 1e-9);
        assertEquals(.12, light(side, up, up), 1e-9);
    }

    @Test
    void solarHelperHasNoOwnershipOrScreenDoorFade() {
        assertFalse(GroundShaderSource.SOLAR_LIGHT.contains("cpGroundHidden"));
        assertFalse(GroundShaderSource.SOLAR_LIGHT.contains("gl_FragCoord"));
    }

    @Test
    void farLightmapUsesVanillaFilteredCoordinatesRatherThanTheBrighterIntegerTexel()
            throws Exception {
        String vanilla;
        try (var in =
                dev.starfold.TestArchiveResources.open(
                        "/assets/minecraft/shaders/include/light.glsl")) {
            vanilla = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        String sample = "texture(lightMap, clamp(uv / 256.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)))";
        assertTrue(vanilla.contains(sample));
        String farSample =
                sample.replace("lightMap", "Sampler2")
                        .replace("uv", "cpNativeLightUV2")
                        .replace(" ", "");
        String vertex;
        try (var in = dev.starfold.TestArchiveResources.open("/assets/starfold/shaders/core/ground_compact_solid.vsh")) {
            vertex = CoreShaderSource.patch(GroundCompact.Variant.SOLID.name, true,
                    new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertTrue(vertex.contains("vertexColor=vec4(" + farSample + ".rgb,1.0)"));
        assertFalse(vertex.contains("texelFetch(Sampler2,cpNativeLightUV2/16,0)"));
        // GL_LINEAR addresses texel centres at (row+.5)/16, not row/16.
        for (int light : new int[] {0, 1, 8, 14, 15}) {
            double row = Math.clamp(light * 16 / 256.0, .5 / 16, 15.5 / 16) * 16 - .5;
            assertEquals(Math.max(0, light - .5), row);
        }
        assertEquals(14.5, Math.clamp(240 / 256.0, .5 / 16, 15.5 / 16) * 16 - .5);
    }

    @Test
    void chartUsesVanillaLightmapWhileOrbitalExteriorKeepsSolarLight() throws Exception {
        String glsl = GroundShaderSource.SOLAR_LIGHT;
        assertTrue(glsl.contains("return 0.6*skyDay+0.4*faceDay"));
    }
}
