package dev.starfold.ship;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.Sources;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.ground.ObserverFrame;

import org.junit.jupiter.api.Test;

import java.nio.file.*;

class HelmClientTest {
    @Test
    void helmUsesRenderedHullAndIgnoresOnlyThatHull() throws Exception {
        var s = Sources.text("HelmClient");
        assertTrue(s.contains("HelmInputClient.body()"));
        assertTrue(s.contains("HelmInputClient.facing(body)"));
        assertFalse(s.contains("Class.forName"));
        assertTrue(s.contains("body.renderPose(partial).transformPosition"));
        assertTrue(s.contains("sable$setSubLevelIgnoring(b->b==body)"));
        assertTrue(s.contains("finally{poses.sable$popPoseSupplier();}"));
        assertTrue(s.contains("if(active&&previous==null)"));
        assertTrue(s.contains("mc.options.setCameraType(previous)"));
        assertFalse(s.contains("import dev.simulated_team"));
    }

    @Test
    void mouseFlightTiltsOnlyTheStandBackDirection() throws Exception {
        assertEquals(8, HelmClient.CHASE_TILT);
        var s = Sources.text("HelmClient");
        assertTrue(
                s.contains(
                        "SpaceAttitudeClient.mouseFlight()?Vec3.directionFromRotation(CHASE_TILT,camera.getYRot())"));
        assertTrue(s.contains("mc.options.getCameraType().isFirstPerson())return null"));
        var hook = Sources.text("HelmCameraMixin");
        assertFalse(hook.contains("setRotation"));
    }

    @Test
    void displacedViewDoesNotBecomeTheChartObserver() throws Exception {
        var client = Sources.text("GroundClient");
        var shader = Sources.text("GroundShaderSource");
        assertTrue(client.contains("frame().vectorToStorage(new Point(d.x,d.y,d.z))"));
        assertTrue(client.contains("set(shader,\"CPNativeViewOffset\",viewOffset)"));
        assertTrue(shader.contains("dot(view.xyz,CPNativeWorldZ))+CPNativeViewOffset"));
        assertTrue(shader.contains("relative=point-CPNativeEye-CPNativeViewOffset"));
        for (int turn = 0; turn < 4; turn++) {
            var f = new ObserverFrame(0, 1, turn, 2048, -4096);
            var eye = new Point(20, 300, 40);
            var offset = new Point(36, -12, 48);
            var vertex = new Point(25, 302, 41);
            var relative = vertex.sub(eye.add(offset));
            var reconstructed = eye.add(f.vectorToStorage(f.vectorToClient(relative))).add(offset);
            assertEquals(vertex.x(), reconstructed.x(), 1e-9);
            assertEquals(vertex.y(), reconstructed.y(), 1e-9);
            assertEquals(vertex.z(), reconstructed.z(), 1e-9);
        }
    }

    @Test
    void navigationReadsPlayerButProjectsFromCamera() throws Exception {
        var s = Sources.text("SpaceNavigationClient");
        assertTrue(s.contains("universeEye(mc.player.getEyePosition"));
        assertTrue(s.contains("relative(frame,viewEye,"));
        assertTrue(s.contains("SpaceNavigation.read(model,frame,eye,"));
        var client = Sources.text("SpaceClient");
        var frame =
                client.substring(
                        client.indexOf("private static Point eye()"),
                        client.indexOf("private static double renderTick"));
        assertTrue(frame.contains("mc.player.getEyePosition"));
        assertFalse(frame.contains("getMainCamera"));
    }
}
