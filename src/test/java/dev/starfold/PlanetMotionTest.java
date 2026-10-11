package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.*;
import dev.starfold.PlanetSpace.*;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanetMotionTest {
    private final PlanetSpace space=new PlanetSpace(16,-3,4);
    private PlanetMotion.Input input(Quaterniond q,float f,float l,float u,boolean direct) {
        return new PlanetMotion.Input(1,q.x,q.y,q.z,q.w,f,l,u,direct,false);
    }
    @Test void placementRejectsCapsuleIntersectionsAndInteriorNotJustTriangleHits() {
        var cell=new Cell(Face.TOP,7,0,7); var hull=new ArrayList<Triangle>(); for(var side:Side.values()) hull.addAll(space.face(cell,side));
        assertTrue(PlanetMotion.intersects(space.project(Face.TOP,7.5,.5,7.5),hull));
        assertFalse(PlanetMotion.intersects(space.project(Face.TOP,7.5,4,7.5),hull));
    }
}
