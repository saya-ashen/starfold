package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

/** Passive region observer. No chart projection, camera ownership or flight prediction. */
public final class NativeSpaceClient {
    private static boolean arriving;
    private static NativeArrivalState.Phase phase=NativeArrivalState.Phase.CRUISE;private static float blend;private static int notice;
    static NativeArrivalState.Phase phase(){return active()?phase:NativeArrivalState.Phase.CRUISE;}
    static float blend(){return active()?blend:0;}
    static int notice(){return active()?notice:0;}
    private static ClientLevel level;private static final NativeSpaceView.Frames frames=new NativeSpaceView.Frames();private static long clientTick;private static ClientLevel viewLevel;private static NativeSpacePackets.View view;private static long retainedSession;
    static void install(NativeSpacePackets.Frame packet){var mc=Minecraft.getInstance();if(level!=mc.level)clear();if(!NativeGroundServer.is(mc.level))return;level=mc.level;arriving=packet.turning();phase=packet.phase();blend=packet.blend();notice=packet.notice();DevProbe.mouseFrame(packet);frames.install(packet,eye(),renderTick(),NativeGroundClient.ephemeris(),time());if(frames.current()!=null)NativeGroundFlightClient.region();}
    static void carried(NativeSpacePackets.Frame packet){frames.clear();install(packet);}
    private static Point eye(){var mc=Minecraft.getInstance();var p=mc.player==null?Vec3.ZERO:mc.player.getEyePosition(mc.getTimer().getGameTimeDeltaPartialTick(false));return new Point(p.x,p.y,p.z);}
    private static double renderTick(){return clientTick+Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);}
    /** Vanilla leaves ten to twenty minutes between tracks; the void gets its own track and short gaps. */
    private static final net.minecraft.sounds.Music MUSIC=new net.minecraft.sounds.Music(net.minecraft.core.Holder.direct(net.minecraft.sounds.SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation.parse("starfold:music.space"))),600,2400,true);
    public static void initialize(){
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.SelectMusicEvent event)->{if(present())event.setMusic(MUSIC);});
        NativeSableCarryClient.initialize();NativeWeatherClient.initialize();
        if(net.neoforged.fml.ModList.get().isLoaded("sable")){NativeSpaceDustClient.initialize();NativeThrusterSoundClient.initialize();}
        if(net.neoforged.fml.ModList.get().isLoaded("sable"))NativeHelmClient.initialize();NativeHullVehicles.initialize();
        LocalNet.clientReceiver(NativeSpacePackets.Frame.TYPE,(packet,c)->install(packet));
        LocalNet.clientReceiver(NativeSpacePackets.View.TYPE,(packet,c)->{var mc=Minecraft.getInstance();if(!NativeGroundServer.is(mc.level))return;if(viewLevel!=null&&viewLevel!=mc.level){NativePlanetRemoteRenderer.clear();retainedSession=0;}viewLevel=mc.level;view=packet;if(packet.session()!=0)retainedSession=packet.session();});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event)->clear());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Pre event)->{if(level!=Minecraft.getInstance().level)clear();clientTick++;});
    }
    private static void clear(){if(frames.current()!=null||view!=null)NativePlanetRemoteRenderer.clear();level=null;arriving=false;phase=NativeArrivalState.Phase.CRUISE;blend=0;notice=0;frames.clear();clientTick=0;viewLevel=null;view=null;retainedSession=0;}
    public static boolean arriving(){return active()&&arriving;}
    public static boolean present(){var mc=Minecraft.getInstance();return NativeGroundServer.is(mc.level)&&mc.player!=null&&NativeSpace.holds(mc.player.getX(),mc.player.getZ());}
    public static NativeSpace.Frame frame(){var mc=Minecraft.getInstance();if(level!=Minecraft.getInstance().level)clear();return NativeGroundServer.is(mc.level)&&mc.player!=null&&NativeSpace.holds(mc.player.getX(),mc.player.getZ())?frames.sample(eye(),renderTick(),NativeGroundClient.ephemeris(),time()):null;}
    public static boolean active(){var frame=frame();var model=NativeGroundClient.ephemeris();return frame!=null&&model!=null&&frame.planet()<model.count();}
    /** Actual Level camera point, not a chart eye or a flight proxy. */
    public static Point universeEye(Vec3 levelCameraPosition){if(!active())return null;var frame=frame();return frame.universe(new Point(levelCameraPosition.x,levelCameraPosition.y,levelCameraPosition.z),NativeGroundClient.ephemeris(),time());}
    /** Sky time never borrows stale flight interpolation or the last chart render partial. */
    public static double time(){return NativeGroundClient.skyTime(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));}
    static long retainedSession(){return viewLevel==Minecraft.getInstance().level?retainedSession:0;}
    public static long session(){return frame()!=null&&viewLevel==Minecraft.getInstance().level&&view!=null?view.session():0;}
    public static GroundSpace.Config mask(){return session()!=0?view.mask():GroundSpace.DEFAULT_MASK;}
    public static boolean shared(net.minecraft.world.level.Level value){return value==viewLevel&&session()!=0&&view.shared();}
    public static double sunlight(){if(!active())return 1;return NativeSpaceView.sunlight(NativeGroundClient.ephemeris(),universeEye(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()),time());}
    public static java.util.List<GroundEphemeris.Star> suns(Point eye){var all=new java.util.ArrayList<>(NativeGroundClient.ephemeris().everyStar());all.sort(java.util.Comparator.comparingDouble(s->s.position().sub(eye).length()));return all.subList(0,Math.min(NativeGroundClient.SUNS,all.size()));}
    public static void lens(net.minecraft.client.renderer.ShaderInstance shader){shader.getUniform("SpaceLens").set(0f,1f,1f);}
    private NativeSpaceClient(){}
}
