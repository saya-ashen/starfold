package dev.starfold.atlas;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapCameraTest {
    @Test void perspectiveAndRaysRoundTripAtAllOrbitAnglesAndLargeWorldOffsets() {
        var camera = new MapCamera(); camera.viewport(10, 52, 620, 360);
        var focus = new Vector3d(9e8, -7e8, 8e8); camera.focus(focus);
        for (double yaw : new double[]{0, .7, 2.1, -3}) for (double pitch : new double[]{-Math.PI/2, -.8, 0, 1.1, Math.PI/2}) {
            camera.pose(yaw, pitch, 4);
            for (var offset : new Vector3d[]{new Vector3d(), new Vector3d(.2, -.3, .4), new Vector3d(-.7, .1, -.2)}) {
                var point = new Vector3d(focus).add(offset);
                var screen = camera.project(point); var ray = camera.ray(screen.x(), screen.y());
                assertTrue(screen.depth() > 0); assertEquals(1, ray.direction().length(), 1e-12);
                var delta = new Vector3d(point).sub(ray.origin());
                var restored = new Vector3d(ray.direction()).mul(delta.dot(ray.direction())).add(ray.origin());
                assertEquals(0, restored.distance(point), 3e-7);
            }
        }
    }
    @Test void spherePickingRoundTripsTheVisibleSurfaceAndRejectsMissesAndRearHits() {
        var camera = new MapCamera(); camera.viewport(0, 52, 600, 400);
        for (var p : new Vector3d[]{new Vector3d(0, 0, 1), new Vector3d(.4, .2, 1).normalize(), new Vector3d(-.3, -.4, 1).normalize()}) {
            var screen = camera.project(p); var hit = camera.ray(screen.x(), screen.y()).sphere(new Vector3d(), 1);
            assertNotNull(hit); assertEquals(0, hit.distance(p), 1e-12);
        }
        assertNull(camera.ray(0, 52).sphere(new Vector3d(), 1));
        assertNull(new MapCamera.Ray(new Vector3d(0, 0, 3), new Vector3d(0, 0, 1)).sphere(new Vector3d(), 1));
        assertEquals(new Vector3d(1, 0, 0), new MapCamera.Ray(new Vector3d(), new Vector3d(1, 0, 0)).sphere(new Vector3d(), 1));
    }
    @Test void matrixProjectionAgreesWithScreenProjectionAndFacingWorksAtPoles() {
        var camera = new MapCamera(); camera.viewport(12, 52, 600, 400);
        camera.face(new Vector3d(1, 2, -3)); camera.pose(.7, .3, 4);
        var point = new Vector3d(.3, -.2, .8);
        var screen = camera.project(point);
        var matrix = camera.projection(.01, 100).mul(camera.view());
        var ndc = matrix.transformProject(new org.joml.Vector3f((float) point.x, (float) point.y, (float) point.z));
        assertEquals(screen.x(), 12 + (ndc.x + 1) * 300, 1e-4);
        assertEquals(screen.y(), 52 + (1 - ndc.y) * 200, 1e-4);
        for (var axis : new Vector3d[]{new Vector3d(0, 1, 0), new Vector3d(0, -1, 0), new Vector3d(1, 0, 0)}) {
            camera.face(axis);
            var p = camera.project(axis);
            assertEquals(312, p.x(), 1e-9); assertEquals(252, p.y(), 1e-9);
            assertTrue(camera.view().isFinite());
        }
    }
    @Test void zoomIsBoundedAndSmoothedAndRotationCoastsWithoutFrameRateDependence() {
        var a = new MapCamera(); var b = new MapCamera();
        a.drag(12, -6); b.drag(12, -6);
        a.update(.1, false); for (int i = 0; i < 10; i++) b.update(.01, false);
        assertEquals(0, a.eye().distance(b.eye()), 1e-12);
        var before = a.eye(); a.update(.1, false); assertTrue(before.distance(a.eye()) > 0);
        a.zoom(100, 2.35, 12); assertEquals(2.35, a.desiredDistance());
        a.update(.05, true); assertTrue(a.distance() > 2.35 && a.distance() < 3.5);
        a.zoom(-100, 2.35, 12); assertEquals(12, a.desiredDistance());
        a.stop(); before = a.eye(); a.distance(a.distance()); a.update(.1, false);
        assertEquals(0, before.distance(a.eye()), 1e-12);
    }
}
