package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.ChunkPos;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Region lifecycle contracts do not boot a server or claim live Sable/chunk acceptance. */
class NativeSpacePlayerTest {
    private static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/dev/starfold/"+name+".java"));}
    private static ClassNode type(String name)throws Exception{var node=new ClassNode();try(var in=TestArchiveResources.open("/dev/starfold/"+name+".class")){new ClassReader(in).accept(node,0);}return node;}
    private static List<String> calls(String name,String method)throws Exception{var out=new ArrayList<String>();for(var m:type(name).methods)if(m.name.equals(method))for(var ins:m.instructions)if(ins instanceof MethodInsnNode c)out.add(c.name);return out;}
    private static void before(List<String> calls,String guard,String dangerous){assertTrue(calls.contains(guard),guard);assertTrue(calls.contains(dangerous),dangerous);assertTrue(calls.indexOf(guard)<calls.indexOf(dangerous),guard+" before "+dangerous);}
    @Test void frameWireIncludesReferenceAndEpochIncludingGone(){
        var frame=new NativeSpace.Frame(new Quaterniond().rotationXYZ(.2,-.7,1.1),new Point(-1234567,45004,-2123456),1);
        for(var f:Arrays.asList(frame,null)){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{
            var packet=new NativeSpacePackets.Frame(f,f==null?0:1234);NativeSpacePackets.Frame.CODEC.encode(b,packet);assertEquals(f==null?1:132,b.readableBytes());
            assertEquals(packet,NativeSpacePackets.Frame.CODEC.decode(b));assertEquals(0,b.readableBytes());
            assertEquals("starfold:native_space_v3_frame",packet.type().id().toString());
        }finally{b.release();}}
    }
    @Test void arrivalPhaseWireRoundTrips(){for(var phase:NativeArrivalState.Phase.values()){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{var packet=new NativeSpacePackets.Frame(NativeSpace.Frame.IDENTITY,20,phase==NativeArrivalState.Phase.TURN_IN||phase==NativeArrivalState.Phase.ARRIVAL,phase,.5f,phase==NativeArrivalState.Phase.NOTICE?4:0);NativeSpacePackets.Frame.CODEC.encode(b,packet);assertEquals(packet,NativeSpacePackets.Frame.CODEC.decode(b));assertEquals(0,b.readableBytes());}finally{b.release();}}}
    @Test void bothWireDecodeAndConstructionRejectEveryNonfiniteFieldAndNonunitRotation(){
        for(int index=0;index<7;index++)for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY}){
            double[] a={0,0,0,1,-2000000,45000,-2000000};a[index]=bad;
            assertThrows(IllegalArgumentException.class,()->new NativeSpacePackets.Frame(new NativeSpace.Frame(new Quaterniond(a[0],a[1],a[2],a[3]),new Point(a[4],a[5],a[6]),0),0));
            var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{b.writeBoolean(true);for(double n:a)b.writeDouble(n);b.writeInt(0);for(int i=0;i<6;i++)b.writeDouble(0);b.writeLong(0);b.writeLong(0);b.writeBoolean(false);assertThrows(IllegalArgumentException.class,()->NativeSpacePackets.Frame.CODEC.decode(b));}finally{b.release();}
        }
        for(double w:new double[]{0,2,.99}){var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{b.writeBoolean(true);for(double n:new double[]{0,0,0,w,0,0,0})b.writeDouble(n);b.writeInt(0);for(int i=0;i<6;i++)b.writeDouble(0);b.writeLong(0);b.writeLong(0);b.writeBoolean(false);assertThrows(IllegalArgumentException.class,()->NativeSpacePackets.Frame.CODEC.decode(b));}finally{b.release();}}
    }
    @Test void invalidReferenceAndEpochAreRejectedOnWire(){
        for(int planet:new int[]{-1,NativePlanetStorage.MAX_PLANETS}){
            assertThrows(IllegalArgumentException.class,()->new NativeSpace.Frame(new Quaterniond(),NativeSpace.ORIGIN,planet));
            var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{b.writeBoolean(true);for(double n:new double[]{0,0,0,1,0,0,0})b.writeDouble(n);b.writeInt(planet);for(int i=0;i<6;i++)b.writeDouble(0);b.writeLong(0);b.writeLong(0);b.writeBoolean(false);assertThrows(IllegalArgumentException.class,()->NativeSpacePackets.Frame.CODEC.decode(b));}finally{b.release();}
        }
        assertThrows(IllegalArgumentException.class,()->new NativeSpacePackets.Frame(NativeSpace.Frame.IDENTITY,-1));
    }
    @Test void receivedRotationAndShiftInvertAnActualCameraPoint(){
        var q=new Quaterniond().rotationXYZ(.7,.1,-1.2);var frame=new NativeSpace.Frame(q,new Point(NativeSpace.X,NativeSpace.Y,NativeSpace.Z),1);var packet=new NativeSpacePackets.Frame(frame,1234);
        q.identity();packet.frame().rotation().identity();var eye=new Point(42.25,-86.9,71.5);assertEquals(0,eye.sub(packet.frame().universe(frame.level(eye,NativeSpaceTest.tilted(),1234),NativeSpaceTest.tilted(),1234)).length(),1e-8);
    }
    @Test void regionStepPrecedesArrivalAndKeepsAllBodiesAndRidersTogether()throws Exception{
        before(calls("NativeSableFrames","cross"),"step","arrive");var step=calls("NativeSpaceServer","step");
        for(String name:new String[]{"getAllSubLevels","isRemoved","holds","players","aboard","universe","nearest","nearer","toward","gain","dragged","getUniversalDrag","setDirty","addLinearAndAngularVelocity","getDeltaMovement","setDeltaMovement","send","anchor","shifted","motion","move"})assertTrue(step.contains(name),name);
        before(step,"gain","addLinearAndAngularVelocity");before(step,"gain","setDeltaMovement");before(step,"setDeltaMovement","dragged");before(step,"dragged","toward");assertFalse(step.contains("velocityShift"));before(step,"anchor","shifted");assertTrue(step.lastIndexOf("shifted")<step.lastIndexOf("move"));
        var source=source("NativeSpaceServer");assertTrue(source.contains("player.hurtMarked=true"));assertTrue(source.contains("-v.x()*20,-v.y()*20,-v.z()*20"));assertTrue(source.contains("motion.spin()),true"));
        assertTrue(source("NativeSableFrames").contains("tracked||keepRiderVelocity"));assertTrue(source.contains("body!=null&&bodies.contains(body)&&NativeSableFrames.tracked(entity,body)"));
    }
    @Test void loginRestoresOnlyControlledNavigationAndOtherwiseCapturesVanilla()throws Exception{
        assertFalse(NativeGroundFlightServer.restores(null));
        for(double h:new double[]{0,NativeGroundDynamics.HANDOFF,1315,6000})for(boolean flying:new boolean[]{false,true}){
            var state=NativeGroundDynamics.local(900,new NativeGroundTopology.Pose(CubeSurfaceTopology.Face.TOP,new Point(1024,h,1024)),new Point(.1,.2,.3),new Quaterniond(),flying,NativeGroundDynamics.OFF);
            assertEquals(flying&&h>NativeGroundDynamics.HANDOFF,NativeGroundFlightServer.restores(state));
            if(h==6000)assertTrue(NativeGroundFlightServer.restores(NativeGroundDynamics.detach(NativeSpaceTest.tilted(),state)));
        }
        var login=calls("NativeGroundFlightServer","login");before(login,"restores","entry");before(login,"entry","enter");assertTrue(login.contains("site"));assertFalse(login.contains("apply"));
        var site=calls("NativeGroundFlightServer","site");assertTrue(site.contains("capture"));assertFalse(site.contains("apply"));assertFalse(site.contains("teleport"));
        assertTrue(calls("NativeGroundFlightServer","tick").contains("login"));assertTrue(source("NativeGroundFlightServer").contains("if(restores(saved))enter(player,clock,entry(player,player.serverLevel(),clock));else site(player)"));
    }
    @Test void regionIdentityLeavesPacketDirectionsAndSablePoseExactlyAlone(){
        var frame=NativeFrame.identity(0,0);var at=new Point(NativeSpace.X+12.5,NativeSpace.Y+3,NativeSpace.Z-5.5);
        for(var access:new NativePlanetAccess[]{NativePlanetAccess.SHARED,NativePlanetAccess.LEGACY}){
            int turns=NativePacketMap.turns(access,frame,at.x(),at.y(),at.z());assertEquals(0,turns);assertEquals(at,frame.toClient(at));assertEquals(at,frame.toStorage(at));
            var pose=new dev.ryanhcode.sable.companion.math.Pose3d(new Vector3d(at.x(),at.y(),at.z()),new Quaterniond().rotationXYZ(.4,.3,.2),new Vector3d(3,4,5),new Vector3d(1,1,1));
            assertSame(pose,NativeSableFrames.pose(at,turns,pose));assertEquals(37,frame.yawToClient(37));
        }
    }
    @Test void captureEntryAndControlPathsGuardBeforeChartWork()throws Exception{
        for(var pair:new String[][]{{"capture","body"},{"entry","find"},{"enter","capture"},{"restart","setup"},{"tick","isSpectator"},{"checkpoint","capture"},{"site","capture"},{"orbit","view"},{"guidance","guidance"},{"target","get"}}){
            var c=calls("NativeGroundFlightServer",pair[0]);assertTrue(c.contains("in"),pair[0]);before(c,"in",pair[1]);
        }
        for(String method:new String[]{"session","aloft","controlled","position"})assertTrue(calls("NativeGroundFlightServer",method).contains("in"));
    }
    @Test void regionEndingNeverMovesOrCapturesAndDisconnectSavesWithoutRecapture()throws Exception{
        var c=calls("NativeGroundFlightServer","region");assertTrue(c.containsAll(List.of("remove","revoke")));
        for(String forbidden:new String[]{"capture","apply","teleport","teleportTo","setPos","setDeltaMovement","put"})assertFalse(c.contains(forbidden),forbidden);
        before(calls("NativeGroundFlightServer","detach"),"checkpoint","save");
        assertTrue(source("NativeGroundServer").contains("List.copyOf(server.overworld().players())"),"automatic home arrival only scans overworld, never region residents");
        before(calls("NativeGroundFlightServer","checkpoint"),"in","capture");
    }
    @Test void carriedLoginRespawnAndFrameReplacementAllAnnouncePassiveFrame()throws Exception{
        var s=source("NativeSpaceServer");for(String event:new String[]{"PlayerLoggedInEvent","PlayerRespawnEvent","PlayerChangedDimensionEvent"})assertTrue(s.contains(event));
        before(calls("NativeSpaceServer","sync"),"region","announce");assertTrue(calls("NativeSpaceServer","sync").contains("send"));
        before(calls("NativeSpaceServer","entering"),"keeping","send");
        var carry=calls("NativeGroundSeams","carryBody");before(carry,"resetPosition","sync");assertFalse(carry.contains("teleport"));before(carry,"sync","site");assertTrue(carry.contains("move"));
        before(calls("NativeFrames","fresh"),"in","storagePlanet");before(calls("NativeFrames","get"),"in","identity");
        assertTrue(source("NativeFrames").contains("NativeSpace.holds(target.x(),target.z())?NativeFrame.identity(0,0)"));
    }
    @Test void chartViewRemoteCommandsAndDaylightAreExcludedBeforePlanetLookup()throws Exception{
        before(calls("NativeGroundViewServer","belongs"),"in","planet");before(calls("NativeGroundViewServer","tick"),"in","planet");before(calls("NativePlanetRemoteServer","tick"),"in","planet");
        for(String method:new String[]{"face","status","universe"})before(calls("NativeGroundServer",method),"in","planet");
        before(calls("NativeGroundDaylight","elevation"),"holds","get");assertTrue(calls("NativeGroundDaylight","dawn").contains("holds"));
        before(calls("NativeGroundSeams","image"),"in","shape");assertTrue(source("NativeGroundClient").contains("!active()||NativeSpace.holds(mc.player.getX(),mc.player.getZ())"));
    }
    @Test void regionChunkWindowIsSmallContainsThePlayerAndNeverAnyChart(){
        for(int radius:new int[]{2,8,16,32})for(int x:new int[]{NativeSpace.X,NativeSpace.X-NativeSpace.REACH,NativeSpace.X+NativeSpace.REACH-1}){
            var center=new ChunkPos(x>>4,NativeSpace.Z>>4);var window=NativeImageStream.regionPositions(center,radius);assertTrue(window.contains(center.toLong()));assertTrue(window.size()<=(2*radius+3)*(2*radius+3));
            for(long key:window){var pos=new ChunkPos(key);assertTrue(NativeSpace.holds(pos.getMinBlockX(),pos.getMinBlockZ()));assertTrue(NativeSpace.holds(pos.getMaxBlockX(),pos.getMaxBlockZ()));assertEquals(-1,NativePlanetStorage.find(pos.getMinBlockX(),pos.getMinBlockZ()));}
        }
    }
    @Test void ticketsLoadRealRegionColumnsAndExistingIdentitySenderDeliversThem()throws Exception{
        var tickets=source("NativeGroundTickets");assertTrue(tickets.contains("if(NativeSpaceServer.in(player)){region.addAll(NativeImageStream.regionPositions"));assertTrue(tickets.contains("addRegionTicket(SPACE,new ChunkPos(key),0,key)"));assertTrue(tickets.contains("removeRegionTicket(SPACE,new ChunkPos(key),0,key)"));
        var stream=source("NativeImageStream");assertTrue(stream.contains("NativeSpaceServer.in(player)?regionPositions(center,radius):positions(center,radius)"));assertTrue(stream.contains("new ClientboundForgetLevelChunkPacket"));assertTrue(stream.contains("getChunkNow"));assertTrue(stream.contains("if(identity(columns))"));assertTrue(stream.contains("new ClientboundLevelChunkWithLightPacket(columns[0].chunk"));
        assertTrue(source("mixin/NativeGroundChunkSenderMixin").contains("NativeImageStream.active(player)"));assertTrue(source("mixin/NativeGroundChunkMapMixin").contains("NativeGroundFlightServer.aloft(player)"));
    }
    @Test void gravityUsesOnlyTransientMultiplicativeModifierOnLivingEntities()throws Exception{
        var s=source("NativeSpaceServer");for(String text:new String[]{"LevelTickEvent.Pre","instanceof net.minecraft.world.entity.LivingEntity living","Attributes.GRAVITY","in(entity)&&!weighted(entity)","getTrackingSubLevel(entity)!=null","NativeSableFrames.inside(body,entity)","space_weightless","-1,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL","addTransientModifier","removeModifier"})assertTrue(s.contains(text),text);
        assertFalse(s.contains("setBaseValue"));assertFalse(s.contains("addPermanentModifier"));assertFalse(s.contains("setNoGravity"));
    }
    @Test void transientGravityIsZeroIdempotentNotSavedAndRestoresOtherModifiers(){
        var gravity=new net.minecraft.world.entity.ai.attributes.AttributeInstance(net.minecraft.world.entity.ai.attributes.Attributes.GRAVITY,h->{});
        gravity.setBaseValue(.12);var other=new net.minecraft.world.entity.ai.attributes.AttributeModifier(net.minecraft.resources.ResourceLocation.parse("test:other_gravity"),.04,net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE);gravity.addPermanentModifier(other);
        double before=gravity.getValue();var saved=gravity.save();NativeSpaceServer.weight(gravity,true);NativeSpaceServer.weight(gravity,true);
        assertEquals(0,gravity.getValue());assertEquals(.12,gravity.getBaseValue());assertEquals(saved,gravity.save());assertEquals(2,gravity.getModifiers().size());
        NativeSpaceServer.weight(gravity,false);assertEquals(before,gravity.getValue());assertEquals(1,gravity.getModifiers().size());assertTrue(gravity.hasModifier(other.id()));NativeSpaceServer.weight(null,true);
    }
    @Test void regionLeaveCannotSendAPlayerHome()throws Exception{before(calls("NativeGroundServer","leave"),"in","detach");before(calls("NativeGroundServer","leave"),"regionMessage","restore");}
    @Test void clientAndProbeArePassiveAndClearOnLogoutAndLevelChanges()throws Exception{
        var s=source("NativeSpaceClient");for(String text:new String[]{"LoggingOut","level!=Minecraft.getInstance().level","NativeGroundFlightClient.region()","frame.universe(new Point(levelCameraPosition.x,levelCameraPosition.y,levelCameraPosition.z),NativeGroundClient.ephemeris(),time())"})assertTrue(s.contains(text));
        for(String forbidden:new String[]{"setPos","teleport","camera.rotation","NativeGroundClient.ready"})assertFalse(s.contains(forbidden));
        assertEquals(List.of("departing","reset"),calls("NativeGroundFlightClient","region"));
        var probe=source("DevProbe");for(String text:new String[]{"case \"region\"","case \"chart\""," | space %.6f %.6f %.6f ref %d w %.6f","NativeSpaceClient.universeEye(position)","case \"chart!=\"","case \"planet!=\""})assertTrue(probe.contains(text));
    }
}
