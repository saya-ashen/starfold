package dev.starfold;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import dev.starfold.BentStripGeometry.*;

class BuildGeometryTest {
    private final BentStripGeometry g=new BentStripGeometry(16,2);
    @Test void halfSlabPickUsesHalfHeightOnBothOwners() {
        for(int u:new int[]{15,16}) {
            var mesh=g.meshBox(new Cell(u,0,1),16,new Point(0,0,0),new Point(1,.5,1));
            var hit=BentStripGeometry.pick(mesh,g.project(new Point(u+.5,3,1.5)),g.up(u+.5).scale(-1),4);
            assertNotNull(hit); assertEquals(u,hit.owner().u()); assertEquals(Face.UP,hit.face()); assertEquals(2.5,hit.distance(),1e-8);
        }
    }
    @Test void stairStepsPickDifferentHeightsWithoutInventingFullCube() {
        var cell=new Cell(15,0,1); var mesh=new ArrayList<Triangle>();
        mesh.addAll(g.meshBox(cell,16,new Point(0,0,0),new Point(1,.5,1)));
        mesh.addAll(g.meshBox(cell,16,new Point(.5,.5,0),new Point(1,1,1)));
        for(double u:new double[]{15.25,15.75}) {
            var hit=BentStripGeometry.pick(mesh,g.project(new Point(u,3,1.5)),g.up(u).scale(-1),4);
            assertNotNull(hit); assertEquals(u<15.5?2.5:2,hit.distance(),1e-8);
        }
    }
    @Test void straightBoxesAvoidUnnecessaryTessellation() {
        assertEquals(12,g.meshBox(new Cell(3,0,1),16,new Point(0,0,0),new Point(1,1,1)).size());
        assertTrue(g.meshBox(new Cell(15,0,1),16,new Point(0,0,0),new Point(1,1,1)).size()>12);
    }
    @Test void partialBoxTrianglesRemainSourceOwnedAndFinite() {
        var cell=new Cell(16,7,31);
        for(var t:g.meshBox(cell,16,new Point(.375,0,.375),new Point(.625,1.5,.625))) {
            assertEquals(cell,t.owner()); assertTrue(t.a().finite()&&t.b().finite()&&t.c().finite());
            assertTrue(t.b().sub(t.a()).cross(t.c().sub(t.a())).length()>1e-10);
        }
    }
    @Test void malformedPartialShapesRejected() {
        var c=new Cell(16,0,0);
        assertThrows(IllegalArgumentException.class,()->g.meshBox(c,16,new Point(0,0,0),new Point(0,1,1)));
        assertThrows(IllegalArgumentException.class,()->g.meshBox(c,16,new Point(0,0,0),new Point(1,2,1)));
        assertThrows(IllegalArgumentException.class,()->g.meshBox(c,16,new Point(Double.NaN,0,0),new Point(1,1,1)));
    }
    @Test void buildingDepthMapsLastRowBothDirections() {
        var a=new StripTopology.Cell(10000,0,10000); var b=new StripTopology.Cell(-10000,0,-10000);
        var atlas=new StripTopology(a,b,32);
        assertEquals(b.add(0,20,31),atlas.canonical(a.add(16,20,31)));
        assertEquals(a.add(15,20,31),atlas.canonical(b.add(-1,20,31)));
    }
    @Test void buildingHaloIsBoundedAndLegacyDepthStaysUnchanged() {
        var a=new StripTopology.Cell(10000,0,10000); var b=new StripTopology.Cell(-10000,0,-10000);
        assertEquals(a.add(16,9,40),new StripTopology(a,b,32).canonical(a.add(16,9,40)));
        assertEquals(a.add(16,9,31),new StripTopology(a,b).canonical(a.add(16,9,31)));
    }
    @Test void invalidAtlasDepthRejected() {
        var p=new StripTopology.Cell(0,0,0);
        assertThrows(IllegalArgumentException.class,()->new StripTopology(p,p,0));
        assertThrows(IllegalArgumentException.class,()->new StripTopology(p,p,33));
    }
}
