package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.starfold.CubeSurfaceTopology.Face;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.joml.Quaterniond;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offline observer, protocol and dispatch contracts; not a claim of live Sable/GPU acceptance. */
class NativeSpaceViewTest {
    private static String source(String name)throws Exception{return Files.readString(Path.of("src/main/java/dev/starfold/"+name+".java"));}
    private static List<String> calls(String name,String method)throws Exception{var node=new ClassNode();try(var in=TestArchiveResources.open("/dev/starfold/"+name+".class")){new ClassReader(in).accept(node,0);}var out=new ArrayList<String>();for(var m:node.methods)if(m.name.equals(method))for(var ins:m.instructions)if(ins instanceof MethodInsnNode c)out.add(c.name);return out;}
    private static void before(List<String> calls,String a,String b){assertTrue(calls.contains(a),a);assertTrue(calls.contains(b),b);assertTrue(calls.indexOf(a)<calls.indexOf(b),a+" before "+b);}
    private static void point(Point a,Point b){NativeSpaceTest.point(a,b);}
    private static Point shader(NativeSpaceView.Placement p,Point local){return p.center().add(new Point(local.dot(p.u()),local.dot(p.up()),local.dot(p.v())));}
    private static final Quaterniond[] ROTATIONS={new Quaterniond(),new Quaterniond().rotationY(Math.PI/2),new Quaterniond().rotationAxis(-Math.PI/2,1,0,0),new Quaterniond().rotationXYZ(.31,-.73,1.2)};

    @Test void planetCenterHasTheKnownLevelDirectionAndDistance(){
        var eye=new Point(100,200,300);var target=eye.add(new Point(0,0,100));
        point(new Point(0,0,100),NativeSpaceView.relative(new NativeSpace.Frame(ROTATIONS[0],NativeSpace.ORIGIN,0),eye,target));
        point(new Point(100,0,0),NativeSpaceView.relative(new NativeSpace.Frame(ROTATIONS[1],NativeSpace.ORIGIN,0),eye,target));
        point(new Point(0,100,0),NativeSpaceView.relative(new NativeSpace.Frame(ROTATIONS[2],NativeSpace.ORIGIN,0),eye,target));
        for(var q:ROTATIONS)assertEquals(100,NativeSpaceView.relative(new NativeSpace.Frame(q,NativeSpace.ORIGIN,0),eye,target).length(),1e-9);
    }
    @Test void placementAgreesWithVanillaLevelDifferenceAcrossReferencePlanetsAndTicks(){
        var model=NativeSpaceTest.tilted();for(var q:ROTATIONS)for(int reference=0;reference<2;reference++)for(double tick:new double[]{0,1234.25,17001.875}){
            var frame=new NativeSpace.Frame(q,NativeSpace.ORIGIN.add(new Point(41,-87,123)),reference);var camera=NativeSpace.ORIGIN.add(new Point(71.25,12.625,-900.5));var eye=frame.universe(camera,model,tick);
            for(int id=0;id<2;id++){var target=model.at(id,tick);var p=NativeSpaceView.place(frame,eye,target);point(frame.level(target.center(),model,tick).sub(camera),p.center());assertEquals(target.center().sub(eye).length(),p.center().length(),1e-8);
                for(var vertex:new Point[]{GroundEphemeris.ZERO,new Point(1,0,0),new Point(0,1,0),new Point(0,0,1),new Point(234,-517,891)})point(frame.level(target.world(vertex),model,tick).sub(camera),shader(p,vertex));
            }
        }
    }
    @Test void subtractingBeforeFloatRetainsSubBlockMotionInLargeUniverseCoordinates(){
        var eye=new Point(999_999_999.125,-777_777_777.25,555_555_555.5);var delta=new Point(.125,.25,-.5);var target=eye.add(delta);
        assertEquals((float)eye.x(),(float)target.x());
        for(var q:ROTATIONS){var frame=new NativeSpace.Frame(q,NativeSpace.ORIGIN,0);point(frame.levelVector(delta),NativeSpaceView.relative(frame,eye,target));}
    }
    @Test void starDirectionsTurnOnlyWithRNotReferenceSpinTickOrTranslation(){
        var model=NativeSpaceTest.tilted();var star=new Point(.36,.48,.8);for(var q:ROTATIONS)for(int id=0;id<2;id++)for(double tick:new double[]{0,2345,54321}){
            var frame=new NativeSpace.Frame(q,NativeSpace.ORIGIN,id);var next=frame.toward(1-id,model,tick).shifted(new Point(123,-456,789));
            point(GroundEphemeris.rotate(q,star),next.levelVector(star));assertEquals(1,next.levelVector(star).length(),1e-10);
        }
    }
    @Test void referenceSwitchAndReanchoringDoNotJumpAnyCelestial(){
        var model=NativeSpaceTest.tilted();for(var q:ROTATIONS)for(double tick:new double[]{0,1900.5,12000}){
            var old=new NativeSpace.Frame(q,NativeSpace.ORIGIN,0);var next=old.toward(1,model,tick);var camera=NativeSpace.ORIGIN.add(new Point(123,456,789));var shift=new Point(-500,0,1200);
            for(int id=0;id<2;id++){var target=model.at(id,tick);var a=NativeSpaceView.place(old,old.universe(camera,model,tick),target);var b=NativeSpaceView.place(next,next.universe(camera,model,tick),target);var c=NativeSpaceView.place(next.shifted(shift),next.shifted(shift).universe(camera.add(shift),model,tick),target);point(a.center(),b.center());point(a.center(),c.center());point(a.u(),c.u());}
        }
    }
    @Test void sunAngularSizeIsPhysicalAndInvariantUnderRegionRotation(){
        var model=GroundSolar.defaults();var eye=model.at(0,100).center().scale(1.5);var sun=model.star(eye);double angle=Math.asin(sun.radius()/sun.position().sub(eye).length());
        for(var q:ROTATIONS){var delta=NativeSpaceView.relative(new NativeSpace.Frame(q,NativeSpace.ORIGIN,0),eye,sun.position());assertEquals(angle,Math.asin(sun.radius()/delta.length()),1e-12);}
    }
    @Test void nearestIncludesSinglePlanetAndFormerHomeAndUsesEyeNotReference(){
        var single=GroundSolar.defaults();assertEquals(1,single.count());assertEquals(List.of(0),NativeSpaceView.nearest(single,new Point(10000,0,0),0,8));
        var model=NativeSpaceTest.tilted();for(double tick:new double[]{0,1234,12000})for(int id=0;id<2;id++)assertEquals(id,NativeSpaceView.nearest(model,model.at(id,tick).center().add(new Point(2,3,4)),tick,1).getFirst());
    }
    @Test void nearestEightIsSortedBoundedAndDeterministic(){
        var model=NativePlanetSystem.model(new int[]{2,2,2,2,2,2});var eye=model.at(9,500).center();var near=NativeSpaceView.nearest(model,eye,500,8);assertEquals(8,near.size());assertEquals(9,near.getFirst());
        double previous=-1;for(int id:near){double distance=model.at(id,500).center().sub(eye).length();assertTrue(distance>=previous);previous=distance;}
        assertEquals(near,NativeSpaceView.nearest(model,eye,500,8));assertEquals(List.of(),NativeSpaceView.nearest(model,eye,500,0));
        var pair=NativeSpaceTest.tilted();assertEquals(List.of(0,1),NativeSpaceView.nearest(pair,pair.center(),12,8));
    }
    @Test void physicalShadowTestsAllNearbyPlanetsNotOnlyTheReference(){
        var model=NativePlanetSystem.model();for(double tick:new double[]{0,1234,8000})for(int id=0;id<2;id++){
            var center=model.at(id,tick).center();var radial=PlanetSpace.unit(center.sub(model.sun().position()));var night=center.add(radial.scale(3000));var day=center.sub(radial.scale(3000));
            assertEquals(0,NativeSpaceView.sunlight(model,night,tick),1e-9);assertEquals(1,NativeSpaceView.sunlight(model,day,tick),1e-9);
        }
    }
    @Test void farBoundIncludesWholeBodiesWithoutCompressingTheirDistance(){
        assertEquals(1_000_000,NativeSpaceView.FAR);assertTrue(NativeSpaceView.visible(100000,4000));assertFalse(NativeSpaceView.visible(NativeSpaceView.FAR-100,100));assertFalse(NativeSpaceView.visible(Double.NaN,1));assertFalse(NativeSpaceView.visible(1,Double.POSITIVE_INFINITY));assertFalse(NativeSpaceView.visible(-1,1));
    }
    @Test void passiveViewWireCarriesMaskLayoutAndIndependentSession(){
        for(long epoch:new long[]{0,1,-41,Long.MAX_VALUE})for(boolean shared:new boolean[]{false,true}){var packet=new NativeSpacePackets.View(epoch,new GroundSpace.Config(7,1234),shared);var b=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);try{NativeSpacePackets.View.CODEC.encode(b,packet);assertEquals(17,b.readableBytes());assertEquals(packet,NativeSpacePackets.View.CODEC.decode(b));assertEquals(0,b.readableBytes());assertEquals("starfold:native_space_v1_view",packet.type().id().toString());}finally{b.release();}}
        assertThrows(IllegalArgumentException.class,()->new NativeSpacePackets.View(1,null,true));
    }
    @Test void existingRemoteProtocolAcceptsPassiveEpochAndReplacesOldSurfaceIdentity(){
        var view=new NativePlanetRemoteView();var changed=new long[]{1};var profile=new NativePlanetPackets.Profile(-41,123,2);var first=new NativePlanetRemotePackets.Summary(0,profile,0,changed,new long[0]);assertTrue(view.summary(first));assertEquals(-41,view.session());assertEquals(0,view.snapshot().planet());
        assertTrue(view.summary(new NativePlanetRemotePackets.Summary(0,new NativePlanetPackets.Profile(-42,123,2),0,changed,new long[0])));assertEquals(-42,view.session());assertEquals(0,view.size());
    }
    @Test void regionServerRoutesBeforeChartLookupAndNeverGeneratesForRemoteView()throws Exception{
        before(calls("NativePlanetRemoteServer","tick"),"in","planet");var region=calls("NativePlanetRemoteServer","region");for(String name:List.of("view","frame","universe","nearest"))assertTrue(region.contains(name),name);assertFalse(region.contains("planet"));assertFalse(region.contains("session"));
        var remote=source("NativePlanetRemoteServer");for(String forbidden:List.of("getChunk(","getChunkNow(","getChunkFuture","addRegionTicket"))assertFalse(remote.contains(forbidden),forbidden);
        assertTrue(source("NativeGroundViewServer").contains("if(NativeSpaceServer.in(p)){remove(p);NativePlanetRemoteServer.tick(p,clock);continue;}"));
        before(calls("NativeSpaceServer","sync"),"region","regionSky");assertTrue(source("NativeSpaceServer").contains("player.level().getGameTime()%20==0"));assertTrue(source("NativeGroundServer").contains("regionSky(ServerPlayer player){whole(player);send(player,NativeGroundClock.get(player.serverLevel()));}"));
    }
    @Test void clientRejectsWrongLevelEpochAndOldFlightPacketsWithoutOwningMotion()throws Exception{
        var client=source("NativeSpaceClient");for(String text:List.of("viewLevel!=mc.level","if(packet.session()!=0)retainedSession=packet.session()","viewLevel==Minecraft.getInstance().level","LoggingOut","if(frames.current()!=null||view!=null)NativePlanetRemoteRenderer.clear()"))assertTrue(client.contains(text),text);
        var accepted=source("NativePlanetRemoteRenderer");assertTrue(accepted.contains("if(NativeSpaceClient.present())return session!=0&&session==NativeSpaceClient.session()"));assertTrue(accepted.contains("accepts(p.surface().session(),p.planet())"));assertTrue(accepted.contains("accepts(p.profile().session(),p.planet())"));
        for(String forbidden:List.of("NativeGroundClient.ready","setPos(","teleport","NativeGroundFlightClient.time"))assertFalse(client.contains(forbidden),forbidden);
        assertTrue(calls("NativeSpaceClient","time").contains("skyTime"));assertFalse(calls("NativeGroundClient","skyTime").contains("controlled"));
    }
    @Test void frameReanchoringDoesNotRestartMeshEpochAndChartOnlyNoticesDoNotClearMeshes()throws Exception{
        var server=source("NativeSpaceServer");var sync=server.substring(server.indexOf("public static void sync("),server.indexOf("private static void send("));assertFalse(sync.contains("views.remove"));assertTrue(sync.contains("view(player)"));
        assertTrue(server.contains("boolean fresh=v==null||v.level()!=player.serverLevel()||!v.packet().mask().equals(mask)"));
        assertTrue(source("NativeSpaceClient").contains("if(viewLevel!=null&&viewLevel!=mc.level){NativePlanetRemoteRenderer.clear();retainedSession=0;}"));
        assertTrue(source("NativePlanetRemoteRenderer").contains("session==NativeSpaceClient.retainedSession()"));
    }
    @Test void regionDrawUsesVanillaCameraMatrixAndNoChartFacade()throws Exception{
        for(String name:List.of("NativePlanetRemoteRenderer","NativeGroundSunRenderer")){before(calls(name,"draw"),"active",name.equals("NativePlanetRemoteRenderer")?"ready":"solar");var c=calls(name,"drawRegion");assertTrue(c.contains("positionMatrix"));assertTrue(c.contains("universeEye"));assertTrue(c.contains("lens"));for(String forbidden:List.of("home","orbit","face","eye","lensUniforms","selectionReady","controlled"))assertFalse(c.contains(forbidden),name+" "+forbidden);}
        var client=source("NativeGroundClient");assertTrue(client.contains("frame=false;partial=delta;if(NativeSpaceClient.present())return;if(!ready()"));assertTrue(client.contains("boolean projected=frame&&ready();ShaderUniforms.set(shader,\"CPNativeActive\",projected?1:0);if(!projected)return"));
        var celestials=source("NativeGroundCelestials");assertTrue(celestials.contains("Stage.AFTER_ENTITIES"));assertTrue(celestials.contains("(!NativeSpaceClient.active()&&!NativeGroundClient.ready())"));assertTrue(celestials.contains("NativePlanetRemoteRenderer.beginFrame()"));
    }
    @Test void starsBlackFogFarPlaneAndLightAreRegionSpecific()throws Exception{
        var stars=source("NativeGroundStars");for(String text:List.of("float fade=region?1f:","context.positionMatrix()","NativeSpaceClient.frame().rotation()","if(region)GL11.glDepthRange(1,1)","if(region)GL11.glDepthRange(range[0],range[1])"))assertTrue(stars.contains(text),text);
        before(calls("mixin/NativeGroundFogMixin","starfold$nativeSky"),"present","ready");assertTrue(source("mixin/NativeGroundFogMixin").contains("fogRed=fogGreen=fogBlue=0"));assertTrue(source("NativeGroundClient").contains("return NativeSpaceClient.present();"));
        before(calls("mixin/NativeGroundLightMixin","starfold$nativeSun"),"active","ready");assertTrue(source("mixin/NativeGroundLightMixin").contains("NativeSpaceClient.sunlight()"));assertTrue(source("mixin/NativeGroundEntityRenderMixin").contains("LightTexture.block(ci.getReturnValue()),15"));
        assertTrue(source("mixin/NativeGroundFlightRenderMixin").contains("NativeSpaceView.FAR"));assertTrue(source("DevProbe").contains("\" | sees \"+NativePlanetRemoteRenderer.regionDrawn()+\" planets\""));
    }
    private static void pinned(String name,String start,String end,String hash)throws Exception{var s=source(name);var text=s.substring(s.indexOf(start),s.indexOf(end));assertEquals(hash,HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))),name);}
    @Test void preexistingChartAndOwnFlightPlacementAndTimeRemainByteForByte()throws Exception{
        // Source spans from fca115a7, before adding passive dispatch. The old computations remain verbatim.
        pinned("NativePlanetRemoteRenderer","    private static void drawBody","    private static boolean accepts","0f807841a9bf5310183d07f51cab4fffdddc1fb25649f4b24321ae9174b363dc");
        pinned("NativeGroundSunRenderer","        if(!NativeGroundClient.solar()","    /** Physical spheres","77a03853cb7dca21142eef80043e3118bc0554e0fce81b6e011b5362275642c6");
        pinned("NativeGroundClient","    public static double time()","    public static int face()","42c69d8dee37cff56b5d44aa8b41ad4f97b4912ec464db3f147ab301ba989056");
    }
    @Test void chartAndOwnFlightFullOrbitOutputsKeepTheirExistingGeometry(){
        for(var face:Face.values())for(double height:new double[]{0,160,1088,6000}){var eye=new Point(1024,height,1024);var pose=new GroundSpace.Pose(face,eye);var old=new GroundOrbit(pose,eye,128);var nativeOrbit=new NativeOrbit(NativePlanetShape.STANDARD,pose,eye,128);
            for(var target:Face.values())for(var point:new Point[]{new Point(0,0,0),new Point(1024,120,1024),new Point(2048,-24,2048)})point(old.point(target,point),nativeOrbit.point(target,point));
            point(eye,nativeOrbit.point(face,eye));
        }
    }
}
