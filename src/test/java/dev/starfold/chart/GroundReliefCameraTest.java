package dev.starfold.chart;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.SourceText;
import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.geometry.OrbitAttitude;
import dev.starfold.geometry.PlanetSpace;
import dev.starfold.ground.GroundTopology;
import dev.starfold.render.GroundExteriorMesh;
import dev.starfold.render.GroundSelection;
import dev.starfold.render.GroundShaderSource;
import dev.starfold.sky.GroundSolar;

import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

class GroundReliefCameraTest {
    static Vector3d ray(Quaterniondc q) {
        return q.transform(new Vector3d(0, 0, 1));
    }

    @Test
    void chartCaptureMatchesActualMinecraftCameraNotMirroredEulerAngles() throws Exception {
        var camera = new net.minecraft.client.Camera();
        var set =
                net.minecraft.client.Camera.class.getDeclaredMethod(
                        "setRotation", float.class, float.class);
        set.setAccessible(true);
        for (float yaw : new float[] {-170, -90, 0, 47, 179})
            for (float pitch : new float[] {-85, -30, 0, 60, 85}) {
                set.invoke(camera, yaw, pitch);
                var chart = GroundCamera.capture(yaw, pitch);
                assertEquals(0, ray(chart).distance(new Vector3d(camera.getLookVector())), 1e-6);
                var display = GroundView.camera(chart).transform(new Vector3d(0, 0, -1));
                assertEquals(0, display.distance(new Vector3d(camera.getLookVector())), 1e-6);
            }
    }

    @Test
    void landingPreservesDownwardRayInsteadOfReflectingItSkyward() throws Exception {
        var chart = GroundCamera.capture(0, 60);
        var old = GroundCamera.capture(0, -OrbitAttitude.pitch(chart));
        var fixed = GroundCamera.capture(GroundCamera.yaw(chart, 0), GroundCamera.pitch(chart));
        assertTrue(ray(chart).y < 0);
        assertTrue(ray(old).y > 0);
        assertEquals(0, ray(chart).distance(ray(fixed)), 1e-12);
        var out = Path.of("build/native-relief-camera");
        Files.createDirectories(out);
        Files.writeString(
                out.resolve("reproduction.json"),
                "{\"old_pitch_jump_degrees\":120,\"new_ray_error\":0,\"gpu_verified\":false,\"exact_screenshot_reproduced\":false}\n");
    }

    @Test
    void yawAndPitchRoundTripAllQuadrantsIncludingPoles() {
        for (int yaw = -180; yaw <= 180; yaw += 30)
            for (double pitch : new double[] {-89.5, -60, 0, 60, 89.5}) {
                var q = GroundCamera.capture(yaw, pitch);
                var copy = GroundCamera.capture(GroundCamera.yaw(q, yaw), GroundCamera.pitch(q));
                assertEquals(0, ray(q).distance(ray(copy)), 1e-12);
            }
    }

    @Test
    void liveMouseRaySurvivesSixFaceEpochAndGroundHandoff() {
        var model = GroundSolar.defaults();
        for (var face : Face.values())
            for (int u : new int[] {1, 1024, 2047})
                for (double pitch : new double[] {-60, 0, 60}) {
                    var pose = new GroundTopology.Pose(face, new Point(u, 159, 777));
                    var local = GroundCamera.capture(43, pitch);
                    double time = 900;
                    var universe =
                            model.at(0, time)
                                    .rotation()
                                    .mul(GroundOrbit.frame(pose.renderPose()).rotation())
                                    .mul(local);
                    var landed = GroundCamera.landing(model, pose, universe, time);
                    assertEquals(0, ray(local).distance(ray(landed)), 1e-10);
                }
    }

    @Test
    void rollRemovalKeepsForwardRayNotAnOldAcknowledgedAim() {
        var model = GroundSolar.defaults();
        var pose = new GroundTopology.Pose(Face.SOUTH, new Point(700, 159, 800));
        var live = GroundCamera.capture(30, 65).rotateZ(2.3);
        var world =
                model.at(0, 100)
                        .rotation()
                        .mul(GroundOrbit.frame(pose.renderPose()).rotation())
                        .mul(live);
        assertEquals(
                0, ray(live).distance(ray(GroundCamera.landing(model, pose, world, 100))), 1e-10);
    }

    @Test
    void oppositeTerrainSlopesCannotShareRadialLighting() {
        Point a = new Point(0, 0, 0), b = new Point(0, 0, 16), c = new Point(16, 8, 16);
        var slope = PlanetSpace.unit(b.sub(a).cross(c.sub(a)));
        var radial = new Point(0, 1, 0);
        assertTrue(Math.abs(slope.dot(radial) - 1) > .05);
        var sun = PlanetSpace.unit(new Point(-1, 1, 0));
        assertTrue(slope.dot(sun) > radial.dot(sun));
    }

    @Test
    void coverAndBakedShellUseTheSamePlanetSpaceTriangleNormal() {
        for (var face : Face.values()) {
            Point a = GroundExteriorMesh.position(face, 700, 800, 0),
                    b = GroundExteriorMesh.position(face, 700, 816, 8),
                    c = GroundExteriorMesh.position(face, 716, 816, 16);
            var expected = PlanetSpace.unit(b.sub(a).cross(c.sub(a)));
            if (expected.dot(a) < 0) expected = expected.scale(-1);
            var dx = b.sub(a).scale(.4).add(c.sub(a).scale(.7));
            var dy = b.sub(a).scale(.8).add(c.sub(a).scale(.2));
            var derived = PlanetSpace.unit(dx.cross(dy));
            if (derived.dot(a) < 0) derived = derived.scale(-1);
            assertEquals(0, expected.sub(derived).length(), 1e-12);
        }
    }

    @Test
    void reliefDerivativesAreBeforeDiscardAndAreNotAtlasReconstruction() {
        String shader =
                GroundShaderSource.patch(
                        "rendertype_solid",
                        false,
                        "#version 150\nuniform sampler2D Sampler0;in vec2 texCoord0;out vec4 fragColor;void main(){fragColor=texture(Sampler0,texCoord0);}");
        assertTrue(
                shader.indexOf("vec3 coverNormal=cpNativeCoverNormal();")
                        < shader.indexOf("if(cpNativeFacing<0.0)discard"));
        assertTrue(
                shader.contains("cross(dFdx(cpNativeSolarPosition),dFdy(cpNativeSolarPosition))"));
        assertTrue(shader.contains("cpNativeSea>0.999"));
        assertTrue(shader.contains("dot(slope,radial)<0.0"));
        assertFalse(GroundShaderSource.TEXTURE.contains("dFdx"));
    }

    @Test
    void partialFineSelectionRetainsCoverUntilItsConnectorsArePublished() {
        BitSet old = new BitSet(), available = new BitSet();
        available.set(4);
        assertFalse(GroundSelection.detail(4, old, new BitSet()));
        BitSet completed = (BitSet) available.clone();
        assertTrue(
                GroundSelection.detail(
                        4, GroundSelection.retain(completed, available), new BitSet()));
    }

    @Test
    void vanishedFineTileRevealsFallbackAndDoesNotMutateRequestedSelection() {
        BitSet requested = new BitSet(), available = new BitSet();
        requested.set(1);
        requested.set(2);
        available.set(2);
        BitSet selected = GroundSelection.retain(requested, available);
        assertFalse(selected.get(1));
        assertTrue(selected.get(2));
        assertTrue(requested.get(1));
        assertFalse(GroundSelection.detail(1, selected, new BitSet()));
    }

    @Test
    void nativeAndDerivedDetailsAreMutuallyExclusive() {
        BitSet selected = new BitSet(), nativeReady = new BitSet();
        selected.set(9);
        nativeReady.set(9);
        assertFalse(GroundSelection.detail(9, selected, nativeReady));
        nativeReady.clear(9);
        assertTrue(GroundSelection.detail(9, selected, nativeReady));
    }

    @Test
    void runtimeCommitsBeforeTerrainAndKeepsInProgressConnectors() throws Exception {
        var s = Sources.text("GroundRenderer");
        assertTrue(
                s.contains(
                        "cpEvent.getStage()!=net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_SKY"));
        assertTrue(s.contains("prepareDetails();if(selectionActive)updateExclusions()"));
        assertTrue(s.contains("if(stitchJob==null&&"));
        assertTrue(s.contains("pendingStitches=stitchJob.finish()"));
        assertTrue(s.contains("GroundOwnership.canCommit(pendingJob.nextCover"));
        assertTrue(s.contains("ownership.owner(key)==GroundOwnership.Owner.DETAIL"));
        assertTrue(s.contains("GroundClient.stitch(mesh==stitches[face])"));
    }

    @Test
    void flightRuntimeUsesConsistentConversionsAndDoesNotReplayMouseAim() throws Exception {
        SourceText client = Sources.text("GroundFlightClient"),
                server = Sources.text("GroundFlightServer");
        assertTrue(server.contains("GroundCamera.capture(player.getYRot(),player.getXRot())"));
        assertTrue(server.contains("GroundCamera.pitch(aim)"));
        assertTrue(client.contains("if(!handoff&&!underWay)aim="));
        assertTrue(
                client.contains(
                        "GroundCamera.landing(model,state.planet(),state.local(),aim,state.tick())"));
        assertTrue(client.contains("p.xRotO=p.getXRot()"));
        assertFalse(client.contains("(float)-OrbitAttitude.pitch"));
    }
}
