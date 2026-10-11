package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GroundGapTest {
    private final Point zero=new Point(0,0,0);
    @Test void protected32NeverShrinksEvenRightBesideTheCorner(){
        var random=new Random(53);for(var face:Face.values())for(int corner=0;corner<4;corner++)for(double d:new double[]{.1,10.5,40.5,80,164.5,900}){
            var eye=new Point((corner&1)==0?d:2048-d,80,(corner&2)==0?1.5:2046.5);var map=new GroundGap(face,eye,1024,true);
            for(int i=0;i<1000;i++){double a=random.nextDouble()*Math.PI*2,r=32*Math.sqrt(random.nextDouble());var p=eye.add(new Point(r*Math.cos(a),random.nextDouble()*100-50,r*Math.sin(a)));assertTrue(map.flat(p).sub(p).length()<1e-9,face+" "+eye+" "+p);}
        }
    }
    @Test void theEntireCurrentFaceAndEveryHeightStayUnchanged(){
        for(var face:Face.values())for(var eye:List.of(new Point(10.5,1,1.5),new Point(164.5,1,2046.5),new Point(2046.5,90,2046.5))){var map=new GroundGap(face,eye,256,true);for(double x=0;x<=2048;x+=31.5)for(double z=0;z<=2048;z+=31.5){var p=new Point(x,39.125,z);assertTrue(map.point(face,p).sub(p).length()<1e-9);}}
    }
    @Test void donorStripsJoinAtTheirRealSharedSeamOnlyInTheFarZone(){
        for(var face:Face.values())for(int corner=0;corner<4;corner++){
            var eye=new Point((corner&1)==0?10.5:2037.5,1,(corner&2)==0?1.5:2046.5);var map=new GroundGap(face,eye,256,true);assertEquals(1,map.strength);
            for(double r:new double[]{128,144,160,176}){var a=new Point(map.corner.x()-map.sx*r,7,map.corner.z());var b=new Point(map.corner.x(),7,map.corner.z()-map.sz*r);assertEquals(1,map.amplitude(r),1e-12);assertTrue(map.flat(a).sub(map.flat(b)).length()<1e-9);}
            var a=new Point(map.corner.x()-map.sx*16,7,map.corner.z());var b=new Point(map.corner.x(),7,map.corner.z()-map.sz*16);assertEquals(a,map.flat(a));assertEquals(b,map.flat(b));assertTrue(map.flat(a).sub(map.flat(b)).length()>20);
        }
    }
    @Test void ordinaryPartsOutsideTheBorderBandRemainRigid(){var map=new GroundGap(Face.TOP,new Point(40,1,2),256,true);for(double r=40;r<600;r+=3){for(double angle:new double[]{0,Math.PI/4,Math.PI/2,-Math.PI/4}){var p=new Point(r*Math.cos(angle),2,r*Math.sin(angle));assertTrue(map.flat(p).sub(p).length()<1e-8);}}}
    @Test void sampledFarStripsDoNotFoldOrPullTerrainIntoProtectedSpace(){
        for(var eye:List.of(new Point(.1,1,.1),new Point(40.5,1,1.5),new Point(164.5,1,2.5),new Point(900,1,1))){var map=new GroundGap(Face.TOP,eye,1024,true);var occupied=new HashSet<String>();
            for(double x=-2040.5;x<2048;x+=17)for(double z=-2040.5;z<2048;z+=17){if(x<0&&z<0)continue;var p=new Point(x,9,z);var a=map.flat(p);var dx=map.flat(p.add(new Point(.001,0,0))).sub(a).scale(1000);var dz=map.flat(p.add(new Point(0,0,.001))).sub(a).scale(1000);assertTrue(dx.x()*dz.z()-dx.z()*dz.x()>.01,"Fold "+eye+" / "+p);assertEquals(p.y(),a.y());if(a.sub(p).length()>1e-8)assertTrue(Math.hypot(a.x()-eye.x(),a.z()-eye.z())>=32,"Warp entered protected disk");assertTrue(occupied.add(Math.round(a.x()*1e6)+":"+Math.round(a.z()*1e6)),"Duplicate sample image");}
        }
    }
    @Test void coarseAndFineBordersShareTheSameLinearSegments(){var map=new GroundGap(Face.TOP,new Point(40.5,1,1.5),256,true);for(int x=-512;x<=512;x+=16)for(int z=-512;z<512;z+=16){if(x<0&&z<0)continue;var a=map.flat(new Point(x,8,z));var b=map.flat(new Point(x,8,z+16));for(double t:new double[]{.0625,.37,.5,.9375})assertTrue(map.flat(new Point(x,8,z+16*t)).sub(a.scale(1-t).add(b.scale(t))).length()<1e-9);}}
    @Test void fullyRepairedFarTerrainDoesNotJumpWhenTheRootCrossesAnOrdinarySeam(){
        for(var face:Face.values())for(int[] dir:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}){var p=new Point(dir[0]<0?0:dir[0]>0?2048:100,1,dir[1]<0?0:dir[1]>0?2048:100);var before=new GroundGap(face,p,256,true);var rootTile=before.chart.at(dir[0]<0?-1:dir[0]>0?2048:100,dir[1]<0?-1:dir[1]>0?2048:100);var after=new GroundGap(rootTile.face(),rootTile.local(p),256,true);
            for(double angle=-Math.PI/2+.03;angle<Math.PI;angle+=.07)for(double r:new double[]{96,128,176}){var q=new Point(before.corner.x()+before.sx*r*Math.cos(angle),4,before.corner.z()+before.sz*r*Math.sin(angle));var tile=before.chart.at((int)Math.floor(q.x()),(int)Math.floor(q.z()));assertNotNull(tile);var local=tile.local(q);assertNotNull(after.chart.tile(tile.face()));assertTrue(before.point(tile.face(),local).sub(rootTile.point(after.point(tile.face(),local))).length()<1e-8,face+" "+rootTile.face()+" "+q);}
        }
    }
    @Test void rootChangesKeepTheProtectedLocalNeighbourhoodIdentical(){
        for(var face:Face.values())for(int[] dir:new int[][]{{-1,0},{1,0},{0,-1},{0,1}}){var p=new Point(dir[0]<0?0:dir[0]>0?2048:100,1,dir[1]<0?0:dir[1]>0?2048:100);var before=new GroundGap(face,p,256,true);var tile=before.chart.at(dir[0]<0?-1:dir[0]>0?2048:100,dir[1]<0?-1:dir[1]>0?2048:100);var after=new GroundGap(tile.face(),tile.local(p),256,true);for(int u=-20;u<=20;u+=2)for(int v=-20;v<=20;v+=2){var a=p.add(new Point(u,0,v));assertTrue(before.flat(a).sub(tile.point(after.flat(tile.local(a)))).length()<1e-8);}}
    }
}
