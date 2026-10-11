package dev.starfold;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector3d;

/** Server-owned, deliberately unsaved helm sessions. A missing input lease always means no command. */
public final class NativeHelmServer {
    private static final NativeHelmCommand.Pilots<ServerSubLevel,ServerPlayer> pilots=new NativeHelmCommand.Pilots<>();
    private static final java.util.Map<ServerSubLevel,Session> sessions=new java.util.HashMap<>();
    private static final class Session {
        final ServerPlayer player;final ServerSubLevel body;final BlockPos pos;final Direction facing;final NativeThruster.Helm controller;final NativeHelmExit exit=new NativeHelmExit();final NativeLanding.Search landingSearch=new NativeLanding.Search();int bits;boolean liftWarned;long tick=Long.MIN_VALUE;
        Session(ServerPlayer player,ServerSubLevel body,BlockPos pos,Direction facing){this.player=player;this.body=body;this.pos=pos.immutable();this.facing=facing;this.controller=(NativeThruster.Helm)player.level().getBlockEntity(pos);}
    }
    public static void initialize(){
        LocalNet.playS2C(NativeHelmPackets.State.TYPE,NativeHelmPackets.State.CODEC);LocalNet.playS2C(NativeHelmPackets.Lift.TYPE,NativeHelmPackets.Lift.CODEC);LocalNet.playC2S(NativeHelmPackets.Input.TYPE,NativeHelmPackets.Input.CODEC);
        LocalNet.serverReceiver(NativeHelmPackets.Input.TYPE,(p,c)->input((ServerPlayer)c.player(),p));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.LevelTickEvent.Pre e)->{if(e.getLevel() instanceof ServerLevel level)tick(level);});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e)->release((ServerPlayer)e.getEntity()));
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e)->{sessions.clear();pilots.clear();});
    }
    static void use(ServerPlayer player,BlockPos pos){
        if(!(Sable.HELPER.getContaining(player.level(),pos) instanceof ServerSubLevel body)||body.isRemoved()||!player.isAlive()||player.isSpectator()||NativeSableFrames.aboard(player)!=body)return;
        var eye=player.getEyePosition();var localEye=body.logicalPose().transformPositionInverse(new Vector3d(eye.x,eye.y,eye.z));
        // Vanilla uses closest point on the block AABB, not centre distance plus an extra allowance.
        if(new net.minecraft.world.phys.AABB(pos).distanceToSqr(new net.minecraft.world.phys.Vec3(localEye.x,localEye.y,localEye.z))>=Math.pow(player.blockInteractionRange(),2))return;
        var current=sessions.get(body);if(current!=null&&!valid(current)){drop(current);current=null;}
        if(current!=null&&current.player==player&&current.pos.equals(pos)){drop(current);return;}
        if(!pilots.take(body,player)){player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.starfold.helm_busy"),true);return;}
        for(var old:java.util.List.copyOf(sessions.values()))if(old.player==player)drop(old);
        pilots.take(body,player);var state=player.level().getBlockState(pos);if(!(state.getBlock() instanceof NativeThruster.HelmBlock)){pilots.release(body);return;}
        var session=new Session(player,body,pos,state.getValue(BlockStateProperties.HORIZONTAL_FACING));sessions.put(body,session);send(session,true);var caps=NativeSpaceSpeedServer.caps(body);player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.starfold.space_caps",Math.round(caps.cruise()),Math.round(caps.boost())),true);
    }
    private static boolean valid(Session s){
        if(s.body.isRemoved()||s.body.getLevel()!=s.player.level()||!s.player.isAlive()||s.player.isSpectator()||s.player.hasDisconnected()||NativeSableFrames.aboard(s.player)!=s.body)return false;
        if(Sable.HELPER.getContaining(s.player.level(),s.pos)!=s.body||s.controller==null||s.controller.isRemoved()||s.player.level().getBlockEntity(s.pos)!=s.controller)return false;
        var state=s.player.level().getBlockState(s.pos);if(!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)||state.getValue(BlockStateProperties.HORIZONTAL_FACING)!=s.facing)return false;
        var at=s.body.logicalPose().transformPosition(new Vector3d(s.pos.getX()+.5,s.pos.getY()+.5,s.pos.getZ()+.5));return at.distanceSquared(s.player.getX(),s.player.getY(),s.player.getZ())<=36;
    }
    public static boolean poseLease(ServerPlayer player){for(var s:sessions.values())if(s.player==player&&NativeHelmCommand.fresh(s.tick,player.level().getGameTime())&&valid(s))return true;return false;}
    public static boolean active(ServerPlayer player){for(var s:sessions.values())if(s.player==player&&valid(s))return true;return false;}
    public static boolean pilot(ServerPlayer player,ServerSubLevel body){var s=sessions.get(body);return s!=null&&s.player==player&&valid(s);}
    static boolean hasPilot(ServerSubLevel body){var s=sessions.get(body);return s!=null&&valid(s);}
    static int bits(ServerSubLevel body){var s=sessions.get(body);return s!=null&&NativeHelmCommand.fresh(s.tick,s.player.level().getGameTime())&&valid(s)?s.bits:0;}
    static Direction facing(ServerSubLevel body){var s=sessions.get(body);return s==null?Direction.NORTH:s.facing;}
    static BentStripGeometry.Point forward(ServerSubLevel body){var d=facing(body);var v=body.logicalPose().orientation().transform(new Vector3d(d.getStepX(),0,d.getStepZ()));return new BentStripGeometry.Point(v.x,v.y,v.z);}
    static int power(ServerSubLevel body,BlockPos pos,net.minecraft.world.level.block.state.BlockState state){var nozzle=state.getValue(BlockStateProperties.FACING);if(NativeLiftServer.owns(body,nozzle))return NativeLiftServer.power(body,nozzle);var s=sessions.get(body);int bits=bits(body);var center=body.getMassTracker().getCenterOfMass();return NativeHelmCommand.effective(state.getValue(BlockStateProperties.POWER),s!=null&&center!=null&&NativeHelmCommand.fires(bits,s.facing,state.getValue(BlockStateProperties.FACING),new Vector3d(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5).sub(center),NativeSpaceServer.rollControl(s.player.level(),body)));}
    static boolean boosted(ServerSubLevel body){if((bits(body)&NativeHelmCommand.SPRINT)==0)return false;for(var actor:body.getPlot().getBlockEntityActors())if(actor instanceof NativeThruster.Booster booster&&!booster.isRemoved())return true;return false;}
    private static void input(ServerPlayer player,NativeHelmPackets.Input packet){for(var s:java.util.List.copyOf(sessions.values()))if(s.player==player&&s.body.getUniqueId().equals(packet.body())){if(!valid(s)){drop(s);return;}if(packet.leave()){boolean space=NativeGroundServer.is(player.level())&&NativeSpace.holds(player.getX(),player.getZ());if(s.exit.leave(player.gameMode.getGameModeForPlayer(),space,NativeLanding.landedWithStair(player.serverLevel(),s.body),player.level().getGameTime()))drop(s);else player.displayClientMessage(net.minecraft.network.chat.Component.translatable(space?"message.starfold.helm_exit_space":"message.starfold.helm_exit_stair"),true);return;}s.bits=packet.bits()&NativeHelmCommand.ALL;s.tick=player.level().getGameTime();return;}}
    private static void tick(ServerLevel level){for(var s:java.util.List.copyOf(sessions.values()))if(s.player.level()==level){if(!valid(s))drop(s);else {if(!s.liftWarned&&NativeLiftServer.insufficient(s.body)){s.liftWarned=true;s.player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.starfold.insufficient_lift"),true);}send(s,true);}}}
    private static void release(ServerPlayer player){for(var s:java.util.List.copyOf(sessions.values()))if(s.player==player)drop(s);}
    private static void drop(Session s){sessions.remove(s.body);pilots.release(s.body);s.bits=0;NativeSpaceServer.releaseHelm(s.player);send(s,false);}
    private static void send(Session s,boolean active){var center=s.body.getMassTracker().getCenterOfMass();if(center==null)center=new Vector3d();var landing=active?NativeLanding.site(s.player.serverLevel(),s.body):NativeLanding.Site.UNKNOWN;if(active)landing=s.landingSearch.update(s.player.serverLevel(),s.body,s.facing,landing);else s.landingSearch.clear();var caps=NativeSpaceSpeedServer.caps(s.body);for(var player:s.body.getLevel().players())if(player==s.player||s.body.getTrackingPlayers().contains(player.getUUID()))if(player.connection.hasChannel(NativeHelmPackets.State.TYPE))PacketDistributor.sendToPlayer(player,new NativeHelmPackets.State(s.body.getUniqueId(),s.pos,s.facing,active,active&&player==s.player,active?bits(s.body):0,active&&NativeSpaceServer.rollControl(player.level(),s.body),center.x(),center.y(),center.z(),landing,caps.cruise(),caps.boost(),NativeThruster.boosted(s.body,s.body.getLevel().getGameTime())));}
    private NativeHelmServer(){}
}
