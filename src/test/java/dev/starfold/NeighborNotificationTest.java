package dev.starfold;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.starfold.StripTopology.Cell;

class NeighborNotificationTest {
    private final Cell left=new Cell(10000,64,20000),right=new Cell(-20000,96,-30000);
    private final StripTopology topology=new StripTopology(left,right);
    @Test void leftToRightReceivesRealTargetAndNearbyVirtualSource() {
        var pair=topology.notification(left.add(16,9,2),left.add(15,9,2));
        assertEquals(right.add(0,9,2),pair.target());
        assertEquals(right.add(-1,9,2),pair.source());
        assertEquals(left.add(15,9,2),topology.canonical(pair.source()));
    }
    @Test void rightToLeftPreservesSourceDirectionAndHeightDespiteDistantOrigins() {
        var pair=topology.notification(right.add(-1,9,0),right.add(0,9,0));
        assertEquals(left.add(15,9,0),pair.target());
        assertEquals(left.add(16,9,0),pair.source());
        assertEquals(right.add(0,9,0),topology.canonical(pair.source()));
    }
    @Test void realReceiversAndUnrelatedWorldCoordinatesRemainUnchanged() {
        for(var target:new Cell[]{left.add(8,9,0),right.add(8,9,0),new Cell(110,81,0)}) {
            var source=target.add(0,1,0); var pair=topology.notification(target,source);
            assertEquals(target,pair.target()); assertEquals(source,pair.source());
        }
    }
    @Test void allSixRelativeDirectionsArePreservedAndRealDeliveryIsIdempotent() {
        for(int[] delta:new int[][]{{-1,0,0},{1,0,0},{0,-1,0},{0,1,0},{0,0,-1},{0,0,1}}) {
            var target=left.add(16,9,2); var source=target.add(delta[0],delta[1],delta[2]);
            var pair=topology.notification(target,source);
            assertEquals(pair.target().add(delta[0],delta[1],delta[2]),pair.source());
            assertEquals(pair,topology.notification(pair.target(),pair.source()));
        }
    }
}
