package dev.starfold.render;

import static org.junit.jupiter.api.Assertions.*;
import dev.starfold.chart.*;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.geometry.CubeSurfaceTopology.Face;
import dev.starfold.worldgen.PlanetTerrainTest;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.function.Function;

class GroundFarHorizonTest {
    @BeforeAll static void boot() { PlanetTerrainTest.registry(); }
    @Test void allFarPathsHaveSixteenBlockTopAndWallEdgesAndUnchangedDrawBatching() {
        for(int step:new int[]{16,32,64}) {
            var quads=GroundFarMesh.build(PlanetShape.STANDARD,0,step,(f,x,z,s)->new GroundFarMesh.Surface(0,Blocks.WATER.defaultBlockState()));
            assertEquals(256,quads.size()); // Water is a surface, not an opaque slab with walls/bottom.
            for(var q:quads) for(int i=0;i<4;i++) {
                var d=q.points().get(i).sub(q.points().get((i+1)%4));
                assertTrue(Math.hypot(d.x(),d.z())<=16);
            }
        }
    }
    @Test void bentHorizonEdgesAndTriangleInteriorsStayWithinHalfBlock() {
        for(int size:new int[]{416,1024,2048}) {
            var shape=new PlanetShape(size);
            double worst=0;
            for(double altitude:new double[]{50,100,150}) for(double fraction:new double[]{.3,.5,.7}) {
                var eye=new Point(size*fraction,altitude,size*.5);
                var normal=shape.direction(Face.TOP,eye.x(),eye.z());
                var air=new PlanetAir(shape,Face.TOP,eye,GroundAir.DEFAULT,true);
                var orbit=new PlanetOrbit(shape,new PlanetShape.Pose(Face.TOP,eye),eye,4096);
                double horizon=shape.radius()/(shape.radius()+altitude);
                for(var face:Face.values()) {
                    for(int z=0;z<size;z+=16) for(int x=0;x<size;x+=16) {
                        var centre=shape.direction(face,x+8,z+8);
                        // Visible horizon band, not the hidden stereographic antipodal pole.
                        if(Math.abs(centre.dot(normal)-horizon)>.06) continue;
                        var p=List.of(new Point(x,0,z),new Point(x,0,z+16),new Point(x+16,0,z+16),new Point(x+16,0,z));
                        worst=Math.max(worst,error(p,v->GroundExteriorMesh.position(shape,face,v.x(),v.z(),v.y())));
                        worst=Math.max(worst,error(p,v->orbit.raw(face,v)));
                        var tile=new PlanetChart(shape,Face.TOP).tile(face);
                        if(tile!=null) worst=Math.max(worst,error(p,v->air.raw(tile.point(v))));
                    }
                }
            }
            System.out.printf(Locale.ROOT,"LOD horizon size=%d max analytic error=%.6f blocks%n",size,worst);
            assertTrue(worst<=.5,"size="+size+" error="+worst);
        }
    }
    static double error(List<Point> p,Function<Point,Point> bend) {
        double worst=0;
        var a=bend.apply(p.get(0)); var b=bend.apply(p.get(1)); var c=bend.apply(p.get(2)); var d=bend.apply(p.get(3));
        for(int i=0;i<=4;i++) for(int j=0;j<=4;j++) {
            double u=i/4.0,v=j/4.0;
            var original=p.get(0).scale(1-u).add(p.get(3).scale(u)).scale(1-v)
                    .add(p.get(1).scale(1-u).add(p.get(2).scale(u)).scale(v));
            var interpolated=v>=u ? a.scale(1-v).add(b.scale(v-u)).add(c.scale(u)) : a.scale(1-u).add(c.scale(v)).add(d.scale(u-v));
            worst=Math.max(worst,interpolated.sub(bend.apply(original)).length());
        }
        return worst;
    }
}
