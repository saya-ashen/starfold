package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.*;
import dev.starfold.PlanetSpace.*;
import org.junit.jupiter.api.Test;
import org.joml.Quaterniond;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanetBalancedGeometryTest {
    // Pin dev.5 independently; production rounded geometry has its own admission suite.
    private final PlanetSpace space=new PlanetSpace(64,-4,4);
    private record Quality(double minEdge,double maxEdge,double minWidth,double meanAngle,double p95Angle,double maxAngle,double ordinaryMax,double maxAspect) {}
    private Quality quality(PlanetSpace s) { return quality(s,0,(u,v)->!s.voidColumn(new Cell(Face.TOP,u,0,v))); }
    private Point measuredPoint(PlanetSpace s,int u,int v,Face f,int version) {
        if(version==0) return s.point(s.vertex(f,u,0,v));
        double a=Math.tan((u/(double)s.size-.5)*Math.PI/2),b=Math.tan((v/(double)s.size-.5)*Math.PI/2);
        double aa=a*a,bb=b*b,p=1-aa,q=1-bb,correction=version==4&&p+q>0?.5*p*q/(p+q)*(.15+.85*aa*bb):0;
        double base=version==3?-.073125:0,own=version==3?-.1125:-.08,other=version==3?.234375:.13;
        double x=a*(1+p*bb*(base+own*aa+other*bb)-correction);
        double z=b*(1+q*aa*(base+own*bb+other*aa)-correction);
        return PlanetSpace.unit(f.normal.add(f.u.scale(x)).add(f.v.scale(z))).scale(s.radius);
    }
    private Quality quality(PlanetSpace s,boolean dev3,java.util.function.BiPredicate<Integer,Integer> include) {
        return quality(s,dev3?3:0,include);
    }
    private Quality quality(PlanetSpace s,int version,java.util.function.BiPredicate<Integer,Integer> include) {
        var errors=new ArrayList<Double>(); double lo=10,hi=0,width=10,max=0,ordinary=0,aspect=0,sum=0;
        // Global admission includes every cell. Seam/interior reports explicitly select their class.
        for(var f:Face.values()) for(int u=0;u<s.size;u++) for(int v=0;v<s.size;v++) {
            if(!include.test(u,v)) continue;
            Point[] p={measuredPoint(s,u,v,f,version),measuredPoint(s,u+1,v,f,version),measuredPoint(s,u+1,v+1,f,version),measuredPoint(s,u,v+1,f,version)};
            double localMin=10,localMax=0;
            for(int i=0;i<4;i++) {
                var a=p[(i+1)%4].sub(p[i]); var b=p[(i+3)%4].sub(p[i]);
                double length=a.length(),cos=Math.clamp(a.dot(b)/(length*b.length()),-1,1);
                double error=Math.abs(Math.toDegrees(Math.acos(cos))-90);
                errors.add(error); sum+=error; max=Math.max(max,error);
                if(!((u==0||u==s.size-1)&&(v==0||v==s.size-1))) ordinary=Math.max(ordinary,error);
                lo=Math.min(lo,length); hi=Math.max(hi,length);
                localMin=Math.min(localMin,length); localMax=Math.max(localMax,length);
                width=Math.min(width,Math.min(length,b.length())*Math.sqrt(1-cos*cos));
            }
            aspect=Math.max(aspect,localMax/localMin);
        }
        errors.sort(Double::compare);
        return new Quality(lo,hi,width,sum/errors.size(),errors.get((int)(.95*(errors.size()-1))),max,ordinary,aspect);
    }
    @Test void allPresentCellsImproveShearWithBoundedWidthAndAspect() {
        var dev4=quality(space,4,(u,v)->!space.voidColumn(new Cell(Face.TOP,u,0,v))); var q=quality(space);
        System.out.println("DEV4 ORDINARY "+dev4); System.out.println("OPEN CORE ORDINARY "+q);
        assertTrue(q.meanAngle<dev4.meanAngle*.7&&q.meanAngle<3.3);
        assertTrue(q.p95Angle<dev4.p95Angle*.67&&q.p95Angle<8.2);
        assertTrue(q.maxAspect<dev4.maxAspect&&q.maxAspect<1.61&&q.maxEdge<1.26);
        // Explicit trade: closest ordinary columns may be narrower than dev.4, but not collapsed.
        // Core columns are absent, NOT secretly included in the visible-cell statistics.
        assertTrue(q.minEdge>.706&&q.minWidth>.70);
        assertTrue(q.maxAngle<11.8&&q.ordinaryMax<11.8);
        // A size admission indicator, not a proof of arbitrary tunnel traversability.
        // Actual capsule passage is checked separately below.
        assertTrue(q.minWidth*(1+space.minHeight/space.radius)>2*(PlanetMotion.RADIUS+PlanetMotion.SKIN));
    }
    @Test void historicalDev3MetricsRemainPinnedRatherThanRelaxedSilently() {
        var q=quality(space,true,(u,v)->true);
        assertEquals(5.278966511087368,q.meanAngle,1e-9);
        assertEquals(26.626206053943818,q.ordinaryMax,1e-9);
        assertEquals(.7102489682997613,q.minWidth,1e-9);
        assertTrue(q.maxEdge<1.001&&q.maxAspect<1.31);
    }
    @Test void historicalDev4MetricsRemainPinnedIncludingItsVisibleTips() {
        var q=quality(space,4,(u,v)->true);
        assertEquals(4.760797118762324,q.meanAngle,1e-9);
        assertEquals(15.579793554234328,q.ordinaryMax,1e-9);
        assertEquals(.7222017928832839,q.minWidth,1e-9);
        assertEquals(1.6795424473751983,q.maxAspect,1e-9);
    }
    @Test void gridLinesContinueAcrossSeamsWithBoundedTangentKinks() {
        var kinks=new ArrayList<Double>();
        // Both directions of every seam; true corner endpoints alone are omitted.
        for(var f:Face.values()) for(int edge=0;edge<4;edge++) for(int k=1;k<64;k++) {
            var toward=(edge<2?f.u:f.v).scale(edge%2==0?-1:1); var along=edge<2?f.v:f.u;
            var border=f.normal.scale(64).add(toward.scale(64)).add(along.scale(2*k-64));
            var left=border.sub(toward.scale(2)); var right=border.sub(f.normal.scale(2));
            var p=space.point(new Vertex((int)border.x(),(int)border.y(),(int)border.z(),0));
            var a=space.point(new Vertex((int)left.x(),(int)left.y(),(int)left.z(),0));
            var b=space.point(new Vertex((int)right.x(),(int)right.y(),(int)right.z(),0));
            var up=PlanetSpace.unit(p); var incoming=p.sub(a); var outgoing=b.sub(p);
            incoming=incoming.sub(up.scale(incoming.dot(up))); outgoing=outgoing.sub(up.scale(outgoing.dot(up)));
            kinks.add(Math.toDegrees(Math.acos(Math.clamp(incoming.dot(outgoing)/(incoming.length()*outgoing.length()),-1,1))));
        }
        kinks.sort(Double::compare); double max=kinks.getLast(),p95=kinks.get((int)(.95*(kinks.size()-1)));
        System.out.println("OPEN CORE SEAM ROW KINK max="+max+" p95="+p95);
        assertEquals(1512,kinks.size()); assertTrue(max<13&&p95<5.5);
    }
    @Test void inverseRemainsStableArbitrarilyCloseToEveryTrueTipAndSeam() {
        for(var f:Face.values()) for(double du:new double[]{0,1e-9,1e-6,.001,.1,1})
            for(double dv:new double[]{0,1e-9,1e-6,.001,.1,1}) for(int flip=0;flip<4;flip++) {
                double u=(flip&1)==0?du:64-du,v=(flip&2)==0?dv:64-dv;
                var p=space.project(f,u,0,v); var c=space.coordinates(f,p);
                assertTrue(p.finite()); assertEquals(u,c.u(),1e-9); assertEquals(v,c.v(),1e-9);
            }
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,1.01,-1.01})
            assertThrows(IllegalArgumentException.class,()->PlanetBalancedGeometry.inverse(bad,0));
    }
    @Test void canonicalCornersAndTextureInverseAgreeOnEveryFace() {
        for(var f:Face.values()) for(int u=0;u<=64;u++) for(int v=0;v<=64;v++) for(int h:new int[]{-4,0,4}) {
            var p=space.point(space.vertex(f,u,h,v));
            assertEquals(p,space.project(f,u,h,v));
            var c=space.coordinates(f,p);
            assertEquals(u,c.u(),1e-9); assertEquals(v,c.v(),1e-9); assertEquals(h,c.h(),1e-12);
        }
    }
    @Test void interiorInverseRoundTripsAndNoFiniteDifferenceFoldWasIntroduced() {
        var random=new Random(8502); double e=1e-4,min=Double.POSITIVE_INFINITY;
        for(var f:Face.values()) for(int i=0;i<2000;i++) {
            double u=random.nextDouble()*64,v=random.nextDouble()*64,h=-4+random.nextDouble()*8;
            var p=space.project(f,u,h,v); var c=space.coordinates(f,p);
            assertEquals(u,c.u(),1e-9); assertEquals(v,c.v(),1e-9); assertEquals(h,c.h(),1e-12);
            var du=space.project(f,u+e,h,v).sub(space.project(f,u-e,h,v)).scale(.5/e);
            var dv=space.project(f,u,h,v+e).sub(space.project(f,u,h,v-e)).scale(.5/e);
            double det=du.cross(dv).dot(PlanetSpace.unit(p).scale(-1)); min=Math.min(min,det);
            assertTrue(det>.4,"Fold/collapse at "+f+" "+u+","+v+" h="+h);
        }
        System.out.println("FIXED SAMPLED MIN SURFACE JACOBIAN="+min);
    }
    @Test void everyPresentFiniteCellKeepsPositiveFacetsAtAllHeights() {
        for(var f:Face.values()) for(int u=0;u<64;u++) for(int v=0;v<64;v++) for(int h=space.minHeight;h<space.maxHeight;h++) {
            var c=new Cell(f,u,h,v); if(!space.hasVolume(c)) continue;
            var center=space.project(f,u+.5,h+.5,v+.5);
            for(var side:Side.values()) for(var t:space.face(c,side)) {
                assertTrue(t.b().sub(t.a()).cross(t.c().sub(t.a())).length()>.2,"collapsed "+c);
                assertTrue(t.normal().dot(t.a().sub(center))>.15,"inverted "+c+" "+side);
            }
        }
    }
    @Test void gnomonicCellsAreConvexAndBoundaryOrderNeverFolds() {
        // Check raw vertex order BEFORE PlanetSpace.face chooses outward winding. A test of
        // corrected normals alone could conceal a folded map. Every face is a convex square
        // disk with monotone boundary and positively oriented constituent triangles.
        for(var f:Face.values()) {
            for(int edge=0;edge<4;edge++) {
                double previous=-2;
                for(int k=0;k<=64;k++) {
                    var p=space.project(f,edge<2?(edge==0?0:64):k,0,edge>=2?(edge==2?0:64):k);
                    double normal=p.dot(f.normal),x=p.dot(f.u)/normal,z=p.dot(f.v)/normal;
                    double fixed=edge<2?x:z,moving=edge<2?z:x;
                    assertEquals(edge%2==0?-1:1,fixed,1e-12); assertTrue(moving>previous); previous=moving;
                }
                assertEquals(1,previous,1e-12);
            }
            for(int u=0;u<64;u++) for(int v=0;v<64;v++) {
                if(space.voidColumn(new Cell(f,u,0,v))) continue;
                Point[] p={space.project(f,u,0,v),space.project(f,u+1,0,v),space.project(f,u+1,0,v+1),space.project(f,u,0,v+1)};
                double[] x=new double[4],z=new double[4];
                for(int i=0;i<4;i++) { double n=p[i].dot(f.normal); x[i]=p[i].dot(f.u)/n; z[i]=p[i].dot(f.v)/n; }
                for(int i=0;i<4;i++) {
                    int j=(i+1)%4,k=(i+2)%4;
                    double cross=(x[j]-x[i])*(z[k]-z[i])-(z[j]-z[i])*(x[k]-x[i]);
                    assertTrue(cross>1e-4,"Raw quad fold: "+f+" "+u+","+v);
                }
            }
        }
    }
    @Test void productionSeamsHaveIdenticalFixedTrianglesOnBothSides() {
        for(var f:Face.values()) for(int k=0;k<64;k++) for(int edge=0;edge<4;edge++) for(int h:new int[]{-4,0,3}) {
            var c=new Cell(f,edge<2?(edge==0?0:63):k,h,edge>=2?(edge==2?0:63):k);
            var side=List.of(Side.WEST,Side.EAST,Side.NORTH,Side.SOUTH).get(edge); var n=space.neighbor(c,side);
            Set<Set<Point>> a=new HashSet<>(),b=new HashSet<>();
            for(var t:space.face(c,side)) a.add(Set.of(t.a(),t.b(),t.c()));
            for(var t:space.face(n.target(),n.incoming())) b.add(Set.of(t.a(),t.b(),t.c()));
            assertEquals(a,b);
        }
    }
}
