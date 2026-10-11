package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeDepartureTest {
    private NativeArrivalState depart(){return NativeArrivalState.departed(1,"ship");}
    private NativeArrivalState step(NativeArrivalState s,double h,boolean backstop){return s.step(1,"ship",h,300,true,backstop,false,false,true);}
    @Test void pointingThroughDepartureDoesNotStartNotice(){var s=depart();for(int i=0;i<140;i++){s=step(s,NativeSpace.OUT,false);assertEquals(NativeArrivalState.Phase.CRUISE,s.phase());}}
    @Test void backstopStillFires(){assertTrue(step(depart(),NativeSpace.IN+299,true).latched());}
    @Test void explicitDepartureLockRestoresNormalRule(){assertEquals(NativeArrivalState.Phase.NOTICE,step(depart().lock(1,"ship"),NativeSpace.OUT,false).phase());assertEquals(NativeArrivalState.Phase.CRUISE,step(depart().lock(0,"ship"),NativeSpace.OUT,false).phase());}
    @Test void otherPlanetMayNoticeWithoutForgettingDeparture(){
        var s=depart().step(2,"ship",2500,300,true,false,false,false,true);
        assertEquals(NativeArrivalState.Phase.NOTICE,s.phase());assertEquals(depart().departure(),s.departure());
        assertEquals(NativeArrivalState.Phase.CRUISE,step(s,2500,false).phase());
        assertEquals(NativeArrivalState.Phase.NOTICE,depart().step(1,"other",2500,300,true,false,false,false,true).phase());
    }
    @Test void anyChartEntryClearsDeparture(){
        var s=depart().step(2,"ship",1500,0,false,false,false,true,true);
        assertNull(s.departure());assertEquals(NativeArrivalState.Phase.NOTICE,step(s,2500,false).phase());
    }
    @Test void neitherSixtySecondsNorClimbingBeyondOldGraceEndsSuppression(){
        var s=depart();for(int i=0;i<2400;i++){
            s=step(s,i<1200?2500:NativeSpace.OUT+1500+10000,false);
            assertEquals(NativeArrivalState.Phase.CRUISE,s.phase());assertEquals(depart().departure(),s.departure());
        }
        assertEquals(NativeArrivalState.Phase.CRUISE,step(s,2500,false).phase());
    }
    @Test void lockedTargetAloneMayNoticeButBackstopIgnoresLock(){
        var s=depart();
        assertEquals(NativeArrivalState.Phase.CRUISE,s.step(2,"ship",2500,300,true,false,false,false,true,3).phase());
        assertEquals(NativeArrivalState.Phase.NOTICE,s.step(3,"ship",2500,300,true,false,false,false,true,3).phase());
        assertEquals(NativeArrivalState.Phase.NOTICE,s.step(2,"ship",2500,300,true,false,false,false,true,-1).phase());
        for(int planet:new int[]{1,2})assertTrue(s.step(planet,"ship",NativeSpace.IN+299,0,true,true,false,false,true,3).latched());
        assertEquals(NativeArrivalState.Phase.NOTICE,s.lock(1,"ship").step(1,"ship",2500,300,true,false,false,false,true,1).phase());
    }
    @Test void lockChangeDoesNotBreakExistingLatch(){
        var s=depart().step(2,"ship",NativeSpace.IN+299,0,false,true,false,false,true,-1);
        for(int i=0;i<100;i++)s=s.step(2,"ship",NativeSpace.IN+299,0,false,false,false,false,true,3);
        assertTrue(s.latched());assertEquals(depart().departure(),s.departure());
    }
    @Test void saveReloadKeepsFlightLongDepartureAndLatchedIdentity()throws Exception{
        var server=new NativeSpaceServer(NativeSpace.Frame.IDENTITY);var field=NativeSpaceServer.class.getDeclaredField("arrivalState");field.setAccessible(true);
        var s=depart();for(int i=0;i<1300;i++)s=step(s,NativeSpace.OUT+2000,false);
        for(var state:new NativeArrivalState[]{s,s.step(2,"ship",NativeSpace.IN+299,0,false,true,false,false,true)}){
            field.set(server,state);var tag=server.save(new net.minecraft.nbt.CompoundTag(),null);
            var restored=(NativeArrivalState)field.get(NativeSpaceServer.load(tag,null));
            assertEquals(state.departure(),state.restored().departure());assertEquals(state.departure(),restored.departure());
            if(state.latched()){assertTrue(restored.latched());assertEquals(2,restored.planet());}
            else assertEquals(NativeArrivalState.Phase.CRUISE,step(restored,2500,false).phase());
            tag.putInt("departureTicks",1); // Old dev.79 saves migrate without a remaining-time expiry.
            assertNotNull(((NativeArrivalState)field.get(NativeSpaceServer.load(tag,null))).departure());
        }
    }
    @Test void occlusionGeometry(){var ship=new Point(0,0,0);var target=new Point(0,0,100);assertTrue(NativeSpaceNavigation.occludes(ship,target,new Point(0,0,50),10));assertTrue(NativeSpaceNavigation.occludes(ship,target,new Point(10,0,50),10));assertFalse(NativeSpaceNavigation.occludes(ship,target,new Point(10.01,0,50),10));assertFalse(NativeSpaceNavigation.occludes(ship,target,new Point(0,0,-50),10));assertFalse(NativeSpaceNavigation.occludes(ship,target,new Point(0,0,150),10));assertFalse(NativeSpaceNavigation.occludes(ship,target,target,10));assertFalse(NativeSpaceNavigation.occludes(ship,ship,new Point(0,0,10),10));assertFalse(NativeSpaceNavigation.occludes(ship,target,new Point(90,0,90),100));}
    @Test void bilingualHintAndWiring()throws Exception{
        for(String lang:new String[]{"en_us","zh_cn"}){var json=NativeFlightPermitTest.json("assets/starfold/lang/"+lang+".json");assertEquals(lang.equals("en_us")?"blocked by %s · go around":"被 %s 遮挡 · 绕行",json.get("hud.starfold.instrument_blocked").getAsString());}
        var root=java.nio.file.Path.of("src/main/java/dev/starfold");
        assertTrue(java.nio.file.Files.readString(root.resolve("NativeSableFrames.java")).contains("NativeSpaceServer.departed(level,planet,body.getUniqueId().toString())"));
        assertTrue(java.nio.file.Files.readString(root.resolve("NativeSableFrames.java")).contains("NativeSpaceServer.enteredChart(level,body.getUniqueId().toString())"));
        var server=java.nio.file.Files.readString(root.resolve("NativeSpaceServer.java"));
        assertTrue(server.contains("target!=null&&validTarget(player,target))lockedPlanet=target.planet()"));
        assertTrue(server.contains("abort,false,remaining<.1,lockedPlanet)"));
        assertTrue(server.contains("value.arrivalState=s.step(s.planet(),id,0,0,false,false,false,true,true)"));
        var client=java.nio.file.Files.readString(root.resolve("NativeSpaceNavigationClient.java"));assertTrue(client.contains("tier>=2&&t.planet()==locked?NativeSpaceNavigation.blocker(model,pivot,locked,tick):-1"));
        var widgets=java.nio.file.Files.readString(root.resolve("NativeFlightWidgets.java"));assertTrue(widgets.contains("t.blocker()>=0&&i%4>=2"));
    }
    @Test void longerCaptionHasSeparateBoundedRow(){for(int[] size:new int[][]{{1280,720},{1920,1080},{854,480}})for(int scale:new int[]{2,3,4}){var l=NativeHudLayout.physical(size[0],size[1],scale);var row=NativeHudLayout.occlusion(l);assertTrue(row.x()>=0&&row.right()<=l.width());assertTrue(row.y()>l.view().bottom());assertTrue(row.bottom()<l.height()-82);assertTrue(row.width()>=180);}}
}
