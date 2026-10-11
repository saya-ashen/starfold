package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NativeWaypointNavigationTest {
    private NativeGroundTopology.Pose pose(Face face,double x,double z){return new NativeGroundTopology.Pose(face,new Point(x,30,z));}
    private NativeWaypoint marker(String name){return new NativeWaypoint(name,0,pose(Face.TOP,500,500));}
    @Test void metadataUsesNewChartSessionBeforeTheHullLeavesRegionCoordinates(){assertTrue(NativeWaypointNavigation.accepts(42,42,17));assertFalse(NativeWaypointNavigation.accepts(17,42,17));assertTrue(NativeWaypointNavigation.accepts(17,0,17));assertFalse(NativeWaypointNavigation.accepts(42,0,17));assertFalse(NativeWaypointNavigation.accepts(0,0,0));}
    @Test void signedTurnUsesMinecraftYawAndWraps(){
        assertEquals(-90,NativeWaypointNavigation.angle(0,new Point(1,0,0)),1e-8);
        assertEquals(90,NativeWaypointNavigation.angle(0,new Point(-1,0,0)),1e-8);
        // Continuous arrow bearings replace the old text-only left/right/ahead categories.
        for(double angle:new double[]{-180,-37,0,4,12,180})assertEquals(angle,NativeWaypointNavigation.angle(0,new Point(-Math.sin(Math.toRadians(angle)),0,Math.cos(Math.toRadians(angle)))),1e-8);
        assertEquals(2,NativeWaypointNavigation.angle(179,new Point(-Math.sin(Math.toRadians(-179)),0,Math.cos(Math.toRadians(-179)))),1e-8);
    }
    @Test void selectionAndAutomaticFallbackAreIndependent(){
        var manual=marker("base");var launch=marker(NativeWaypoint.LAUNCH);
        assertEquals("starfold:launch",launch.name());assertEquals("message.starfold.mark.invalid",assertThrows(NativeWaypoint.Failure.class,()->new NativeWaypointSave().add(UUID.randomUUID(),launch)).key);
        assertNull(NativeWaypointSave.active(null,null));assertEquals(launch,NativeWaypointSave.active(null,launch));assertEquals(manual,NativeWaypointSave.active(manual,launch));
        var save=new NativeWaypointSave();var id=UUID.randomUUID();save.add(id,manual);save.select(id,"base");save.launch(id,launch);assertEquals(manual,save.active(id));save.remove(id,"base");assertEquals(launch,save.active(id));
    }
    @Test void launchSlotRoundTripsAndDoesNotConsumeNamedCapacity()throws Exception{
        var save=new NativeWaypointSave();var id=UUID.randomUUID();save.launch(id,marker(NativeWaypoint.LAUNCH));for(int i=0;i<64;i++)save.add(id,marker("m"+i));save.select(id,"m1");
        var file=Path.of("build/native-exploration/launch-slot.dat");Files.createDirectories(file.getParent());NbtIo.writeCompressed(save.save(new CompoundTag(),null),file);
        var loaded=NativeWaypointSave.load(NbtIo.readCompressed(file,NbtAccounter.unlimitedHeap()),null);assertEquals(save.active(id),loaded.active(id));assertEquals(64,loaded.list(id).size());loaded.clear(id);assertEquals(marker(NativeWaypoint.LAUNCH),loaded.active(id));
        loaded.launch(id,marker("new_launch"));assertEquals(marker("new_launch"),loaded.active(id));assertNull(loaded.active(UUID.randomUUID()));
    }
    @Test void malformedLaunchSlotFailsClosed(){var save=new NativeWaypointSave();var id=UUID.randomUUID();save.launch(id,marker(NativeWaypoint.LAUNCH));var tag=save.save(new CompoundTag(),null);tag.getCompound("players").getCompound(id.toString()).putString("launch","bad");assertThrows(IllegalArgumentException.class,()->NativeWaypointSave.load(tag,null));}
    @Test void launchRequiresKnownRestingGround(){assertTrue(NativeWaypointNavigation.grounded(0,.1));assertFalse(NativeWaypointNavigation.grounded(2,0));assertFalse(NativeWaypointNavigation.grounded(Double.NaN,0));assertFalse(NativeWaypointNavigation.grounded(0,3));assertFalse(NativeWaypointNavigation.grounded(-20,0));}
    @Test void allFacesHaveSymmetricSurfaceRoutes(){var shape=NativePlanetShape.STANDARD;double n=shape.size();for(var a:Face.values())for(var b:Face.values()){
        var from=pose(a,n*.3,n*.4);var to=pose(b,n*.7,n*.6);var route=NativeWaypointNavigation.route(shape,from,to);assertNotNull(route,a+" -> "+b);assertEquals(route.distance(),NativeWaypointNavigation.route(shape,to,from).distance(),1e-6);assertEquals(route.distance(),route.direction().length(),1e-7);
    }}
    @Test void returnDistanceAndDirectionAreContinuousAcrossEachSeam(){var shape=NativePlanetShape.STANDARD;double n=shape.size();for(var face:Face.values())for(var step:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
        var home=pose(face,n/2,n/2);var outside=new Point(n/2+step[0]*(n/2+10),30,n/2+step[1]*(n/2+10));var transported=NativeGroundTopology.canonical(shape,face,outside);var route=NativeWaypointNavigation.route(shape,transported.pose(),home);assertNotNull(route);assertEquals(n/2+10,route.distance(),1e-6);
        var bearing=ChartLabSpace.rotate(route.direction(),-transported.turns());assertEquals(-step[0],bearing.x()/route.distance(),1e-6);assertEquals(-step[1],bearing.z()/route.distance(),1e-6);
        var inside=pose(face,n/2+step[0]*(n/2-10),n/2+step[1]*(n/2-10));assertEquals(route.distance()-20,NativeWaypointNavigation.route(shape,inside,home).distance(),1e-6);
    }}
}
