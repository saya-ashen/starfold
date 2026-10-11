package dev.starfold;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;
import dev.starfold.CubeSurfaceTopology.*;

class CubeSurfaceTopologyTest {
    private final CubeSurfaceTopology topology=new CubeSurfaceTopology(16);
    private static final Delta[] DIRECTIONS={new Delta(1,0,0),new Delta(-1,0,0),new Delta(0,0,1),new Delta(0,0,-1)};
    @Test void allTwelveEdgesAreReciprocalAtEveryCellAndHeight() {
        var pairs=new HashSet<String>();
        for(Face face:Face.values()) for(Delta d:DIRECTIONS) for(int i=0;i<16;i++) for(int h=-1;h<=12;h++) {
            Cell origin=new Cell(face,d.u()==0?i:d.u()>0?15:0,h,d.v()==0?i:d.v()>0?15:0);
            Route route=topology.canonical(origin.add(d.u(),0,d.v()));
            assertNotEquals(face,route.cell().face()); assertEquals(h,route.cell().h());
            Delta step=route.transport(d);
            Route back=topology.canonical(route.cell().add(-step.u(),0,-step.v()));
            assertEquals(origin,back.cell()); assertEquals(0,(route.turns()+back.turns())%4);
            int a=face.ordinal(),b=route.cell().face().ordinal(); pairs.add(Math.min(a,b)+":"+Math.max(a,b));
        }
        assertEquals(12,pairs.size());
    }
    @Test void allOwnedCellsStayUniqueAndUnrotated() {
        var cells=new HashSet<Cell>();
        for(Face face:Face.values()) for(int u=0;u<16;u++) for(int v=0;v<16;v++) {
            Cell c=new Cell(face,u,4,v); Route r=topology.canonical(c);
            assertEquals(c,r.cell()); assertEquals(0,r.turns()); assertTrue(cells.add(r.cell()));
        }
        assertEquals(6*16*16,cells.size());
    }
    @Test void entireBoundedHaloConvergesDeterministically() {
        for(Face face:Face.values()) for(int u=-16;u<32;u++) for(int v=-16;v<32;v++) {
            Cell c=new Cell(face,u,3,v); Route route=topology.canonical(c);
            assertTrue(topology.owned(route.cell())); assertEquals(3,route.cell().h());
            assertEquals(route,topology.canonical(c));
            for(Delta d:DIRECTIONS) assertEquals(d,route.untransport(route.transport(d)));
        }
    }
    @Test void cornerPolicyIsExplicitAndDoesNotClaimCommutingPaths() {
        Cell corner=new Cell(Face.TOP,15,0,15);
        Route east=topology.canonical(corner.add(1,0,0));
        Delta south=east.transport(new Delta(0,0,1));
        Cell eastThenSouth=topology.canonical(east.cell().add(south.u(),0,south.v())).cell();
        Route southRoute=topology.canonical(corner.add(0,0,1));
        Delta eastDelta=southRoute.transport(new Delta(1,0,0));
        Cell southThenEast=topology.canonical(southRoute.cell().add(eastDelta.u(),0,eastDelta.v())).cell();
        assertNotEquals(eastThenSouth,southThenEast);
        assertEquals(eastThenSouth,topology.canonical(corner.add(1,0,1)).cell());
    }
    @Test void notificationsPreserveRealTargetAndTransportedRelativeSource() {
        for(Face face:Face.values()) for(int u=-1;u<=16;u++) for(int v=-1;v<=16;v++) for(Delta d:DIRECTIONS) {
            Cell target=new Cell(face,u,9,v),source=target.add(d.u(),d.h(),d.v());
            Notification n=topology.notification(target,source); Route r=topology.canonical(target);
            assertEquals(r.cell(),n.target());
            assertEquals(r.transport(d),new Delta(n.source().u()-n.target().u(),n.source().h()-n.target().h(),n.source().v()-n.target().v()));
            assertEquals(n.target().face(),n.source().face());
        }
    }
    @Test void heightNeverBecomesAHorizonalAxis() {
        for(int turns=-12;turns<=12;turns++) assertEquals(new Delta(0,1,0),CubeSurfaceTopology.rotate(new Delta(0,1,0),turns));
    }
    @Test void rejectsUnboundedQueriesAndMismatchedNotificationCharts() {
        assertThrows(IllegalArgumentException.class,()->topology.canonical(new Cell(Face.TOP,Integer.MIN_VALUE,0,0)));
        assertThrows(IllegalArgumentException.class,()->topology.notification(new Cell(Face.TOP,0,0,0),new Cell(Face.EAST,0,0,0)));
    }
}
