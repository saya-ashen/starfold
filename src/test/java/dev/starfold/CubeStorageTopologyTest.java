package dev.starfold;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import dev.starfold.CubeSurfaceTopology.Face;
import dev.starfold.StripTopology.Cell;
import java.util.EnumMap;

class CubeStorageTopologyTest {
    private static EnumMap<Face,Cell> anchors() {
        var anchors=new EnumMap<Face,Cell>(Face.class);
        for(Face f:Face.values()) anchors.put(f,new Cell(30_000_000-f.ordinal()*4096,64,30_000_000));
        return anchors;
    }
    @Test void acceptedStripImplementsTheSameContractWithoutRotation() {
        Cell left=new Cell(1000,64,1000),right=new Cell(3000,64,1000);
        CellTopology strip=new StripTopology(left,right,32);
        Cell query=left.add(16,9,15);
        assertEquals(right.add(0,9,15),strip.canonical(query)); assertEquals(0,strip.turns(query));
        assertEquals(right.add(-1,9,15),strip.notification(query,left.add(15,9,15)).source());
    }
}
