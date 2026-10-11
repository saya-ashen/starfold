package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSpacePassbyTest {
    @Test void closestApproachHeadOnGrazeWideAndReceding(){
        double radius=4096;for(double speed:new double[]{.001,1,40,4000})for(double miss:new double[]{-radius,NativeSpace.IN+200,5000}){var p=new Point(-20000,radius+miss,0);var v=new Point(speed,0,0);assertEquals(miss,NativeSpaceServer.missHeight(p,v,radius),1e-8);assertEquals(Double.POSITIVE_INFINITY,NativeSpaceServer.missHeight(p,v.scale(-1),radius));}
        assertEquals(Double.POSITIVE_INFINITY,NativeSpaceServer.missHeight(new Point(1,2,3),GroundEphemeris.ZERO,radius));
    }
    @Test void hysteresisUsesStrictEntryInclusiveExitAndRecessionClears(){
        double in=NativeSpace.IN+500,out=NativeSpace.IN+1000;assertFalse(NativeSpaceServer.inbound(false,1,in));assertTrue(NativeSpaceServer.inbound(false,1,Math.nextDown(in)));assertTrue(NativeSpaceServer.inbound(true,1,in));assertTrue(NativeSpaceServer.inbound(true,1,out));assertFalse(NativeSpaceServer.inbound(true,1,Math.nextUp(out)));for(double closing:new double[]{0,-1})assertFalse(NativeSpaceServer.inbound(true,closing,-4000));
    }
    @Test void widePassNeverAlignsOrGovernsAtAnySpeedAboveFloor(){
        for(double speed:new double[]{.001,1,40,4000})for(double x=-20000;x<=20000;x+=100){var p=new Point(x,9096,0);var v=new Point(speed,0,0);double h=p.length()-4096,closing=NativeSpaceNavigation.closing(p.scale(-1),v,GroundEphemeris.ZERO);boolean inbound=NativeSpaceServer.inbound(false,closing,NativeSpaceServer.missHeight(p,v,4096));assertFalse(inbound);assertFalse(NativeSpaceServer.align(h,closing,inbound));assertFalse(NativeSpaceServer.governed(h,inbound));assertSame(v,NativeSpaceServer.govern(v,p.scale(-1),v,h,inbound));}
    }
    @Test void headOnRetainsOldArithmeticBitForBit(){
        for(double h=NativeSpace.IN;h<20000;h+=17)for(double speed:new double[]{1,150,300,800}){var p=new Point(0,4096+h,0);var v=new Point(0,-speed/20,0);var toward=p.scale(-1);boolean inbound=NativeSpaceServer.inbound(false,speed,NativeSpaceServer.missHeight(p,v,4096));assertTrue(inbound);assertEquals(NativeSpaceServer.align(h,speed),NativeSpaceServer.align(h,speed,inbound));var radial=NativeSpaceServer.arrival(v,toward,v,h);assertEquals(NativeSpaceServer.near(radial,toward,radial,h),NativeSpaceServer.govern(v,toward,v,h,inbound));}
    }
    @Test void floorAndLateSwingAreImmediate(){
        assertFalse(NativeSpaceServer.governed(NativeSpace.IN+300,false));assertTrue(NativeSpaceServer.governed(Math.nextDown(NativeSpace.IN+300),false));assertTrue(NativeSpaceServer.align(Math.nextDown(NativeSpace.IN+300),-1,false));var v=new Point(40,0,0);assertEquals(150+500.0/3,NativeSpaceServer.govern(v,new Point(0,-1,0),v,NativeSpace.IN+500,true).length()*20,1e-10);assertEquals(150+500.0/6,NativeSpaceServer.govern(new Point(0,-40,0),new Point(0,-1,0),new Point(0,-40,0),NativeSpace.IN+500,true).length()*20,1e-10);
    }
    @Test void lockDoesNotEnterArrivalGeometryAndClientsUseServerFlag()throws Exception{
        var root=java.nio.file.Path.of("src/main/java/dev/starfold");var s=java.nio.file.Files.readString(root.resolve("NativeSpaceServer.java"));var entry=s.substring(s.indexOf("var entry=value.frame"),s.indexOf("if(v.length()>1e-9)"));assertTrue(entry.contains("NativeSpace.nearest(model,position,tick)"));assertFalse(entry.contains("targetVelocity"));assertTrue(entry.contains("value.inboundPlanet==destination"));assertFalse(s.substring(s.indexOf("@Override public CompoundTag save")).contains("inbound"));for(String file:new String[]{"NativeSpaceAttitudeClient.java","NativeSpaceNavigationClient.java"})assertTrue(java.nio.file.Files.readString(root.resolve(file)).contains("NativeSpaceClient.arriving()"));assertTrue(java.nio.file.Files.readString(root.resolve("NativeSpaceClient.java")).contains("arriving=packet.turning()"));
    }
}
