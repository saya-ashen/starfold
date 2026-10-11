package dev.starfold;

import org.junit.jupiter.api.Test;
import net.minecraft.world.level.GameType;
import static org.junit.jupiter.api.Assertions.*;

class NativeFirstTripTest {
    @Test void departureIsLastAndAllTargetsRemain(){
        var model=NativePlanetSystem.model();var at=model.at(0,0).center();
        var order=NativeSpaceNavigation.cycle(model,at,0,0);
        assertEquals(model.count(),order.size());assertEquals(0,order.getLast());assertNotEquals(0,order.getFirst());
        int locked=-1;for(int id:order){locked=NativeSpaceNavigation.next(model,at,0,locked,0);assertEquals(id,locked);}
        assertEquals(-1,NativeSpaceNavigation.next(model,at,0,locked,0));
        assertEquals(order.getFirst(),NativeSpaceNavigation.next(model,at,0,999,0));
        assertEquals(NativeSpaceView.nearest(model,at,0,model.count()),NativeSpaceNavigation.cycle(model,at,0,-1));
    }
    @Test void unsafePlanetExitUsesSameWindowButSafeStairIsImmediate(){
        for(var mode:new GameType[]{GameType.SURVIVAL,GameType.ADVENTURE}){
            var rule=new NativeHelmExit();assertFalse(rule.leave(mode,false,false,100));assertTrue(rule.leave(mode,false,false,159));
            assertTrue(rule.leave(mode,false,true,160));assertFalse(rule.leave(mode,true,true,161));
        }
        assertTrue(new NativeHelmExit().leave(GameType.CREATIVE,false,false,0));
        var rule=new NativeHelmExit();assertFalse(rule.leave(GameType.SURVIVAL,false,false,0));assertFalse(rule.leave(GameType.SURVIVAL,false,false,60));
    }
    @Test void footprintUsesEveryColumnAndFailsClosed(){
        assertEquals(NativeLanding.Kind.LAND,NativeLanding.survey(0,0,5,5,(x,z)->new NativeLanding.Surface(100,false)).kind());
        assertEquals(NativeLanding.Kind.WATER,NativeLanding.survey(0,0,5,5,(x,z)->new NativeLanding.Surface(100,x==1&&z==1)).kind());
        assertEquals(NativeLanding.Kind.LAVA,NativeLanding.survey(0,0,5,5,(x,z)->new NativeLanding.Surface(100,true,true)).kind());
        assertEquals(NativeLanding.Kind.SLOPE,NativeLanding.survey(0,0,5,5,(x,z)->new NativeLanding.Surface(x==1?111:100,false)).kind());
        assertEquals(NativeLanding.Kind.LAND,NativeLanding.survey(0,0,5,5,(x,z)->new NativeLanding.Surface(x==1?110:100,false)).kind());
        assertEquals(NativeLanding.Kind.UNKNOWN,NativeLanding.survey(0,0,5,5,(x,z)->new NativeLanding.Surface(Double.NaN,false)).kind());
    }
    @Test void preciseChartOnlyBelowTwoHundred(){
        for(int tier=0;tier<4;tier++)for(var mode:NativeFlightSnapshot.Mode.values()){
            assertEquals(tier==3&&mode==NativeFlightSnapshot.Mode.CHART,NativeLanding.showSurface(tier,mode,199.99));
            assertFalse(NativeLanding.showSurface(tier,mode,200));assertFalse(NativeLanding.showSurface(tier,mode,Double.NaN));
        }
    }
    @Test void hoveringWithStairsIsStillNotLanded(){
        assertFalse(NativeLanding.safeExit(5,true));assertFalse(NativeLanding.safeExit(0,false));assertFalse(NativeLanding.safeExit(Double.NaN,true));
        assertTrue(NativeLanding.safeExit(0,true));assertTrue(NativeLanding.safeExit(-.1,true));
        for(var kind:new NativeLanding.Kind[]{NativeLanding.Kind.WATER,NativeLanding.Kind.LAVA,NativeLanding.Kind.UNKNOWN})assertFalse(NativeLanding.safeExit(0,true,kind));
    }
    @Test void landingDataRoundTripsInsideExistingHelmPacket(){
        for(var kind:NativeLanding.Kind.values())for(boolean can:new boolean[]{false,true}){
            var buf=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
            try{var packet=new NativeHelmPackets.State(java.util.UUID.randomUUID(),net.minecraft.core.BlockPos.ZERO,net.minecraft.core.Direction.NORTH,true,true,0,false,0,0,0,new NativeLanding.Site(kind,can));
                NativeHelmPackets.State.CODEC.encode(buf,packet);assertEquals(packet,NativeHelmPackets.State.CODEC.decode(buf));assertEquals(0,buf.readableBytes());
            }finally{buf.release();}
        }
    }
    @Test void surfaceRowsClearModeViewAndSurvivalBars(){
        for(int[] size:new int[][]{{1280,720},{1920,1080},{854,480}})for(int scale:new int[]{2,3,4}){
            var l=NativeHudLayout.physical(size[0],size[1],scale);
            assertTrue(54>l.view().bottom());assertTrue(65+9<l.height()-49);
            assertTrue(Math.min(180,l.width()-32)>=150);
        }
    }
    @Test void wireAndPlannerAreShared()throws Exception{
        var root=java.nio.file.Path.of("src/main/java/dev/starfold");
        var server=java.nio.file.Files.readString(root.resolve("NativeHelmServer.java"));assertTrue(server.contains("NativeLanding.site(s.player.serverLevel(),s.body)"));
        var stairs=java.nio.file.Files.readString(root.resolve("NativeAirstair.java"));assertTrue(stairs.contains("plan(level,stair,body,at,true)"));assertTrue(stairs.contains("chart?plan(server,stair,body,at,gravity):null"));assertTrue(stairs.contains("if(!state.getFluidState().isEmpty())return null"));
        var packet=java.nio.file.Files.readString(root.resolve("NativeHelmPackets.java"));assertTrue(packet.contains("b.writeEnum(p.landing.kind())"));assertTrue(packet.contains("b.readEnum(NativeLanding.Kind.class)"));
        for(String lang:new String[]{"en_us","zh_cn"}){var json=NativeFlightPermitTest.json("assets/starfold/lang/"+lang+".json");for(String key:new String[]{"below_land","below_water","below_lava","below_slope","below_unknown","stair_yes","stair_no"})assertTrue(json.has("hud.starfold.instrument_"+key));assertTrue(json.has("message.starfold.helm_exit_stair"));}
    }
    @Test void bothBooksGiveTheNextActionAndLandingCue(){
        assertTrue(NativeStarterBooks.pages("zh_cn").getFirst().endsWith("走上舷梯，右键船头的控制器"));
        assertTrue(NativeStarterBooks.pages("en_us").getFirst().endsWith("Walk up the airstair; right-click the bow controller."));
        assertTrue(NativeStarterBooks.pages("zh_cn").get(4).contains("最近的其他星球"));
        assertTrue(NativeStarterBooks.pages("en_us").get(4).contains("nearest other planet"));
        assertTrue(NativeStarterBooks.pages("zh_cn").get(7).contains("下方"));assertTrue(NativeStarterBooks.pages("en_us").get(7).contains("Below"));
    }
}
