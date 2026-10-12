package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.render.GroundAlbedo;
import dev.starfold.render.GroundClient;
import dev.starfold.worldgen.PlanetHeightCache;
import dev.starfold.worldgen.PlanetTerrain;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

/** Numeric reproductions of open height strips and the 183.1-altitude view disk cut. */
class GroundTransitionTest {
    @Test
    void original183AltitudeClipCutsAFrontFacingVisibleGroundPoint() throws Exception {
        var eye = new Point(1024, 183.1, 1024);
        var orbit = new GroundOrbit(new GroundSpace.Pose(Face.SOUTH, eye), eye, 128);
        var source = new Point(1400, 0, 1024);
        var p = orbit.point(Face.SOUTH, source);
        double distance = Math.hypot(p.x() - eye.x(), p.z() - eye.z());
        assertTrue(GroundOrbit.direction(Face.SOUTH, source.x(), source.z()).dot(orbit.normal) > 0);
        assertTrue(distance > orbit.clip);
        assertTrue(distance < GroundTransition.clip(eye.y(), orbit.clip, 128));
        assertEquals(163.3, orbit.clip, 1);
        var out = Path.of("build/native-transition");
        Files.createDirectories(out);
        Files.writeString(
                out.resolve("reproduction.json"),
                String.format(
                        java.util.Locale.ROOT,
                        "{\"eye_height\":183.1,\"face\":4,\"view\":128,\"old_clip\":%.9f,\"sample_distance\":%.9f,\"new_clip\":%.9f,\"front_facing\":true,\"exact_screenshot_reproduced\":false,\"gpu_verified\":false}\n",
                        orbit.clip,
                        distance,
                        GroundTransition.clip(eye.y(), orbit.clip, 128)));
    }

    @Test
    void nativeRangeExtendsSmoothlyWithoutTouchingTheAcceptedNearGroundRange() {
        for (double y : new double[] {0, 30, 64, 127, 128})
            assertEquals(152, GroundTransition.clip(y, 152, 128));
        assertTrue(GroundTransition.clip(144, 152, 128) > 152);
        assertTrue(GroundTransition.clip(160, 152, 128) < 400);
        assertTrue(GroundTransition.clip(320, 152, 128) > 2800);
        for (int y = 129; y <= 320; y++)
            assertTrue(
                    GroundTransition.clip(y, 152, 128) - GroundTransition.clip(y - 1, 152, 128)
                            < 32);
        assertEquals(1e30, GroundTransition.clip(2000, Double.POSITIVE_INFINITY, 128));
        double prior = 0;
        for (int y = 128; y <= 1088; y++) {
            double clip = GroundTransition.clip(y, 152, 128);
            assertTrue(clip >= prior);
            prior = clip;
        }
    }

    @Test
    void realBlocksDissolveIntoTheFarTerrainInsideTheLoadedSquareAndBeforeVanillaFog()
            throws Exception {
        for (int far : new int[] {32, 128, 192, 512, 1024}) {
            float[] f = GroundClient.fade(far);
            assertTrue(0 < f[0] && f[0] < f[1] && f[1] <= far - 16);
            assertTrue(0 < f[2] && f[2] < f[3] && f[3] <= far - Math.clamp(far / 10f, 4f, 64f));
        }
        assertEquals(0, GroundTransition.fade(128));
        assertEquals(1, GroundTransition.fade(160));
        var shader = Sources.text("GroundShaderSource");
        assertTrue(
                shader.contains(
                        "smoothstep(CPNativeFade.x,CPNativeFade.y,length(cpNativeRelative.xz))*CPNativeFadeMix"));
        assertTrue(
                shader.contains("smoothstep(CPNativeFade.z,CPNativeFade.w,-cpNativeRelative.y)"));
        assertTrue(shader.contains("cpNativeRelative=relative;"));
    }

    @Test
    void unequalColumnTopsNeedAVerticalFaceNotJustAnExclusionBit() {
        var wall = GroundTransition.bridge(15, 16, 1, 0, 20, 19, 19);
        assertEquals(4, wall.size());
        assertEquals(
                1,
                wall.get(1).sub(wall.getFirst()).cross(wall.get(3).sub(wall.getFirst())).length(),
                1e-9);
        assertTrue(GroundTransition.bridge(15, 16, 1, 0, 20, 20, 20).isEmpty());
    }

    @Test
    void realSeedHasNativeToCoarseHeightMismatchThatMustBeStitched() {
        var cache = new PlanetHeightCache(new PlanetTerrain(PlanetTerrain.TEST_SEED));
        int gaps = 0;
        for (int x = 16; x < 2048; x += 64)
            for (int z = 16; z < 2048; z += 64) {
                double nativeTop = Math.max(0, cache.ground(Face.SOUTH, x - 1, z) + 1 - 128);
                double coarse = GroundTransition.generated(cache, Face.SOUTH, x, z);
                if (Math.abs(nativeTop - coarse) > .1) {
                    assertFalse(
                            GroundTransition.bridge(x, z, 0, 1, nativeTop, coarse, coarse)
                                    .isEmpty());
                    gaps++;
                }
            }
        assertTrue(gaps > 100);
    }

    @Test
    void originalAlbedoReadsABGRChannelsAndAppliesTintInsteadOfMapColours() {
        assertEquals(0xff0000, GroundAlbedo.average(new int[] {0xff0000ff}, 0xffffff));
        assertEquals(0x00ff00, GroundAlbedo.average(new int[] {0xff00ff00}, 0xffffff));
        assertEquals(0x0000ff, GroundAlbedo.average(new int[] {0xffff0000}, 0xffffff));
        assertEquals(0x3f76e4, GroundAlbedo.average(new int[] {0xffffffff}, 0x3f76e4));
    }

    @Test
    void transparentPixelsDoNotBleedAndAveragingIsLinearLight() {
        assertEquals(0xffffff, GroundAlbedo.average(new int[] {0xffffffff, 0x00000000}, 0xffffff));
        assertEquals(0xbcbcbc, GroundAlbedo.average(new int[] {0xffffffff, 0xff000000}, 0xffffff));
        assertEquals(0xff00ff, GroundAlbedo.average(new int[] {0}, 0xffffff));
    }

    @Test
    void cachedAlbedoUsesActualSpriteTintAndReloadInvalidation() throws Exception {
        var source = Sources.text("GroundAlbedo");
        assertTrue(
                source.contains("IClientFluidTypeExtensions.of(fluid.getType())")
                        && source.contains("getStillTexture(fluid,view,pos)"));
        assertTrue(source.contains("getTintColor(fluid,view,pos)"));
        assertTrue(source.contains("getTintIndex"));
        assertTrue(source.contains("getPixelsRGBA"));
        assertFalse(source.contains(".getMapColor("));
        var exterior = Sources.text("GroundExterior");
        assertFalse(exterior.contains(".getMapColor("));
        assertTrue(exterior.contains("GroundAlbedo.clear()"));
        assertTrue(exterior.contains("GroundAlbedo.color(state"));
    }

    @Test
    void rendererClosesColumnSidesAndCachesReadySelectionBorderStrips() throws Exception {
        var source = Sources.text("GroundRenderer");
        assertTrue(source.contains("columnWalls(builders,face"));
        assertTrue(source.contains("stitchJob.step(deadline)"));
        assertTrue(source.contains("!ownership.sameAs(available)"));
        assertTrue(source.contains("&&mesh!=stitches[face]"));
        assertTrue(source.contains("stitches[i]=pendingStitches[i]"));
    }

    @Test
    void rangeExtensionSharesUniformsWithVertexClipAndReturnsToSkyBeforeTheCut() throws Exception {
        var shader = Sources.text("GroundShaderSource");
        var client = Sources.text("GroundClient");
        assertTrue(client.contains("GroundTransition.clip(e.y(),gap.clip,view())"));
        assertTrue(client.contains("GroundTransition.clip(e.y(),orbit.clip,view())"));
        assertTrue(shader.contains("smoothstep(CPNativeRange.x,CPNativeRange.y"));
        assertTrue(shader.contains("mix(fragColor.rgb,FogColor.rgb,cpNativeRangeFog)"));
        assertTrue(
                shader.contains(
                        "length(point.xz-CPNativeEye.xz)*(1.0-smoothstep(128.0,160.0,CPNativeEye.y))"));
    }
}
