package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.starfold.NativeSpaceTest.point;

class NativeSpaceArrivalModeTest {
    @Test void tangentAddsNothingAndRadialKeepsEitherSign(){
        var toward=new Point(0,-100,0);var drift=new Point(2,-30,4);
        for(double sign:new double[]{-1,1}){point(GroundEphemeris.ZERO,NativeSpaceServer.arrivalPush(new Point(3*sign,0,4*sign),toward,true));point(new Point(0,2*sign,0),NativeSpaceServer.arrivalPush(new Point(3,2*sign,4),toward,true));point(drift,NativeSpaceServer.gain(drift,NativeSpaceServer.arrivalPush(new Point(3,0,4),toward,true),true,0));}
    }
    @Test void projectionWorksInEveryPlanetDirection(){
        var random=new java.util.Random(53);for(int i=0;i<1000;i++){var toward=new Point(random.nextDouble()-.5,random.nextDouble()-.5,random.nextDouble()-.5);var n=toward.scale(1/toward.length());var push=new Point(random.nextDouble()-.5,random.nextDouble()-.5,random.nextDouble()-.5);var kept=NativeSpaceServer.arrivalPush(push,toward,true);assertEquals(push.dot(n),kept.dot(n),1e-12);point(GroundEphemeris.ZERO,kept.sub(n.scale(kept.dot(n))));assertTrue(kept.length()<=push.length()+1e-12);}
    }
    @Test void outsideArrivalIsBitForBitIncludingLockedCruise(){
        var random=new java.util.Random(53);for(int i=0;i<1000;i++){var drift=new Point(random.nextDouble()*40,random.nextDouble()*40,random.nextDouble()*40);var impulse=new Point(random.nextDouble()-.5,random.nextDouble()-.5,random.nextDouble()-.5);var toward=new Point(2,-3,4);assertSame(impulse,NativeSpaceServer.arrivalPush(impulse,toward,false));for(boolean boost:new boolean[]{false,true})for(var target:new Point[]{GroundEphemeris.ZERO,new Point(1,2,3)}){var old=NativeSpaceServer.gain(drift,impulse,true,0);var next=NativeSpaceServer.gain(drift,NativeSpaceServer.arrivalPush(impulse,toward,false),true,0);assertEquals(old,next);assertEquals(NativeSpaceServer.cruise(drift,old,impulse,boost,target,NativeSpaceSpeed.caps(NativeSpaceSpeed.A_REF)),NativeSpaceServer.cruise(drift,next,NativeSpaceServer.arrivalPush(impulse,toward,false),boost,target,NativeSpaceSpeed.caps(NativeSpaceSpeed.A_REF)));}}
    }
    @Test void tangentialPassAtFiveThousandIsNotArrival(){assertFalse(NativeSpaceServer.align(5000,0));var v=new Point(40,0,0);assertSame(v,NativeSpaceServer.arrivalPush(v,new Point(0,-1,0),false));assertEquals(v,NativeSpaceServer.near(v,new Point(0,-1,0),v,5000));}
    @Test void integrationFiltersFoldNotHullRelabellingAndSkipsBothAssists()throws Exception{
        var s=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/starfold/NativeSpaceServer.java"));assertTrue(s.contains("boolean arriving=value.arrivalState.latched()"));assertTrue(s.contains("gain(f.drift(),blendPush(f.universeVector(v),towardEntry,blend),piloted,0)"));assertTrue(s.contains("push=blendPush(push,towardEntry,blend)"));assertTrue(s.contains("new org.joml.Vector3d(-v.x()*20,-v.y()*20,-v.z()*20)"));assertTrue(s.contains("if(!arriving)"));assertTrue(s.indexOf("if(!arriving)")<s.indexOf("assist(f.drift(),nose"));assertTrue(s.indexOf("drift=cruise(prior")<s.indexOf("var limited=govern(drift"));
        var hud=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/starfold/NativeSpaceNavigationClient.java"));assertTrue(hud.contains("NativeSpaceClient.arriving()?NativeFlightSnapshot.Mode.ARRIVAL"));var widgets=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/starfold/NativeFlightWidgets.java"));assertTrue(widgets.contains("if(arrival){if(s.blend()<1)"));assertTrue(widgets.contains("g.setColor(1,1,1,(float)s.blend())"));
    }
}
