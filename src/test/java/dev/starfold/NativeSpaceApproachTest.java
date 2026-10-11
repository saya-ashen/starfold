package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeSpaceApproachTest {
    private static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/dev/starfold/"+name+".java"));}
    private static List<String> calls(String name,String method)throws Exception{var node=new ClassNode();try(var in=TestArchiveResources.open("/dev/starfold/"+name+".class")){new ClassReader(in).accept(node,0);}var out=new ArrayList<String>();for(var m:node.methods)if(m.name.equals(method))for(var i:m.instructions)if(i instanceof MethodInsnNode c)out.add(c.owner+"."+c.name);return out;}
    @Test void predictedColumnUsesTheCurrentRotatingPlanetAndEveryFace(){
        var model=NativeSpaceTest.tilted();for(int planet=0;planet<2;planet++)for(var shape:NativeSpaceTest.SHAPES)for(var face:Face.values())for(double tick:new double[]{0,1234,9876}){
            var pose=new NativeGroundTopology.Pose(face,new Point(shape.size()*.27,1500,shape.size()*.73));var motion=NativeSpaceTest.chart(pose.point());
            var universe=NativeSpace.out(model,planet,shape,pose,motion,tick);var predicted=NativeSpace.in(model,planet,shape,universe,tick);
            var anchor=NativePlanetAccess.SHARED.bodyAnchor(planet,predicted.pose());var origin=NativePlanetStorage.origin(planet);
            assertEquals(new ChunkPos((int)Math.floor(origin.x()+face.ordinal()*4096+pose.point().x())>>4,(int)Math.floor(origin.z()+pose.point().z())>>4),NativeSpaceApproach.column(anchor));
        }
    }
    @Test void forecastUsesGovernorsAndFutureSpinWithoutChangingReadiness(){
        var model=NativeSpaceTest.tilted();var shape=NativePlanetShape.STANDARD;double tick=1234;
        for(int planet=0;planet<2;planet++)for(var face:Face.values()){
            var pose=new NativeGroundTopology.Pose(face,new Point(shape.size()*.3,6000,shape.size()*.6));
            var start=NativeSpace.out(model,planet,shape,pose,NativeSpaceTest.chart(pose.point()),tick);
            var up=NativeSpace.up(model,planet,start.position(),tick);var toward=new Point(-up.x,-up.y,-up.z);
            var motion=new NativeSpace.Motion(start.position(),start.orientation(),toward.scale(40).add(model.at(planet,tick).velocity()),start.spin());
            var predicted=NativeSpaceApproach.predict(model,planet,shape,motion,tick);
            var position=motion.position().sub(model.at(planet,tick).center());var velocity=toward.scale(40);int steps=0;
            while(position.length()-shape.radius()>NativeSpace.IN){double height=position.length()-shape.radius();var radial=position.scale(-1/position.length());velocity=radial.scale(Math.min(velocity.dot(radial),(150+Math.max(0,height-NativeSpace.IN)/6)/20));position=position.add(velocity);steps++;assertTrue(steps<600);}
            var expected=NativeSpace.in(model,planet,shape,new NativeSpace.Motion(position.add(model.at(planet,tick+steps).center()),motion.orientation(),velocity.add(model.at(planet,tick+steps).velocity()),motion.spin()),tick+steps);
            assertEquals(expected.pose().face(),predicted.pose().face());assertTrue(expected.pose().point().sub(predicted.pose().point()).length()<1e-7);
            assertTrue(predicted.pose().point().sub(NativeSpace.in(model,planet,shape,motion,tick).pose().point()).length()>1000);
        }
        assertTrue(NativeSpaceApproach.PREPARE>NativeSpaceApproach.APPROACH);
    }
    @Test void forecastIsBoundedForStationaryAndRetreatingShips(){
        var model=NativeSpaceTest.tilted();var shape=NativePlanetShape.STANDARD;double tick=456;
        var pose=new NativeGroundTopology.Pose(Face.values()[0],new Point(500,7000,700));var start=NativeSpace.out(model,0,shape,pose,NativeSpaceTest.chart(pose.point()),tick);
        var up=NativeSpace.up(model,0,start.position(),tick);
        for(double speed:new double[]{0,10}){var motion=new NativeSpace.Motion(start.position(),start.orientation(),new Point(up.x,up.y,up.z).scale(speed).add(model.at(0,tick).velocity()),start.spin());assertEquals(NativeSpace.in(model,0,shape,motion,tick).pose(),NativeSpaceApproach.predict(model,0,shape,motion,tick).pose());}
    }
    @Test void patchHysteresisRetainsOneChunkWobblesButFollowsRealMotion(){
        var old=new ChunkPos(20,-10);for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)assertSame(old,NativeSpaceApproach.stable(old,new ChunkPos(old.x+x,old.z+z)));
        var next=new ChunkPos(22,-10);assertSame(next,NativeSpaceApproach.stable(old,next));assertSame(next,NativeSpaceApproach.stable(null,next));
    }
    @Test void regionReleaseIsBoundedDrainsAndReticketingWins(){
        Set<Long> held=new LinkedHashSet<>();for(long i=0;i<625;i++)held.add(i);int ticks=0;
        while(!held.isEmpty()){int before=held.size();held=NativeGroundTickets.retiring(held,Set.of());assertEquals(Math.min(8,before),before-held.size());assertTrue(++ticks<80);}
        assertEquals(79,ticks);held=new LinkedHashSet<>();for(long i=0;i<100;i++)held.add(i);var desired=Set.of(0L,1L,200L);
        for(int i=0;i<20;i++){held=NativeGroundTickets.retiring(held,desired);assertTrue(held.containsAll(desired));}assertEquals(desired,held);
    }
    @Test void columnsFloorRatherThanTruncateAtNegativeAndChunkBoundaries(){
        for(double x:new double[]{-16.001,-16,-.001,0,15.999,16})for(double z:new double[]{-16.001,-16,-.001,0,15.999,16})assertEquals(new ChunkPos((int)Math.floor(x/16),(int)Math.floor(z/16)),NativeSpaceApproach.column(new Point(x,2000,z)));
    }
    @Test void readinessIsStrictAndTheEmergencyFloorNeverWaits(){
        for(boolean full:new boolean[]{false,true}){assertFalse(NativeSpaceApproach.ready(NativeSpace.IN,full));assertFalse(NativeSpaceApproach.ready(NativeSpace.IN+1,full));assertTrue(NativeSpaceApproach.ready(NativeSpace.IN-512-.001,full));}
        assertFalse(NativeSpaceApproach.ready(NativeSpace.IN-512,false));assertTrue(NativeSpaceApproach.ready(NativeSpace.IN-512,true));assertFalse(NativeSpaceApproach.ready(NativeSpace.IN-.001,false));assertTrue(NativeSpaceApproach.ready(NativeSpace.IN-.001,true));
    }
    @Test void preparationIsAsyncFullAndCoversHullAndCanonicalImages()throws Exception{
        var s=source("NativeSpaceApproach");for(String text:List.of("APPROACH=4096","RADIUS=3,INTERVAL=10","tick-old.tick()<INTERVAL","NativeSpace.in(","access.bodyAnchor(planet,arrival.pose())","lead.boundingBox()","i<8","minX-1","maxX+1","NativeChunkViews.columns(access,x,z).owners()","addRegionTicket(TYPE,new ChunkPos(key),0,key)","removeRegionTicket(TYPE,new ChunkPos(key),0,key)"))assertTrue(s.contains(text),text);
        assertTrue(s.contains("for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)"));assertTrue(calls("NativeSpaceApproach","full").stream().anyMatch(c->c.endsWith(".getChunkNow")));assertFalse(s.contains("getChunk("));
        var arrive=calls("NativeSableFrames","arrive");int ready=arrive.indexOf("dev/starfold/NativeSpaceApproach.ready"),move=arrive.indexOf("dev/starfold/NativeSableFrames.move");assertTrue(ready>=0&&ready<move);assertTrue(arrive.indexOf("dev/starfold/NativeSpaceApproach.arrived")>move);
    }
    @Test void ticketsAreReleasedOnEveryExitAndShutdown()throws Exception{
        var s=source("NativeSpaceApproach");assertTrue(s.contains("old.body()!=lead||old.body().isRemoved()"));assertTrue(s.indexOf("old.body().isRemoved()")<s.indexOf("tick-old.tick()<INTERVAL"));assertTrue(s.contains(">=PREPARE){release(level);return;}"));assertTrue(s.contains("if(value!=null&&value.body()==body)release(level)"));assertTrue(s.contains("held.remove(level)"));assertTrue(s.contains("List.copyOf(held.keySet()))release(level)"));
        assertTrue(source("NativeSpaceServer").contains("value.inboundPlanet=-1;value.arrivalState=NativeArrivalState.empty();value.setDirty();NativeSpaceApproach.update(level,container,null);return;}"));assertTrue(source("NativeSpaceServer").contains("LevelEvent.Unload"));assertTrue(source("NativeSableFrames").contains("if(container==null){NativeSpaceApproach.release(level);return;}"));assertTrue(calls("NativeGroundTickets","clear").contains("dev/starfold/NativeSpaceApproach.clear"));
    }
    @Test void passiveArrivalMeshSurvivesAndUsesTheChartPlacement()throws Exception{
        var s=source("NativePlanetRemoteRenderer");assertTrue(s.contains("NativeSpaceClient.retainedSession()"));assertTrue(s.contains("body.fallback==null&&!NativeGroundRenderer.selectionReady()"));assertTrue(s.contains("e.getKey()==NativeGroundClient.planet()&&!NativeGroundRenderer.selectionReady()"));assertTrue(calls("NativePlanetRemoteRenderer","drawBody").contains("dev/starfold/NativeGroundClient.place"));
        var client=source("NativeSpaceClient");assertTrue(client.contains("if(viewLevel!=null&&viewLevel!=mc.level){NativePlanetRemoteRenderer.clear();retainedSession=0;}"));assertFalse(client.contains("view.session()!=packet.session()))NativePlanetRemoteRenderer.clear()"));
    }
    @Test void departureTransfersBuffersAndDoesNotWaitForANewSummary()throws Exception{
        assertTrue(source("NativeGroundFlightClient").contains("NativePlanetRemoteRenderer.departing();reset();"));var s=source("NativePlanetRemoteRenderer");assertTrue(s.contains("NativeGroundExterior.take(shape)"));assertTrue(s.contains("int planet=NativeGroundViewClient.cachedPlanet();if(profile==null||planet<0)return"));assertTrue(s.contains("System.arraycopy(meshes,0,body.meshes,0,meshes.length)"));assertTrue(s.contains("body.resident!=null?body.resident:body.fallback"));assertFalse(s.contains("body.view.session()!=session)continue"));
        assertTrue(source("NativeGroundExterior").contains("var out=patches.clone();Arrays.fill(patches,null);return out;"));
    }
    @Test void approachDataIsStagedWithoutAChartOrFlightSession()throws Exception{
        assertTrue(source("NativePlanetRemoteServer").contains("NativeSpaceApproach.destination(player)"));assertTrue(source("NativeGroundViewServer").contains("if(destination>=0)ticked.add(destination)"));assertTrue(source("NativeGroundViewClient").contains("NativePlanetRemoteRenderer.seedOwn(boundPlanet,id)"));assertTrue(source("NativeGroundViewClient").contains("(previewed||!coverageReady())"));assertTrue(calls("NativePlanetRemoteRenderer","seedOwn").contains("dev/starfold/NativeGroundViewClient.preview"));
        var s=source("NativeSpaceApproach");for(String forbidden:List.of("site(","capture(","setPos(","moveTo(","setup("))assertFalse(s.contains(forbidden));
    }
}
