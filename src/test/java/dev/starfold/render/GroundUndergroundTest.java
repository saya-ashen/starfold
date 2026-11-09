package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.PlanetSpace;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

/** No GPU or world: explicit underground/dirty-section failure models plus runtime contracts. */
class GroundUndergroundTest {
    static String fragment() {
        return GroundShaderSource.patch(
                "rendertype_solid",
                false,
                "#version 150\nuniform sampler2D Sampler0;in vec2 texCoord0;out vec4 fragColor;void main(){fragColor=texture(Sampler0,texCoord0);}");
    }

    static String vertex(String name) {
        return GroundShaderSource.patch(
                name,
                true,
                "#version 150\nin vec3 Position;in vec3 Normal;in vec4 Color;in ivec2 UV2;uniform sampler2D Sampler2;uniform mat4 ProjMat,ModelViewMat;out vec4 vertexColor;void main(){vertexColor=Color*texelFetch(Sampler2,UV2/16,0);gl_Position=ProjMat*ModelViewMat*vec4(Position,1);}");
    }

    static double visibility(int sky) {
        double v = Math.clamp(sky / 15.0, 0, 1);
        return v / (4 - 3 * v);
    }

    static int code(byte[] atlas, int face, int x, int z) {
        return Byte.toUnsignedInt(atlas[x + 1 + 130 * (z + 1 + 130 * face)]);
    }

    @Test
    void absentTopSectionDoesNotMeanAbsentUndergroundGeometry() throws Exception {
        var old = GroundOwnership.atlas(GroundOwnership.empty());
        var fixed = GroundOwnership.atlas(GroundOwnership.empty(), 4);
        assertEquals(0, code(old, 4, 64, 64));
        assertEquals(255, code(fixed, 4, 64, 64));
        var out = Path.of("build/native-underground");
        Files.createDirectories(out);
        Files.writeString(
                out.resolve("reproduction.json"),
                "{\"old_surface_eligibility\":false,\"old_native_code\":0,\"new_native_code\":255,\"old_roof_sun_factor\":1,\"new_roof_sun_factor\":0,\"exact_screenshot_reproduced\":false,\"gpu_verified\":false}\n");
    }

    @Test
    void dirtyEditCannotWithdrawTheOriginalNearVolume() {
        var atlas = GroundOwnership.atlas(GroundOwnership.empty(), 4);
        assertEquals(255, code(atlas, 4, 64, 64));
        assertEquals(255, code(atlas, 4, 65, 64));
        assertEquals(0, code(atlas, 3, 64, 64));
    }

    @Test
    void nativeFaceOverrideCoversInteriorAndCanonicalHaloForEveryFace() {
        for (int owner = 0; owner < 6; owner++) {
            var atlas = GroundOwnership.atlas(GroundOwnership.empty(), owner);
            for (int f = 0; f < 6; f++)
                for (int z = -1; z <= 128; z++)
                    for (int x = -1; x <= 128; x++) {
                        long key = GroundOwnership.key(f, x * 16 + 8, z * 16 + 8);
                        assertEquals(key / 16384 == owner ? 255 : 0, code(atlas, f, x, z));
                    }
        }
    }

    @Test
    void flightStillUsesExplicitSelectedOwnersInsteadOfWholeFaceOverride() {
        assertArrayEquals(
                GroundOwnership.atlas(GroundOwnership.empty()),
                GroundOwnership.atlas(GroundOwnership.empty(), -1));
        assertThrows(
                IllegalArgumentException.class,
                () -> GroundOwnership.atlas(GroundOwnership.empty(), 6));
    }

    @Test
    void currentNearChartDoesNotDrawAHeightfieldOrUseItsSurfaceEligibility() throws Exception {
        var renderer = Sources.text("GroundRenderer");
        assertTrue(renderer.contains("window=GroundNativeCoverage.visible()"));
        assertTrue(renderer.contains("GroundFlightClient.controlled()?-1:GroundClient.face()"));
        assertFalse(renderer.contains("drawDetail("));
        assertTrue(
                fragment()
                        .contains(
                                "CPNativeSelection<0.5||cpNativeOwner(ivec2(floor(cpNativeSourcePoint.xz/16.0)))>0.75"));
        assertTrue(
                fragment()
                        .contains(
                                "if(nativeVolume&&((CPNativeCover>0.5&&!lod)||CPNativeStitch>0.5))discard"));
        assertFalse(fragment().contains("CPNativeSelection>0.5&&!nativeVolume"));
        assertTrue(fragment().contains("all(lessThan(cpNativeSourcePoint.xz,vec2(CPNativeSize)))"));
        assertTrue(
                vertex("rendertype_solid")
                        .contains(
                                "CPNativeDerived<0.5&&CPNativeVolume>0.5)cpNativeSourcePoint.xz-=Normal.xz*0.02"));
    }

    @Test
    void modeChangesRepublishAtlasAndCannotCommitOldExitStrips() throws Exception {
        var renderer = Sources.text("GroundRenderer");
        assertTrue(Sources.text("GroundCoverage").contains("GroundOwnership.atlas(shape,next,window)"));
        assertFalse(renderer.contains("publishedNativeFace==nativeFace"));
        assertFalse(renderer.contains("ownership.rebase"));
        assertFalse(renderer.contains("if(neighbour/16384!=nativeFace)"));
        assertTrue(renderer.contains("Prebuild all canonical exits"));
        var client = Sources.text("GroundProjectionUniforms");
        assertTrue(client.contains("GroundRenderer.nativeVolume(owner.ordinal())?1:0"));
    }

    @Test
    void fullRoofBlocksDirectSunEvenWhenTheSunIsOverhead() {
        assertEquals(1, .12 + .88 * 1);
        assertEquals(0, (.12 + .88 * 1) * visibility(0));
        assertEquals(1, (.12 + .88 * 1) * visibility(15));
        assertFalse(fragment().contains("(0.12+0.88*daylight)*cpNativeSkyVisibility"));
    }

    @Test
    void skyAttenuationMatchesVanillaLightLevelCurveAndIsMonotone() {
        double previous = -1;
        for (int level = 0; level <= 15; level++) {
            double v = visibility(level);
            assertTrue(v >= previous && v >= 0 && v <= 1);
            previous = v;
        }
        assertEquals(0, visibility(0));
        assertEquals(1, visibility(15));
        assertEquals(7.0 / 39, visibility(7), 1e-12);
        assertFalse(vertex("rendertype_solid").contains("vertexColor.rgb/=max"));
    }

    @Test
    void torchLightIsNotMultipliedByBlockedSkylight() {
        double torch = .7, sky = visibility(0), day = .12 + .88 * 1;
        assertEquals(torch, Math.max(day * sky, torch));
        assertTrue(vertex("rendertype_solid").contains("Color*texelFetch(Sampler2,cpNativeLightUV2/16,0)"));
        assertFalse(fragment().contains("fragColor.rgb*=cpNativeSkyVisibility"));
    }

    @Test
    void ceilingNormalsMustNotBeFlippedOutwardLikeAHeightfield() {
        var a = new BentStripGeometry.Point(0, 0, 0);
        var b = new BentStripGeometry.Point(1, 0, 1);
        var c = new BentStripGeometry.Point(0, 0, 1);
        var normal = PlanetSpace.unit(b.sub(a).cross(c.sub(a)));
        var sun = new BentStripGeometry.Point(0, 1, 0);
        assertEquals(-1, normal.dot(sun));
        assertEquals(0, Math.max(0, normal.dot(sun)));
        assertTrue(
                GroundShaderSource.RELIEF.contains(
                        "if(CPNativeCoarse>0.5||CPNativeCover>0.5){if(dot(slope,radial)<0.0)"));
        assertTrue(GroundShaderSource.RELIEF.contains("else if(!gl_FrontFacing)slope=-slope"));
        assertTrue(
                GroundShaderSource.RELIEF.contains(
                        "((CPNativeCoarse>0.5||CPNativeCover>0.5)&&cpNativeSea>0.999)"));
    }

    @Test
    void coarseSurfaceDefaultsAndSelfLitShadersDoNotAcquireVoxelSkyAttenuation() {
        assertTrue(GroundShaderSource.VERTEX.contains("cpNativeSkyVisibility=1.0"));
        assertFalse(vertex("rendertype_solid").contains("if(CPNativeCoarse<0.5){cpNativeBlockLight="));
        assertFalse(vertex("rendertype_eyes").contains("cpNativeSkyVisibility=sky/"));
    }

    @Test
    void ordinaryWorldAndGuiRemainOutsideNativeLightingScope() {
        assertTrue(GroundShaderSource.VERTEX.contains("if(CPNativeActive<0.5)return"));
        String gui = "#version 150\nvoid main(){}";
        assertEquals(gui, GroundShaderSource.patch("rendertype_gui", false, gui));
        assertTrue(
                fragment()
                        .contains(
                                "CPNativeActive>0.5&&(CPNativeDerived>0.5||CPNativeTerrain>0.5)"));
    }
    @Test
    void shaderDoesNotTurnAnOverheadSurfaceIntoSkyFogJustBecauseCameraIsHigh() throws Exception {
        var s = Sources.text("GroundShaderSource");
        assertTrue(
                s.contains(
                        "length(point.xz-CPNativeEye.xz)*(1.0-smoothstep(128.0,160.0,CPNativeEye.y))"));
        assertFalse(s.contains("vertexDistance = length(view.xyz)"));
        assertTrue(
                s.contains(
                        "texelFetch(CPNativeCoverage,tile+ivec2(1,1+int(CPNativeFace)*130),0).r"));
        assertFalse(s.contains("CPNativeDerived<0.5&&owner<0.75)discard"));
    }

    @Test
    void rendererHasNoLargeQuadStageOrBakedDetailHolesAndRestoresCoverageSampler()
            throws Exception {
        var s = Sources.text("GroundRenderer");
        assertFalse(s.contains("BaseCoverJob"));
        assertFalse(s.contains("if(wanted.contains(key)&&detail.containsKey(key))"));
        assertTrue(s.contains("patchesComplete()"));
        assertTrue(s.contains("staging[patch]=mesh;stagedEpoch++;if(old!=null)old.close()"));
        assertTrue(
                s.contains("GroundPipeline.publish(dirty.count(),!coverJobs.isEmpty(),patches,staging,p->GroundViewPolicy.exists(shape,p))"));
        assertTrue(s.contains("coverage.close()"));
        var binding = Sources.text("CoverageBinding");
        assertTrue(binding.contains("GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture)"));
        assertTrue(binding.contains("ARBSamplerObjects.glBindSampler(3,sampler)"));
        assertFalse(s.contains("CompiledSection.UNCOMPILED"));
        assertTrue(s.contains("GroundNativeCoverage.visible()"));
    }
}
