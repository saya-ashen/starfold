package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;

import dev.starfold.chart.PlanetShape;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.lod.VoxelLodPolicy;

import org.junit.jupiter.api.Test;

import java.util.*;

class GroundLodSelectionTest {
    @Test void refreshTracksSpatialProjectionDataAndZoomExpiry() {
        var refresh = new GroundLodSelection.Refresh();
        var pose = new PlanetShape.Pose(Face.TOP, new Point(8, 2, 8));
        assertTrue(refresh.update(pose,70,1080,0,1,0,0,0));
        assertFalse(refresh.update(pose,70,1080,0,0,1,0,1));
        assertFalse(refresh.update(new PlanetShape.Pose(Face.TOP,new Point(11.9,3.9,8)),70,1080,0,1,0,0,2));
        assertTrue(refresh.update(new PlanetShape.Pose(Face.TOP,new Point(12,2,8)),70,1080,0,1,0,0,3));
        assertTrue(refresh.update(pose,70,1080,1,1,0,0,4));
        assertTrue(refresh.update(pose,70,720,1,1,0,0,5));
        assertTrue(refresh.update(pose,10,720,1,1,0,0,6));
        assertTrue(refresh.update(pose,10,720,1,0,1,0,7));
        assertTrue(refresh.update(pose,70,720,1,0,1,0,8));
        assertFalse(refresh.update(pose,70,720,1,0,1,0,GroundLodSelection.ZOOM_HOLD_NS+7));
        assertTrue(refresh.update(pose,70,720,1,0,1,0,GroundLodSelection.ZOOM_HOLD_NS+8));
        assertFalse(refresh.update(pose,70,720,1,0,1,0,GroundLodSelection.ZOOM_HOLD_NS+9));
        assertTrue(refresh.update(new PlanetShape.Pose(Face.BOTTOM,pose.point()),70,720,1,0,1,0,GroundLodSelection.ZOOM_HOLD_NS+10));
    }

    @Test
    void editedCoverageRebuildsBeforeOptionalNearerProceduralDetail() {
        var queue=new PriorityQueue<>(GroundLodSelection.REBUILD_ORDER);
        queue.add(new GroundLodSelection.Tile(1,1));
        queue.add(new GroundLodSelection.Tile(2,500,true));
        queue.add(new GroundLodSelection.Tile(3,100,true));
        assertEquals(3,queue.remove().key());
        assertEquals(2,queue.remove().key());
        assertEquals(1,queue.remove().key());
    }

    @Test void spyglassLevelsSurviveThirtySecondsAfterZoomOut() {
        var selection=new GroundLodSelection();
        selection.projection(10,720);
        int fine=selection.level(12,1000,0);
        assertEquals(1,fine);
        selection.projection(70,720);
        assertEquals(fine,selection.level(12,1000,GroundLodSelection.ZOOM_HOLD_NS-1));
        assertTrue(selection.level(12,1000,GroundLodSelection.ZOOM_HOLD_NS)>fine);
    }

    @Test void idleZoomHoldsFineMeshesFromZoomOutInsteadOfLastSelection() {
        var selection = new GroundLodSelection();
        selection.projection(10,720,0);
        int fine = selection.level(12,1000,0);
        long idle = GroundLodSelection.ZOOM_HOLD_NS * 3;
        selection.projection(70,720,idle);
        assertEquals(fine,selection.level(12,1000,idle + GroundLodSelection.ZOOM_HOLD_NS - 1));
        assertTrue(selection.level(12,1000,idle + GroundLodSelection.ZOOM_HOLD_NS) > fine);
    }

    @Test
    void distanceBandsIncludeAltitudeAndHysteresis() {
        for(double fov:new double[]{30,70,110}) for(int height:new int[]{720,1080,2160})
            for(double distance:new double[]{100,450,1000,3000,9000,30000}) {
                int step=VoxelLodPolicy.screenStep(distance,-1,fov,height);
                if(step>1) assertTrue(VoxelLodPolicy.pixels(step,distance,fov,height)<=1.25);
                if(step<64 && step>1) assertTrue(VoxelLodPolicy.pixels(step*2,distance,fov,height)>1.25);
            }
        assertTrue(VoxelLodPolicy.screenStep(1000,-1,30,720)<VoxelLodPolicy.screenStep(1000,-1,110,720));
        assertTrue(VoxelLodPolicy.screenStep(1000,-1,70,2160)<VoxelLodPolicy.screenStep(1000,-1,70,720));
        double focal=720/(2*Math.tan(Math.toRadians(70)/2));
        assertEquals(2,VoxelLodPolicy.screenStep(focal*2/1.4,2,70,720));
        assertEquals(1,VoxelLodPolicy.screenStep(focal*2/1.6,2,70,720));
        assertEquals(1,VoxelLodPolicy.screenStep(focal*2/1.2,1,70,720));
        assertEquals(2,VoxelLodPolicy.screenStep(focal*2/1.0,1,70,720));
        var shape = new PlanetShape(2048);
        var f = Face.TOP;
        var eye = shape.position(new PlanetShape.Pose(f, new Point(1032, 300, 1032)));
        assertEquals(
                1, VoxelLodPolicy.step(VoxelLodPolicy.distance(shape, eye, f, 1024, 1024, 0), -1));
    }

    @Test
    void incrementalFloodReachesAllFacesSeamsAndBottomCorners() {
        for (int size : new int[] {416, 2048})
            for (var face : Face.values()) {
                var shape = new PlanetShape(size);
                var pose = new PlanetShape.Pose(face, new Point(8, 2, 8));
                var eye = shape.position(pose);
                var selection = new GroundLodSelection();
                // Below orbit the renderer culls by the sphere horizon.
                selection.visible(
                        k ->
                                VoxelLodPolicy.horizonVisible(
                                        shape,
                                        eye,
                                        Face.values()[(int) (k / 16384)],
                                        (int) (k % 128) * 16,
                                        (int) (k / 128 % 128) * 16));
                assertTrue(selection.move(shape, pose, eye, k -> 0));
                var selected = new HashMap<Long, Integer>();
                int calls = 0;
                do {
                    int before = selected.size();
                    selection.step(
                            shape,
                            Long.MAX_VALUE,
                            () -> 0,
                            k -> 0,
                            (tile, step) -> selected.put(tile.key(), step));
                    assertTrue(selected.size() - before <= 128);
                    assertTrue(++calls < 1000);
                } while (!selection.done());
                long key = GroundOwnership.key(shape, face.ordinal(), 0, 0);
                assertEquals(1, selected.get(key));
                for (long neighbour : GroundStreaming.neighbours(shape, key))
                    assertEquals(1, selected.get(neighbour), face + " " + neighbour);
                assertTrue(selected.keySet().stream().map(k -> k / 16384).distinct().count() >= 3);
                assertTrue(selected.size()<6*shape.chunks()*shape.chunks(),"hidden hemisphere stays coarse");
                assertFalse(selection.move(shape, pose, eye, k -> 0));
            }
    }

    @Test
    void admissionAndFadeAreBounded() {
        assertFalse(VoxelLodPolicy.admits(VoxelLodPolicy.RESIDENT_BYTES, 1, 0));
        assertFalse(VoxelLodPolicy.admits(0, VoxelLodPolicy.TILE_BYTES + 1, 0));
        assertFalse(VoxelLodPolicy.admits(0, 1, VoxelLodPolicy.RESIDENT_TILES));
        assertTrue(VoxelLodPolicy.admits(0, VoxelLodPolicy.TILE_BYTES, 0));
        assertEquals(0, VoxelLodPolicy.fade(100, 100));
        assertEquals(.5f, VoxelLodPolicy.fade(100, 250_000_100));
        assertEquals(1, VoxelLodPolicy.fade(100, 900_000_100));
    }
}
