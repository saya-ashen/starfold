package dev.starfold;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeAirstairGeometryTest {
    private NativeAirstairGeometry.Flight flight(double ground){return NativeAirstairGeometry.flight(new Vector3d(0,10,0),new Vector3d(0,0,1),new Vector3d(0,-1,0),1,(x,z)->ground);}
    @Test void flatGroundGivesHalfBlockTreads(){var f=flight(4);assertNotNull(f);assertEquals(12,f.steps());assertEquals(6,f.drop());assertEquals(9.5,f.height(0));assertEquals(4.0125,f.height(11));}
    @Test void firstTreadStartsAtSillOuterEdge(){var f=flight(9);var boxes=f.boxes();assertEquals(0,boxes.stream().mapToDouble(b->b.minZ).min().orElseThrow(),1e-8);assertEquals(1,boxes.stream().mapToDouble(b->b.maxZ).max().orElseThrow(),1e-8);assertEquals(-.5,boxes.stream().mapToDouble(b->b.minX).min().orElseThrow(),1e-8);}
    @Test void supportsEveryFacingAndTiltedHull(){for(var out:new Vector3d[]{new Vector3d(1,.18,0),new Vector3d(-1,0,0),new Vector3d(0,0,-1),new Vector3d(1,.18,1)}){var f=NativeAirstairGeometry.flight(new Vector3d(0,10,0),out,new Vector3d(0,-1,0),1,(x,z)->4);assertNotNull(f);assertEquals(0,f.out().y);assertEquals(1,f.out().length(),1e-8);for(var box:f.boxes())assertTrue(box.maxY<=10&&box.maxY>=4);}}
    @Test void rejectsNoGroundAndDeepDrop(){assertNull(flight(Double.NaN));assertNull(flight(-4));assertNotNull(flight(0));assertNull(flight(-.01));}
    @Test void rejectsDoorGroundAtOrAboveSill(){assertNull(flight(10));assertNull(flight(11));}
    @Test void wallAndRisingSlopeStayFolded(){for(var terrain:java.util.List.<NativeAirstairGeometry.Terrain>of((x,z)->z>=2?10:4,(x,z)->8+z)){assertNull(NativeAirstairGeometry.flight(new Vector3d(0,10,0),new Vector3d(0,0,1),new Vector3d(0,-1,0),1,terrain));}}
    @Test void subMillimetreSettlingAcrossHalfTreadBoundaryDoesNotRetract(){for(double drop:new double[]{1,1.0001,1.0005,1.001,1.002,3,3.0001,3.0005,3.001,3.002}){var f=flight(10-drop);assertNotNull(f,"drop="+drop);assertEquals(drop,f.drop(),1e-8);}}
    @Test void fractionalBottomRestsOnGround(){var f=flight(8.7);assertEquals(3,f.steps());assertEquals(8.7125,f.height(2),1e-8);assertTrue(f.height(1)-f.height(2)<.5);}
    @Test void widthTwoIsOneFlight(){var f=NativeAirstairGeometry.flight(new Vector3d(0,10,0),new Vector3d(0,0,1),new Vector3d(0,-1,0),2,(x,z)->4);assertEquals(2,f.width());assertEquals(-1,f.boxes().stream().mapToDouble(b->b.minX).min().orElseThrow(),1e-8);assertEquals(1,f.boxes().stream().mapToDouble(b->b.maxX).max().orElseThrow(),1e-8);assertEquals(2,NativeAirstairGeometry.pairedWidth(0,true));assertEquals(0,NativeAirstairGeometry.pairedWidth(1,false));assertEquals(1,NativeAirstairGeometry.pairedWidth(0,false));assertEquals(2,NativeAirstairGeometry.pairedWidth(2,true));}
    @Test void nothingCanStandUnderATread(){for(double ground:new double[]{4,8.7,9}){var f=flight(ground);for(var box:f.boxes())assertTrue(box.maxY==10||Math.abs(box.minY-ground)<1e-8,box.toString());}}
    @Test void hingeBridgePrecedesTheSableHullStep(){var f=flight(4);assertTrue(f.boxes().stream().anyMatch(b->b.maxY==10&&b.minZ==0&&b.maxZ==.125));}
    @Test void noZeroGravityOrVerticalFacing(){assertNull(NativeAirstairGeometry.flight(new Vector3d(),new Vector3d(0,1,0),new Vector3d(0,-1,0),1,(x,z)->-1));assertNull(NativeAirstairGeometry.flight(new Vector3d(),new Vector3d(1,0,0),new Vector3d(),1,(x,z)->-1));}
}
