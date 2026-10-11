package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GroundAirTest {
    private static final double START=30,MIDDLE=79,END=128;
    private GroundGap map(Face f,Point eye){return new GroundGap(f,eye,1024,true,GroundAir.DEFAULT,true);}
    @Test void acceptedFinalDefaultsAndInvalidThresholdsDoNotSilentlyClamp(){
        assertEquals(30,GroundAir.DEFAULT.start());assertEquals(128,GroundAir.DEFAULT.end());assertEquals(GroundSpace.DEFAULT_MASK.height(),GroundAir.DEFAULT.end());
        for(int[] v:new int[][]{{-1,48},{0,0},{80,80},{100,90},{80,129}})assertThrows(IllegalArgumentException.class,()->new GroundAir.Config(v[0],v[1]));
        var c=new GroundAir.Config(0,128);assertEquals(0,c.weight(0));assertEquals(.5,c.weight(64));assertEquals(1,c.weight(128));
    }
    @Test void thresholdsBelowTheFormer64LimitActuallyDriveClosure(){
        for(var c:List.of(new GroundAir.Config(0,1),new GroundAir.Config(8,16),GroundAir.DEFAULT,new GroundAir.Config(80,112))){
            var low=new GroundGap(Face.TOP,new Point(10.5,c.start(),1.5),256,true,c,true);
            var mid=new GroundGap(Face.TOP,new Point(10.5,(c.start()+c.end())*.5,1.5),256,true,c,true);
            var full=new GroundGap(Face.TOP,new Point(10.5,c.end(),1.5),256,true,c,true);
            assertEquals(0,low.air.weight);assertEquals(.5,mid.air.weight);assertEquals(1,full.air.weight);assertFalse(low.residual().isEmpty());assertTrue(full.residual().isEmpty());
        }
        for(double h:new double[]{128,128.01,128.72})assertEquals(1,map(Face.TOP,new Point(10.5,h,1.5)).air.weight);
    }
    @Test void lowHeightAndOffModesExactlyPreserveAcceptedMap(){
        var random=new Random(54);for(var f:Face.values())for(double height:new double[]{1,16,30,32,40,56,79,112,127.99,128}){
            var eye=new Point(40.5,height,1.5);var old=new GroundGap(f,eye,1024,true);var now=map(f,eye);var disabled=new GroundGap(f,eye,1024,true,GroundAir.DEFAULT,false);var original=new GroundGap(f,eye,1024,false,GroundAir.DEFAULT,true);
            for(var tile:old.chart.tiles())for(int i=0;i<30;i++){var p=new Point(random.nextDouble()*2048,random.nextDouble()*64-8,random.nextDouble()*2048);assertEquals(old.point(tile.face(),p),disabled.point(tile.face(),p));assertEquals(tile.point(p),original.point(tile.face(),p));if(height<=GroundAir.DEFAULT.start())assertEquals(old.point(tile.face(),p),now.point(tile.face(),p));}
        }
    }
    @Test void everyCutClosesAtFullHeightNotJustTheNearestCorner(){
        for(var f:Face.values())for(var eye:List.of(new Point(.1,END,.1),new Point(10.5,END,1.5),new Point(1024,END,1.5),new Point(2047.9,END,2047.9),new Point(1024,END,1024))){var m=map(f,eye);
            for(double x:new double[]{0,2048})for(double z:new double[]{0,2048})for(double r:new double[]{0,.1,1.5,16,63.75,512,1024,1800,2048}){double sx=x==0?1:-1,sz=z==0?1:-1;var a=m.flat(new Point(x-sx*r,17.2,z));var b=m.flat(new Point(x,17.2,z-sz*r));assertTrue(a.sub(b).length()<1e-7,f+" "+eye+" r="+r);assertEquals(17.2,a.y());}
            assertTrue(m.residual().isEmpty(),eye.toString());
        }
    }
    @Test void fullHeightChangesCurrentFaceButKeepsCameraFootprintAndVerticalLayers(){
        var m=map(Face.TOP,new Point(10.5,END,1.5));var eye=m.eye;assertTrue(m.flat(new Point(eye.x(),0,eye.z())).sub(new Point(eye.x(),0,eye.z())).length()<1e-10);
        assertTrue(m.flat(new Point(100,7,100)).sub(new Point(100,7,100)).length()>1);
        for(double y:new double[]{-8,0,7,56})assertEquals(y,m.flat(new Point(100,y,100)).y());
    }
    @Test void heightBlendHasNoTimeLagAndIsContinuousBothWays(){
        var p=new Point(-16,0,0);Point last=null;for(double h=START-1;h<=END+1;h+=.25){var m=map(Face.TOP,new Point(10.5,h,1.5));var q=m.flat(p);assertTrue(q.finite());if(last!=null)assertTrue(q.sub(last).length()<1);last=q;assertEquals(q,map(Face.TOP,new Point(10.5,h,1.5)).flat(p));}
        var low=new GroundGap(Face.TOP,new Point(10.5,1,1.5),1024,true);var full=map(Face.TOP,new Point(10.5,END,1.5));var mid=map(Face.TOP,new Point(10.5,MIDDLE,1.5));assertTrue(mid.flat(p).sub(low.flat(p).add(full.flat(p)).scale(.5)).length()<1e-10);
    }
    @Test void highChartAndAnchorAreTransportedAtEveryOrdinaryRootSeam(){
        for(var face:Face.values())for(int[] dir:new int[][]{{-1,0},{1,0},{0,-1},{0,1}})for(double along:new double[]{.1,10.5,100,1024,2047.9})for(double subframe:new double[]{-.1,0,.1}){
            var p=new Point(dir[0]<0?0:dir[0]>0?2048:along,END,dir[1]<0?0:dir[1]>0?2048:along).add(new Point(dir[0]*subframe,0,dir[1]*subframe));var a=map(face,p);var t=a.chart.at(dir[0]<0?-1:dir[0]>0?2048:(int)along,dir[1]<0?-1:dir[1]>0?2048:(int)along);var b=map(t.face(),t.local(p));
            for(var tile:a.chart.tiles()){if(b.chart.tile(tile.face())==null)continue;for(double x:new double[]{0,12.5,1024,2037.5,2048})for(double z:new double[]{0,12.5,1024,2037.5,2048}){var q=new Point(x,8,z);var before=a.point(tile.face(),q);if(before.sub(p).length()>1100)continue;assertTrue(before.sub(t.point(b.point(tile.face(),q))).length()<1e-6,face+" -> "+t.face()+" "+p+" "+q);}}
        }
    }
    @Test void sampledVisibleTrianglesDoNotFoldDuringTransition(){
        for(var eye:List.of(new Point(.1,MIDDLE,.1),new Point(10.5,MIDDLE,1.5),new Point(1024,MIDDLE,1.5),new Point(2037.5,MIDDLE,2046.5),new Point(1024,MIDDLE,1024)))for(double h:new double[]{START,42,MIDDLE,116,END}){var m=map(Face.TOP,new Point(eye.x(),h,eye.z()));
            for(var tile:m.chart.tiles())for(double x=8;x<2048;x+=64)for(double z=8;z<2048;z+=64){var p=tile.point(new Point(x,0,z));var a=m.flat(p);if(Math.hypot(a.x()-eye.x(),a.z()-eye.z())>1100)continue;var du=m.flat(p.add(new Point(.01,0,0))).sub(a);var dv=m.flat(p.add(new Point(0,0,.01))).sub(a);assertTrue(du.x()*dv.z()-du.z()*dv.x()>1e-7,"Fold "+eye+" h="+h+" "+p);}
        }
    }
    @Test void coarseAndFineBordersRemainWeldedAtAllHeights(){
        for(double h:new double[]{START,MIDDLE,END}){var m=map(Face.TOP,new Point(10.5,h,1.5));for(int x=-128;x<=256;x+=16)for(int z=0;z<256;z+=16){var a=m.flat(new Point(x,8,z));var b=m.flat(new Point(x,8,z+16));for(double t:new double[]{.0625,.37,.5,.9375})assertTrue(m.flat(new Point(x,8,z+16*t)).sub(a.scale(1-t).add(b.scale(t))).length()<1e-9);}}
    }
    @Test void oppositeFaceBoundaryStaysOutsideMaximumVisibleDisk(){
        for(var eye:List.of(new Point(0,END,0),new Point(10.5,END,1.5),new Point(1024,END,1.5),new Point(1024,END,1024),new Point(2048,END,2048))){var m=map(Face.TOP,eye);
            for(double t=0;t<=2048;t+=16)for(var p:List.of(new Point(-2048,0,t),new Point(4096,0,t),new Point(t,0,-2048),new Point(t,0,4096))){var a=m.flat(p);assertTrue(a.finite());assertTrue(m.air.facing(p)<0||Math.hypot(a.x()-eye.x(),a.z()-eye.z())>m.clip,"Missing opposite face entered view "+eye+" "+p);}
        }
    }
    @Test void remoteProjectionDomainBoundaryCannotCutIntoTheVisibleDisk(){
        for(double x:new double[]{0,.1,10.5,100,512,1024,2048})for(double z:new double[]{0,.1,100,1024,2048}){
            var eye=new Point(x,END,z);var air=new GroundAir(Face.TOP,eye,GroundAir.DEFAULT,true);var u=air.basisU.scale(1/air.basisU.length());var v=air.normal.cross(u);
            for(double angle=0;angle<Math.PI*2;angle+=.04){var s=u.scale(Math.cos(angle)).add(v.scale(Math.sin(angle)));var q=s.scale(1024/Math.max(Math.abs(s.x()),Math.max(Math.abs(s.y()),Math.abs(s.z()))));Point p;
                if(Math.abs(q.y()+1024)<1e-8)continue; // outside all five source tiles
                if(Math.abs(q.x()+1024)<1e-8)p=new Point(q.y()-1024,0,q.z()+1024);
                else if(Math.abs(q.x()-1024)<1e-8)p=new Point(3072-q.y(),0,q.z()+1024);
                else if(Math.abs(q.z()+1024)<1e-8)p=new Point(q.x()+1024,0,q.y()-1024);
                else if(Math.abs(q.z()-1024)<1e-8)p=new Point(q.x()+1024,0,3072-q.y());
                else p=new Point(q.x()+1024,0,q.z()+1024);
                for(double h:new double[]{START,42,MIDDLE,116,END}){var m=map(Face.TOP,new Point(x,h,z));var a=m.flat(p);assertTrue(Math.hypot(a.x()-x,a.z()-z)>m.clip+64,"Domain boundary entered view "+eye+" h="+h+" "+p);}
            }
        }
    }
}
