package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.*;
import java.util.function.Predicate;

/** Live flight owner above 160. Native Minecraft remains movement/inventory owner below it. */
public final class NativeGroundFlightServer {
    private static final Map<UUID,Session> sessions=new HashMap<>();
    private static Predicate<Entity> clientControl=e->false;
    private static final class Session {
        final long id;final NativeGroundFlightPackets.Queue queue=new NativeGroundFlightPackets.Queue();NativeGroundDynamics.State state;boolean controlled;long lastSync=-100;
        Session(NativeGroundDynamics.State state,boolean controlled){this.state=state;this.controlled=controlled;long id=UUID.randomUUID().getMostSignificantBits();this.id=id==0?1:id;}
    }
    private static java.util.function.Supplier<GroundSpace.Config> clientMask=()->GroundSpace.DEFAULT_MASK;
    public static void clientMask(java.util.function.Supplier<GroundSpace.Config> supplier){clientMask=supplier;}
    public static GroundSpace.Config mask(net.minecraft.world.level.Level level){return level.isClientSide?clientMask.get():NativeGroundClock.get((ServerLevel)level).mask;}
    /** Where a player is in the universe, or null without a session. */
    public static Point position(ServerPlayer player,NativeGroundClock clock){if(NativeSpaceServer.in(player))return null;var s=sessions.get(player.getUUID());return s==null||s.state.planet()>=clock.clock.model.count()?null:NativeGroundDynamics.position(clock.clock.model,s.state,s.state.tick());}
    /** Above this height over the nominal surface a vessel loads no chunks; the approach owners begin below it. */
    public static final double ALOFT=2048;
    public static boolean aloft(ServerPlayer player){if(NativeSpaceServer.in(player))return false;var s=sessions.get(player.getUUID());return s!=null&&s.controlled&&(s.state.space()||s.state.local().point().y()>ALOFT);}
    public static long session(ServerPlayer player){if(NativeSpaceServer.in(player))return 0;var s=sessions.get(player.getUUID());return s==null?0:s.id;}
    public static void clientControl(Predicate<Entity> predicate){clientControl=Objects.requireNonNull(predicate);}
    public static boolean controlled(Entity entity){if(!NativeGroundServer.is(entity.level()))return false;if(NativeSpaceServer.in(entity))return false;if(entity.level().isClientSide)return clientControl.test(entity);if(!(entity instanceof ServerPlayer p))return false;var s=sessions.get(p.getUUID());return s!=null&&s.controlled;}
    public static boolean compatible(ServerPlayer player){return (!NativePlanetSystem.terrain(player.level())||player.connection.hasChannel(NativePlanetPackets.Profile.TYPE))&&player.connection.hasChannel(NativeGroundFlightPackets.Setup.TYPE)&&player.connection.hasChannel(NativeGroundFlightPackets.State.TYPE);}
    public static void initialize(){
        LocalNet.playS2C(NativeGroundFlightPackets.Seam.TYPE,NativeGroundFlightPackets.Seam.CODEC);
        LocalNet.playS2C(NativeGroundFlightPackets.Frame.TYPE,NativeGroundFlightPackets.Frame.CODEC);
        LocalNet.playS2C(NativeGroundFlightPackets.Setup.TYPE,NativeGroundFlightPackets.Setup.CODEC);LocalNet.playS2C(NativeGroundFlightPackets.State.TYPE,NativeGroundFlightPackets.State.CODEC);
        LocalNet.playC2S(NativeGroundFlightPackets.Input.TYPE,NativeGroundFlightPackets.Input.CODEC);LocalNet.playC2S(NativeGroundFlightPackets.Sync.TYPE,NativeGroundFlightPackets.Sync.CODEC);
        LocalNet.serverReceiver(NativeGroundFlightPackets.Input.TYPE,(packet,c)->{var p=((net.minecraft.server.level.ServerPlayer)c.player());var s=sessions.get(p.getUUID());if(!NativeSpaceServer.in(p)&&s!=null&&s.controlled&&NativeGroundServer.is(p.level())&&packet.session()==s.id)s.queue.offer(packet,p.level().getGameTime());});
        LocalNet.serverReceiver(NativeGroundFlightPackets.Sync.TYPE,(packet,c)->{var p=((net.minecraft.server.level.ServerPlayer)c.player());var s=sessions.get(p.getUUID());if(!NativeSpaceServer.in(p)&&s!=null&&NativeGroundServer.is(p.level())&&packet.session()==s.id&&p.level().getGameTime()-s.lastSync>=20){s.lastSync=p.level().getGameTime();restart(p,NativeGroundClock.get(p.serverLevel()));}});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent cpEvent)->{var handler=((net.minecraft.server.level.ServerPlayer)cpEvent.getEntity()).connection;var server=handler.player.getServer();detach(handler.player);});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent cpEvent)->{var server=cpEvent.getServer();for(var p:server.getPlayerList().getPlayers())checkpoint(p);});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent cpEvent)->{var server=cpEvent.getServer();sessions.clear();});
    }
    /** A chart state only; region residents have no state to capture. */
    public static NativeGroundDynamics.State capture(ServerPlayer player,NativeGroundClock clock){
        if(NativeSpaceServer.in(player))return null;
        var v=player.getDeltaMovement();var pose=NativePlanetAccess.of(player.level()).body(new Point(player.getX(),player.getY(),player.getZ()));var q=NativeGroundCamera.capture(player.getYRot(),player.getXRot());
        var s=sessions.get(player.getUUID());var options=s==null?NativeGroundDynamics.OFF:s.state.guidance();
        return NativeGroundDynamics.local(clock.clock.tick(),NativePlanetSystem.planet(player),pose,new Point(v.x,v.y,v.z),q,player.getAbilities().flying,options);
    }
    /** Prepare before any teleport or return-book mutation; never reset a damaged/occupied entry. */
    public static NativeGroundDynamics.State entry(ServerPlayer player,ServerLevel level,NativeGroundClock clock){
        if(NativeSpaceServer.in(player))return null;
        var saved=NativeGroundNavigationSave.get(level).find(player.getUUID());if(saved==null)return null;
        // The body-sized occupancy check needs the real blocks: load the few owners under a saved ground pose
        // now, rather than reading an unloaded chunk as solid and refusing a valid restore.
        if(!saved.space()){var access=NativePlanetAccess.of(level);var target=NativePlanetSystem.multiple(level)?NativePlanetSystem.level(level,saved.planet()):level;var feet=access.feet(saved.planet(),saved.local());
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){var query=net.minecraft.core.BlockPos.containing(feet.x()+dx*16,feet.y(),feet.z()+dz*16);if(!access.inChart(query))continue;var owner=access.address(query).owner();target.getChunk(owner.getX()>>4,owner.getZ()>>4);}}
        return NativeGroundDynamics.resume(clock.clock.model,NativePlanetSystem.terrain(level,player,clock.mask),saved,clock.clock.tick());
    }
    public static void enter(ServerPlayer player,NativeGroundClock clock,NativeGroundDynamics.State saved){
        if(NativeSpaceServer.in(player)){region(player);return;}
        var state=saved==null?capture(player,clock):saved;boolean controlled=state.space()||state.local().point().y()>NativeGroundDynamics.HANDOFF&&state.flying();
        var session=new Session(state,controlled);sessions.put(player.getUUID(),session);if(saved!=null)apply(player,clock,session,true);checkpoint(player);setup(player,clock,session);
    }
    /** Vanilla's saved position wins unless navigation owned the saved movement. Explicit entry is unchanged. */
    private static void login(ServerPlayer player,NativeGroundClock clock){
        var saved=NativeGroundNavigationSave.get(player.serverLevel()).find(player.getUUID());
        if(restores(saved))enter(player,clock,entry(player,player.serverLevel(),clock));else site(player);
    }
    static boolean restores(NativeGroundDynamics.State saved){return saved!=null&&(saved.space()||saved.local().point().y()>NativeGroundDynamics.HANDOFF&&saved.flying());}
    private static void setup(ServerPlayer player,NativeGroundClock clock,Session s){NativeFrames.announce(player);net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new NativeGroundFlightPackets.Setup(s.id,clock.clock.model,clock.mask,s.state,s.controlled,clock.air,NativePlanetSystem.shared(player.level())));NativeWaypointServer.send(player);}
    public static void restart(ServerPlayer player,NativeGroundClock clock){if(NativeSpaceServer.in(player)){region(player);return;}var old=sessions.get(player.getUUID());if(old==null)return;var next=new Session(old.state,old.controlled);next.lastSync=old.lastSync;sessions.put(player.getUUID(),next);setup(player,clock,next);}
    public static boolean underWay(){for(var s:sessions.values())if(s.controlled)return true;return false;}
    public static void tick(ServerLevel level,NativeGroundClock clock){
        for(var player:List.copyOf(level.players()))try{
            if(NativeSpaceServer.in(player)){region(player);continue;}
            if(player.isSpectator()||!compatible(player)){NativeGroundServer.leave(player);continue;}
            // The dead stay where they fell until they are reborn; they have no journey to go on with.
            if(!player.isAlive())continue;
            boolean permit=NativeFlightPermit.sync(player);
            var s=sessions.get(player.getUUID());if(s==null){login(player,clock);continue;}
            if(NativePlanetSystem.multiple(level)&&s.state.tick()==clock.clock.tick())continue;
            if(!s.controlled){
                NativeGroundSeams.moved(player);s.state=capture(player,clock);
                // Only someone flying is taken over as a vessel: on foot, a mountain or a tower above the handoff is still ground.
                if(s.state.local().point().y()>NativeGroundDynamics.HANDOFF&&s.state.flying()){var next=new Session(s.state,true);sessions.put(player.getUUID(),next);s=next;player.getAbilities().flying=true;player.onUpdateAbilities();setup(player,clock,s);}
            }else{
                if(s.state.tick()+1!=clock.clock.tick())throw new IllegalStateException("Native flight epoch mismatch");
                var input=s.queue.poll();var aim=NativeGroundDynamics.orientation(clock.clock.model,s.state,s.state.tick());var motion=input==null?PlanetMotion.Input.idle(s.queue.ack(),aim):input.motion();
                // Without the permit a vessel has no thrust: it hangs where it is, or coasts in space, until the permit is back.
                if(!permit){motion=PlanetMotion.Input.idle(motion.sequence(),motion.orientation());input=null;}
                int priorPlanet=s.state.planet();
                s.state=NativeGroundDynamics.step(clock.clock.model,NativePlanetSystem.terrain(level,player,clock.mask),s.state,motion,input!=null&&input.toggle());
                boolean landing=!s.state.space()&&s.state.local().point().y()<=NativeGroundDynamics.HANDOFF;
                if(landing)s.state=NativeGroundDynamics.local(s.state.tick(),s.state.planet(),s.state.local(),NativeGroundDynamics.chartVelocity(s.state),GroundView.upright(NativeGroundDynamics.chartAim(s.state)),s.state.flying()&&permit,s.state.guidance());
                apply(player,clock,s,landing);if(landing||player.serverLevel()!=level||s.state.planet()!=priorPlanet){var next=new Session(s.state,!landing);sessions.put(player.getUUID(),next);s=next;setup(player,clock,s);}
            }
            checkpoint(player);net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new NativeGroundFlightPackets.State(s.id,s.queue.ack(),s.state,s.controlled));
        }catch(IllegalArgumentException|IllegalStateException error){player.sendSystemMessage(Component.translatable("message.starfold.flight_stopped",NativeWaypointServer.failure(error)));NativeGroundServer.leave(player);}
    }
    private static NativeGroundTopology.Pose view(NativeGroundClock clock,NativeGroundDynamics.State state){return state.space()?NativeGroundTopology.locate(NativePlanetShape.of(state.planet()),clock.clock.model.at(state.planet(),state.tick()).local(state.position())):state.local();}
    private static void apply(ServerPlayer player,NativeGroundClock clock,Session s,boolean handoff){
        var access=NativePlanetAccess.of(player.level());var pose=view(clock,s.state);var feet=access.feet(s.state.planet(),pose);var old=access.face(player.getX());boolean planetChanged=NativePlanetSystem.planet(player)!=s.state.planet();var frame=NativeOrbit.frame(NativePlanetShape.of(s.state.planet()),pose);
        var aim=frame.rotation().conjugate().mul(clock.clock.model.at(s.state.planet(),s.state.tick()).rotation().conjugate()).mul(NativeGroundDynamics.orientation(clock.clock.model,s.state,s.state.tick())).normalize();
        float yaw=(float)NativeGroundCamera.yaw(aim,player.getYRot()),pitch=(float)NativeGroundCamera.pitch(aim);
        var destination=NativePlanetSystem.multiple(player.serverLevel())?NativePlanetSystem.level(player.serverLevel(),s.state.planet()):player.serverLevel();
        if(destination!=player.serverLevel()||planetChanged){NativeGroundViewServer.remove(player);}
        // Keep the observer's client coordinates continuous when this place is drawn in their chart.
        NativeFrames.relocate(player,feet);
        if(access==NativePlanetAccess.SHARED){
            // Same ServerLevel and ClientLevel: position synchronization, never respawn/dimension travel.
            if(old!=pose.face().ordinal()||planetChanged||handoff)player.connection.teleport(feet.x(),feet.y(),feet.z(),yaw,pitch);
            else{player.setPos(feet.x(),feet.y(),feet.z());player.setYRot(yaw);player.setXRot(pitch);}
        }
        else if(destination!=player.serverLevel()||old!=pose.face().ordinal()||handoff){player.teleportTo(destination,feet.x(),feet.y(),feet.z(),yaw,pitch);}
        else{player.setPos(feet.x(),feet.y(),feet.z());player.setYRot(yaw);player.setXRot(pitch);}
        player.fallDistance=0;player.getAbilities().flying=s.state.flying();
        if(handoff&&!s.state.space()){var velocity=NativeGroundDynamics.chartVelocity(s.state);player.setDeltaMovement(velocity.x(),velocity.y(),velocity.z());player.onUpdateAbilities();}
        else player.setDeltaMovement(Vec3.ZERO);
        player.serverLevel().getChunkSource().move(player);
    }
    public static void checkpoint(ServerPlayer player){if(NativeSpaceServer.in(player)){region(player);return;}var s=sessions.get(player.getUUID());if(s!=null&&NativeGroundServer.is(player.level())){if(!s.controlled)s.state=capture(player,NativeGroundClock.get(player.serverLevel()));NativeGroundNavigationSave.get(player.serverLevel()).put(player.getUUID(),s.state);}}
    public static void detach(ServerPlayer player){
        checkpoint(player);sessions.remove(player.getUUID());NativeGroundViewServer.remove(player);
        // Vanilla writes the player's own data on disconnect. Write the navigation record with it, so an unclean stop
        // can never pair a fresh player file with a flight record from the last autosave.
        if(NativeGroundServer.is(player.level())){NativePlanetSystem.authority(player.serverLevel()).getDataStorage().save();if(NativeSpaceServer.in(player)&&NativePlanetSystem.authority(player.serverLevel())!=player.serverLevel())player.serverLevel().getDataStorage().save();}
    }
    /** A death ends the journey it happened on: the session and its saved place are given up. */
    public static void forget(ServerPlayer player){sessions.remove(player.getUUID());NativeGroundViewServer.remove(player);if(NativeGroundServer.is(player.level()))NativeGroundNavigationSave.get(player.serverLevel()).remove(player.getUUID());}
    /** Give up stale navigation as well as live prediction; vanilla saves the region position. */
    public static void region(ServerPlayer player){var old=sessions.remove(player.getUUID());NativeGroundViewServer.remove(player);NativeGroundNavigationSave.get(player.serverLevel()).remove(player.getUUID());if(old!=null)NativeFlightPermit.revoke(player);}
    public static void site(ServerPlayer player){long start=NativeArrivalProbe.start();try{if(NativeSpaceServer.in(player)){region(player);return;}var clock=NativeGroundClock.get(player.serverLevel());var next=new Session(capture(player,clock),false);sessions.put(player.getUUID(),next);checkpoint(player);setup(player,clock,next);}finally{NativeArrivalProbe.end("site",start);}}
    public static int orbit(ServerPlayer player){if(NativeSpaceServer.in(player))return NativeGroundServer.regionMessage(player);var s=sessions.get(player.getUUID());if(s==null)return 0;var clock=NativeGroundClock.get(player.serverLevel());var at=view(clock,s.state);var pose=new NativeGroundTopology.Pose(at.face(),new Point(at.point().x(),4096,at.point().z()));if(!new NativeGroundTerrain(player.serverLevel(),player,clock.mask).clear(pose))return 0;var aim=s.state.space()?new org.joml.Quaterniond():NativeGroundDynamics.chartAim(s.state);var state=NativeGroundDynamics.local(clock.clock.tick(),NativePlanetSystem.planet(player),pose,GroundEphemeris.ZERO,aim,true,s.state.guidance());var next=new Session(state,true);sessions.put(player.getUUID(),next);apply(player,clock,next,true);checkpoint(player);setup(player,clock,next);return 1;}
    public static int guidance(ServerPlayer player,int field,boolean on){if(NativeSpaceServer.in(player))return NativeGroundServer.regionMessage(player);var s=sessions.get(player.getUUID());if(s==null)return 0;var g=s.state.guidance();var options=new GroundGuidance.Options(g.target(),field==0?on:g.relativeBrake(),field==1?on:g.landing());s.state=NativeGroundDynamics.guidance(s.state,options);checkpoint(player);restart(player,NativeGroundClock.get(player.serverLevel()));player.sendSystemMessage(Component.translatable("message.starfold.guidance",NativePlanetSystem.name(options.target()),Component.translatable(options.relativeBrake()?"options.on":"options.off"),Component.translatable(options.landing()?"options.on":"options.off")));return 1;}
    public static int target(ServerPlayer player,int target){if(NativeSpaceServer.in(player))return NativeGroundServer.regionMessage(player);var s=sessions.get(player.getUUID());if(s==null)return 0;var clock=NativeGroundClock.get(player.serverLevel());if(target<0||target>=clock.clock.model.count()){player.sendSystemMessage(Component.translatable("message.starfold.no_planet"));return 0;}try{NativeWaypointSave.get(player.serverLevel()).clear(player.getUUID());}catch(IllegalArgumentException|IllegalStateException error){player.sendSystemMessage(Component.translatable("message.starfold.mark.read_failed",NativeWaypointServer.failure(error)));return 0;}var g=s.state.guidance();s.state=NativeGroundDynamics.guidance(s.state,new GroundGuidance.Options(target,g.relativeBrake(),g.landing()));checkpoint(player);restart(player,clock);player.sendSystemMessage(Component.translatable("message.starfold.target",NativePlanetSystem.name(target)));return 1;}
    private NativeGroundFlightServer(){}
}
