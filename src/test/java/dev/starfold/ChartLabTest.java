package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.ChartLabSpace.*;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChartLabTest {
    private static Pose pose(double x,double y,double z) { return new Pose(0,new Point(x,y,z)); }
    private static void close(Point a,Point b) { assertEquals(0,a.sub(b).length(),1e-8); }
    @Test void everyStoredCellHasExactlyOneTextureAndSaveIndex() {
        for(int i=0;i<ChartLabSpace.VOLUME;i++) assertEquals(i,ChartLabSpace.index(ChartLabSpace.cell(i)));
        assertEquals(24576,ChartLabSpace.VOLUME);
    }
    @Test void everyOrdinarySeamHasAnInverseOwnerAndRigidSharedFace() {
        for(int s=0;s<3;s++) for(int z=4;z<32;z++) for(int y=-2;y<6;y++) {
            assertEquals(new Cell(Math.floorMod(s-1,3),z,y,0),ChartLabSpace.owner(s,-1,y,z));
            assertEquals(new Cell(s,0,y,z),ChartLabSpace.owner(s-1,z,y,-1));
            for(double k:new double[]{0,.5,1}) close(new Point(z+k,y,0),ChartLabSpace.rotate(new Point(0,y,z+k),-1));
        }
    }
    @Test void chartTransformsNeverShearScaleOrReflectOrdinaryCubes() {
        var x=new Point(1,0,0); var y=new Point(0,1,0); var z=new Point(0,0,1);
        for(int t=-9;t<=9;t++) { var a=ChartLabSpace.rotate(x,t); var b=ChartLabSpace.rotate(y,t); var c=ChartLabSpace.rotate(z,t);
            assertEquals(1,a.length()); assertEquals(0,a.dot(b)); assertEquals(0,a.dot(c)); close(a.cross(b),c);
            close(new Point(.3,2.7,11),ChartLabSpace.rotate(ChartLabSpace.rotate(new Point(.3,2.7,11),t),-t)); }
    }
    @Test void guardWindowCanNeverAdmitTwoLiftedImagesOfAnyOrdinaryOwner() {
        for(double a=0;a<=90;a+=2.5) for(int s=0;s<3;s++) for(int x=0;x<32;x++) for(int z=0;z<32;z++) {
            var eye=new Point(20*Math.cos(Math.toRadians(a)),2,20*Math.sin(Math.toRadians(a))); int visible=0;
            for(int turns=-8;turns<=8;turns++) if(Math.floorMod(turns,3)==s&&ChartLabSpace.guard(ChartLabSpace.angle(eye,x,z,turns))<1) visible++;
            assertTrue(visible<=1);
        }
    }
    @Test void evenTheConservativeGuardDistanceBoundIsBeyondInteractionReach() {
        // Outside the 4x4 core, eye radius >=4 and ordinary cell-center radius >=4.5.
        // At >=115 degrees, chord distance is monotone in both radii. Subtract a whole
        // horizontal half-diagonal to include every point of the cube, not just its center.
        double a=ChartLabSpace.CORE,b=ChartLabSpace.CORE+.5;
        double minimum=Math.sqrt(a*a+b*b-2*a*b*Math.cos(Math.toRadians(ChartLabSpace.GUARD_START)))-Math.sqrt(.5);
        assertTrue(minimum>ChartLabSpace.REACH); assertTrue(minimum>6.46);
    }
}
