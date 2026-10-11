package dev.starfold;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.*;
import org.joml.Vector3d;

/** A saved deck place waits for its hull, not for the ground far beneath it. */
public final class NativeSableBoarding {
    static final String KEY="starfold_aboard";
    static final int LIMIT=200;
    private static final net.minecraft.resources.ResourceLocation WEIGHTLESS=net.minecraft.resources.ResourceLocation.parse("starfold:boarding_weightless");
    private static final java.util.Map<ServerPlayer,Integer> boarding=new java.util.WeakHashMap<>();
    public static void initialize(){
        LocalNet.playS2C(NativeSableBoardingPacket.TYPE,NativeSableBoardingPacket.CODEC);
        DevBoardingProbe.initialize();
        var bus=net.neoforged.neoforge.common.NeoForge.EVENT_BUS;
        bus.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event)->login((ServerPlayer)event.getEntity()));
        bus.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event)->{var player=(ServerPlayer)event.getEntity();save(player);boarding.remove(player);weight(player,false);});
        bus.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerChangedDimensionEvent event)->release((ServerPlayer)event.getEntity(),"dimension"));
        bus.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event)->release((ServerPlayer)event.getEntity(),"respawn"));
        bus.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event)->boarding.clear());
        bus.addListener((net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event)->{if(event.getEntity() instanceof ServerPlayer player)tick(player);});
    }
    /** Called before vanilla serializes ForgeData, including autosaves, not after the file was written. */
    public static void save(ServerPlayer player){
        if(boarding.containsKey(player))return;
        var data=player.getPersistentData();var body=NativeGroundServer.is(player.level())?NativeSableFrames.aboard(player):null;
        if(body==null||body.isRemoved()){data.remove(KEY);return;}
        var local=body.logicalPose().transformPositionInverse(new Vector3d(player.getX(),player.getY(),player.getZ()));
        var tag=new CompoundTag();tag.putUUID("body",body.getUniqueId());tag.putDouble("x",local.x);tag.putDouble("y",local.y);tag.putDouble("z",local.z);data.put(KEY,tag);
        probe(player,"save",body,local,0);
    }
    static boolean valid(CompoundTag tag){return tag.hasUUID("body")&&tag.contains("x",6)&&tag.contains("y",6)&&tag.contains("z",6)&&Double.isFinite(tag.getDouble("x"))&&Double.isFinite(tag.getDouble("y"))&&Double.isFinite(tag.getDouble("z"));}
    private static void login(ServerPlayer player){
        if(!player.getPersistentData().contains(KEY))return;
        if(!NativeGroundServer.is(player.level())||!valid(player.getPersistentData().getCompound(KEY))||player.isSpectator()||player.getAbilities().flying){release(player,"ineligible");return;}
        boarding.put(player,0);weight(player,true);player.fallDistance=0;player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(player));probe(player,"login",null,null,0);
    }
    private static void weight(ServerPlayer player,boolean free){
        weight(player.getAttribute(Attributes.GRAVITY),free);
    }
    static void weight(AttributeInstance gravity,boolean free){
        if(gravity==null)return;
        if(free){if(!gravity.hasModifier(WEIGHTLESS))gravity.addTransientModifier(new AttributeModifier(WEIGHTLESS,-1,AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));}
        else gravity.removeModifier(WEIGHTLESS);
    }
    private static void tick(ServerPlayer player){
        var age=boarding.get(player);if(age==null)return;age++;boarding.put(player,age);
        if(!NativeGroundServer.is(player.level())||!player.isAlive()||player.isSpectator()||player.getAbilities().flying||age>=LIMIT){release(player,"expired-or-ineligible");return;}
        var tag=player.getPersistentData().getCompound(KEY);if(!valid(tag)){release(player,"invalid");return;}
        weight(player,true);player.fallDistance=0;
        var container=SubLevelContainer.getContainer(player.serverLevel());var found=container==null?null:container.getSubLevel(tag.getUUID("body"));
        // A null lookup can be an unloaded saved hull. Give normal chunk loading the bounded grace too.
        if(!(found instanceof ServerSubLevel body)){player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);return;}
        if(body.isRemoved()){release(player,"removed");return;}
        var local=new Vector3d(tag.getDouble("x"),tag.getDouble("y"),tag.getDouble("z"));
        boolean restored=NativeSableFrames.tracked(player,body);
        var at=body.logicalPose().transformPosition(new Vector3d(local));
        // Teleports alone race Sable's interpolated deck on a descending hull. Restore the body pose and
        // saved feet atomically with the existing carry fence; an absent client plot waits for the next tick.
        player.setPos(at.x,at.y,at.z);player.connection.teleport(at.x,at.y,at.z,player.getYRot(),player.getXRot());player.connection.resetPosition();player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        var frame=NativeFrames.get(player);var origin=container.getOrigin();var plot=body.getPlot().plotPos;
        var carry=new NativeSableCarry(net.minecraft.world.level.ChunkPos.asLong(plot.x-origin.x,plot.z-origin.y),container.trackingSystem().getInterpolationTick(),new dev.ryanhcode.sable.companion.math.Pose3d(NativeSableFrames.pose(player,frame,body.logicalPose())),new dev.ryanhcode.sable.companion.math.Pose3d(NativeSableFrames.pose(player,frame,body.lastPose())),frame.toClient(new BentStripGeometry.Point(at.x,at.y,at.z)),frame.yawToClient(player.getYRot()),frame,NativeSpaceServer.packet(player));
        NativePacketMap.raw(player,new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(new NativeSableBoardingPacket(carry)));
        probe(player,restored?"tracked":"hold",body,local,age);if(restored)release(player,"tracked");
    }
    private static void release(ServerPlayer player,String reason){
        if(!boarding.containsKey(player)&&!player.getPersistentData().contains(KEY))return;
        probe(player,"release-"+reason,null,null,boarding.getOrDefault(player,0));
        boarding.remove(player);weight(player,false);player.getPersistentData().remove(KEY);player.fallDistance=0;
    }
    private static void probe(ServerPlayer player,String phase,ServerSubLevel body,Vector3d local,int age){if(Boolean.getBoolean("starfold.rejoinProbe"))org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE BOARD {} tick {} age {} body {} local {} pos {} gravity {}",phase,player.level().getGameTime(),age,body==null?"none":body.getUniqueId(),local==null?"none":java.util.Arrays.toString(new double[]{local.x,local.y,local.z}),player.position(),player.getAttributeValue(Attributes.GRAVITY));}
    private NativeSableBoarding(){}
}
