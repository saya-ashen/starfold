package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.chart.GroundSolar;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.space.SpaceView;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class SurfaceBodiesTest {
    @Test
    void skyProjectionKeepsFieldOfViewOffsetsAndNearPlaneWithoutChangingTerrain() {
        var terrain = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9, .05f, 224);
        terrain.m20(.013f).m21(-.025f);
        var original = new Matrix4f(terrain);
        var sky = SpaceView.skyProjection(terrain);
        assertEquals(original, terrain);
        assertEquals(terrain.m00(), sky.m00());
        assertEquals(terrain.m11(), sky.m11());
        assertEquals(terrain.m20(), sky.m20());
        assertEquals(terrain.m21(), sky.m21());
        assertEquals(SpaceView.SKY_NEAR, sky.m32() / (sky.m22() - 1), 1e-3);
        var moon = sky.transform(new Vector4f(20, 40, -8000, 1));
        assertTrue(Math.abs(moon.z) <= moon.w);
        assertTrue(SpaceView.visible(8000, 512));
        assertTrue(SpaceView.visible(SpaceView.FAR, 512));
    }

    @Test
    void viewBobbingNeverPushesFarBodiesPastTheSkyFarPlane() {
        var plain = new Matrix4f().perspective((float) Math.toRadians(70), 16f / 9, .05f, 224);
        // As GameRenderer.bobView: P * translate * roll * pitch.
        var bobbed =
                new Matrix4f(plain)
                        .translate(.04f, -.06f, 0)
                        .rotateZ((float) Math.toRadians(2.5))
                        .rotateX((float) Math.toRadians(1.8));
        var sky = SpaceView.skyProjection(bobbed);
        var unbobbed = SpaceView.skyProjection(plain);
        for (float x : new float[] {-60000, 0, 60000})
            for (float y : new float[] {-60000, 0, 60000}) {
                var far = new Vector4f(x, y, -.9f * SpaceView.FAR, 1);
                var clip = sky.transform(new Vector4f(far));
                assertTrue(Math.abs(clip.z) <= clip.w, "bobbed body at " + x + "," + y);
                var view = new Matrix4f().translate(.04f, -.06f, 0).rotateZ((float) Math.toRadians(2.5))
                        .rotateX((float) Math.toRadians(1.8)).transform(new Vector4f(far));
                var reference = unbobbed.transform(view);
                assertEquals(reference.z / reference.w, clip.z / clip.w, 1e-6);
            }
    }

    @Test
    void daylightWashoutHasANoonFloorAndNoAltitudeSteps() {
        assertEquals(.2, SpaceView.bodyFade(1, 0), 1e-12);
        assertEquals(1, SpaceView.bodyFade(-1, 0), 1e-12);
        assertEquals(.6, SpaceView.bodyFade(0, 0), 1e-12);
        assertEquals(1, SpaceView.bodyFade(1, 1), 1e-12);
        for (double altitude : new double[] {100, 159, 160, 161, 300, 1023, 1024, 1025}) {
            double space = dev.starfold.chart.GroundOrbit.weight(altitude);
            assertEquals(.2 + .8 * GroundSolar.stars(.05, space), SpaceView.bodyFade(.05, space));
            assertTrue(
                    Math.abs(
                                    SpaceView.bodyFade(.05, space)
                                            - SpaceView.bodyFade(
                                                    .05,
                                                    dev.starfold.chart.GroundOrbit.weight(
                                                            altitude + .001)))
                            < .00001);
        }
    }

    @Test
    void horizonOccludesIndividualSurfacePointsNotTheWholeDisc() {
        var center = new Point(0, 0, 0);
        var eye = new Point(0, 100, 0);
        assertFalse(GroundSolar.occulted(eye, new Point(8000, 101, 0), center, 100));
        assertTrue(GroundSolar.occulted(eye, new Point(8000, -100, 0), center, 100));
        assertFalse(GroundSolar.occulted(eye, new Point(0, 8000, 0), center, 100));
        assertTrue(GroundSolar.occulted(eye, new Point(0, -8000, 0), center, 100));
        assertFalse(
                GroundSolar.occulted(new Point(0, 200, 0), new Point(8000, 101, 0), center, 100));
    }

    @Test
    void chartBodiesUseTheExistingMeshBeforeTerrainWithDiscardedDepth() throws Exception {
        var sky = Sources.text("GroundCelestials");
        assertTrue(sky.contains("Stage.AFTER_SKY"));
        assertTrue(sky.contains("SpaceView.skyProjection(projection)"));
        assertTrue(sky.contains("GL11.GL_DEPTH_BUFFER_BIT"));
        assertTrue(sky.contains("setProjectionMatrix(projection,sorting)"));
        assertTrue(
                sky.indexOf("GroundStars.draw(c);", sky.indexOf("private static void drawSky"))
                        < sky.indexOf(
                                "PlanetRemoteRenderer.draw(c);",
                                sky.indexOf("private static void drawSky")));
        assertTrue(sky.contains("!RendererGuard.submerged(c.camera())"));
        var remote = Sources.text("PlanetRemoteRenderer");
        assertTrue(remote.contains("sky==(snapshot.planet()!=GroundClient.planet())"));
        assertTrue(remote.contains("groundCenter(target.center(),scale)"));
        assertTrue(remote.contains("SpaceView.renderScale("));
        var meshes = Sources.text("RemotePlanetMeshes");
        assertTrue(meshes.contains("fallback=resident;fallbackMask=mask;"));
        assertTrue(meshes.contains("mask=null;view=new PlanetRemoteView()"));
        assertTrue(remote.contains("body.fallback,body.fallbackMeshes,body.fallbackMask"));
        assertTrue(remote.contains("SpaceView.bodyFade("));
        var shader =
                Files.readString(
                        Path.of(
                                "src/main/resources/assets/starfold/shaders/core/native_ground_exterior.fsh"));
        assertTrue(shader.contains("occulted(BodyEye,bodyPosition,Occluder,OccluderRadius)"));
        assertTrue(shader.contains("occulted(bodyPosition,SunBody,ShadeCenter,ShadeRadius)"));
        assertFalse(shader.contains("FogEnd"));
        assertTrue(
                shader.contains("mix(SkyColor,surfaceColor.rgb*light*AtmosphereGrade,BodyFade)"));
    }
}
