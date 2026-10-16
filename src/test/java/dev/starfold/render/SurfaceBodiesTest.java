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
        assertEquals(.05f, sky.m32() / (sky.m22() - 1), 1e-7);
        var moon = sky.transform(new Vector4f(20, 40, -8000, 1));
        assertTrue(Math.abs(moon.z) <= moon.w);
        assertTrue(SpaceView.visible(8000, 512));
        assertFalse(SpaceView.visible(SpaceView.FAR, 512));
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
        assertTrue(sky.contains("FogType.NONE"));
        var remote = Sources.text("PlanetRemoteRenderer");
        assertTrue(remote.contains("sky==(snapshot.planet()!=GroundClient.planet())"));
        assertTrue(remote.contains("if(remote&&!SpaceView.visible("));
        assertTrue(remote.contains("fallback=resident;fallbackMask=mask;"));
        assertTrue(remote.contains("mask=null;view=new PlanetRemoteView()"));
        assertTrue(remote.contains("body.fallback,body.fallbackMeshes,body.fallbackMask"));
        assertTrue(remote.contains("SpaceView.bodyFade("));
        var shader =
                Files.readString(
                        Path.of(
                                "src/main/resources/assets/starfold/shaders/core/native_ground_exterior.fsh"));
        assertTrue(shader.contains("occulted(BodyEye,bodyPosition,Occluder,OccluderRadius)"));
        assertTrue(shader.contains("occulted(bodyPosition,SunBody,ShadeCenter,ShadeRadius)"));
        assertFalse(shader.contains("FogEnd"));
        assertTrue(shader.contains("mix(SkyColor,surfaceColor.rgb*light,BodyFade)"));
    }
}
