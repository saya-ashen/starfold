package dev.starfold;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import java.util.List;

/** Real-chunk backend: upright chart queries and high flight, never a block-material ledger. */
public final class NativeGroundServer {
    public static final ResourceKey<Level> DIMENSION=ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse("starfold:native_ground"));
    public static final ResourceKey<Level> PLANET=ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse("starfold:native_planet"));
    public static final ResourceKey<Level> ROCK=ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse("starfold:native_rock"));
    /** Storage-chart aliasing applies to the server world only: a client world is already laid out in the
     * observer's own continuous coordinates and is read exactly where it is. */
    public static boolean canonical(Level level){return is(level)&&!level.isClientSide;}
    public static boolean is(Level level){return level!=null&&(level.dimension().equals(DIMENSION)||level.dimension().equals(PLANET)||level.dimension().equals(ROCK));}
    public static void afterDeath(ServerPlayer player){arrived.remove(player.getUUID());if(is(player.level()))NativeGroundFlightServer.forget(player);}
    private static void canSleep(net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent event){
        var player=event.getEntity();var pos=event.getPos();if(!NativeGroundDaylight.kept(player.level(),pos))return;
        var problem=event.getVanillaProblem();var time=net.minecraft.world.entity.player.Player.BedSleepingProblem.NOT_POSSIBLE_NOW;var unsafe=net.minecraft.world.entity.player.Player.BedSleepingProblem.NOT_SAFE;
        if(NativeGroundDaylight.day(player.serverLevel(),pos)){if(problem==null||problem==time||problem==unsafe)event.setProblem(time);}
        else if(problem==time){
            net.minecraft.world.entity.player.Player.BedSleepingProblem result=null;
            if(!player.isCreative()){
                double d0=8.0;double d1=5.0;
                var vec3=net.minecraft.world.phys.Vec3.atBottomCenterOf(pos);
                List<net.minecraft.world.entity.monster.Monster> list=player.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,new net.minecraft.world.phys.AABB(vec3.x()-8.0,vec3.y()-5.0,vec3.z()-8.0,vec3.x()+8.0,vec3.y()+5.0,vec3.z()+8.0),monster->monster.isPreventingPlayerRest(player));
                if(!list.isEmpty())result=unsafe;
            }
            event.setProblem(result);
        }
    }
    public static void initialize(net.neoforged.bus.api.IEventBus modBus){
        if(net.neoforged.fml.ModList.get().isLoaded("create")&&net.neoforged.fml.ModList.get().isLoaded("sable"))NativeDrillClearance.initialize();if(net.neoforged.fml.ModList.get().isLoaded("sable"))NativeHullVehicles.initializeServer();NativeSableFrames.initialize();NativeSpaceServer.initialize();NativeSableBoarding.initialize();NativeThruster.initialize(modBus);NativeAirstair.initialize(modBus);NativeAircraftDock.initialize(modBus);NativeSpaceDust.initialize(modBus);
        NativePlanetChunkGenerator.initialize(modBus);NativeAnomaly.initialize(modBus);NativeCore.initialize(modBus);NativeFlightPermit.initialize(modBus);
        LocalNet.playS2C(NativeGroundPackets.Sky.TYPE,NativeGroundPackets.Sky.CODEC);
        LocalNet.playS2C(NativeGroundPackets.Shapes.TYPE,NativeGroundPackets.Shapes.CODEC);
        // A system's planets take their saved sizes before any of its chunks is addressed.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.LevelEvent.Load cpEvent)->{if(!(cpEvent.getLevel() instanceof net.minecraft.server.level.ServerLevel level))return;var server=level.getServer();if(level.getChunkSource().getGenerator() instanceof NativeSystemChunkGenerator system){system.materialize(level.getSeed());NativeUniverse.bind(level,system);system(system);}});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent cpEvent)->{var server=cpEvent.getServer();NativePlanetShape.reset();});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent cpEvent)->{var handler=((net.minecraft.server.level.ServerPlayer)cpEvent.getEntity()).connection;var server=handler.player.getServer();shapes(handler.player);});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post cpEvent)->{var server=cpEvent.getServer();if(server.getTickCount()%20==0)arrive(server);});
        // Completed player deaths are handled at ServerPlayer.die TAIL by NativeGroundDeathMixin.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent cpEvent)->{var handler=((net.minecraft.server.level.ServerPlayer)cpEvent.getEntity()).connection;var server=handler.player.getServer();arrived.remove(handler.player.getUUID());skies.remove(handler.player.getUUID());wholes.remove(handler.player.getUUID());arriving.remove(handler.player.getUUID());});
        NativeGroundFlightServer.initialize();NativeWaypointServer.initialize();NativeShipGuide.initialize();NativeStarterShip.initialize();
        NativeGroundViewServer.initialize();NativePlanetRemoteServer.initialize();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.LevelTickEvent.Pre cpEvent)->{if(!(cpEvent.getLevel() instanceof net.minecraft.server.level.ServerLevel level))return;if(is(level))NativeGroundTickets.update(level);});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppingEvent cpEvent)->{var server=cpEvent.getServer();arrived.clear();skies.clear();wholes.clear();arriving.clear();NativeGroundTickets.clear();NativeImageStream.clear();NativeAnomalyZone.clear();});
        // A bed on a planet is slept in while it is night where the bed stands, whatever the level's clock says.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(NativeGroundServer::canSleep);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent event)->{var entity=event.getEntity();if(!(entity instanceof net.minecraft.world.entity.player.Player)||event.getProblem()==net.minecraft.world.entity.player.Player.BedSleepingProblem.NOT_POSSIBLE_HERE)return;entity.getSleepingPos().ifPresent(pos->{if(NativeGroundDaylight.kept(entity.level(),pos))event.setContinueSleeping(!NativeGroundDaylight.day((ServerLevel)entity.level(),pos));});});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Pre cpEvent)->{var server=cpEvent.getServer();var a=server.getLevel(PLANET);try{if(a!=null&&NativePlanetSystem.multiple(a))NativeGroundClock.get(a).advance();}catch(IllegalArgumentException|IllegalStateException error){for(var p:server.getPlayerList().getPlayers())if(is(p.level()))p.sendSystemMessage(Component.translatable("message.starfold.clock_stopped",NativeWaypointServer.failure(error)));}});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.LevelTickEvent.Post cpEvent)->{if(!(cpEvent.getLevel() instanceof net.minecraft.server.level.ServerLevel level))return;if(!is(level))return;try{var saved=NativeGroundClock.get(level);if(!NativePlanetSystem.multiple(level))saved.advance();NativeGroundFlightServer.tick(level,saved);NativeImageStream.tick(level);NativeGroundViewServer.tick(level,saved);NativeAnomalyZone.tick(level);if(level.getGameTime()%20==0){for(var player:List.copyOf(level.players())){var at=NativeGroundFlightServer.position(player,saved);if(at!=null&&NativeUniverse.grow(level,at))saved=NativeGroundClock.get(level);}for(var player:level.players())send(player,saved);}}catch(IllegalArgumentException|IllegalStateException error){for(var player:level.players())player.sendSystemMessage(Component.translatable("message.starfold.clock_failed",NativeWaypointServer.failure(error)));}});
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent cpEvent)->{var dispatcher=cpEvent.getDispatcher();dispatcher.register(Commands.literal("starfold")
            // Going to the planets, steering and marking are everyone's; the rest is for setting a world up and looking into it.
            .then(Commands.literal("enter").requires(s->s.hasPermission(2)).executes(c->enter(c.getSource().getPlayerOrException())))
            .then(Commands.literal("terrain").executes(c->enter(c.getSource().getPlayerOrException(),PLANET)))
            .then(Commands.literal("leave").executes(c->leave(c.getSource().getPlayerOrException())))
            .then(NativeWaypointServer.commands())
            .then(Commands.literal("status").executes(c->status(c.getSource().getPlayerOrException())))
            .then(Commands.literal("universe").executes(c->universe(c.getSource().getPlayerOrException())))
            .then(Commands.literal("orbit").requires(s->s.hasPermission(2)).executes(c->NativeGroundFlightServer.orbit(c.getSource().getPlayerOrException())))
            .then(Commands.literal("nav").then(Commands.literal("target").then(Commands.literal("a").executes(c->NativeGroundFlightServer.target(c.getSource().getPlayerOrException(),0))).then(Commands.literal("b").executes(c->NativeGroundFlightServer.target(c.getSource().getPlayerOrException(),1))).then(Commands.argument("planet",IntegerArgumentType.integer(0,NativePlanetStorage.MAX_PLANETS-1)).executes(c->NativeGroundFlightServer.target(c.getSource().getPlayerOrException(),IntegerArgumentType.getInteger(c,"planet"))))).then(Commands.literal("brake").then(Commands.literal("on").executes(c->NativeGroundFlightServer.guidance(c.getSource().getPlayerOrException(),0,true))).then(Commands.literal("off").executes(c->NativeGroundFlightServer.guidance(c.getSource().getPlayerOrException(),0,false)))).then(Commands.literal("land").then(Commands.literal("on").executes(c->NativeGroundFlightServer.guidance(c.getSource().getPlayerOrException(),1,true))).then(Commands.literal("off").executes(c->NativeGroundFlightServer.guidance(c.getSource().getPlayerOrException(),1,false)))))
            .then(Commands.literal("face").requires(s->s.hasPermission(2)).then(Commands.argument("id",IntegerArgumentType.integer(0,5)).executes(c->face(c.getSource().getPlayerOrException(),IntegerArgumentType.getInteger(c,"id")))))
            .then(Commands.literal("spin").requires(s->s.hasPermission(2)).then(Commands.literal("on").executes(c->spin(c.getSource().getPlayerOrException(),true))).then(Commands.literal("off").executes(c->spin(c.getSource().getPlayerOrException(),false)))));});
    }
    /** Someone who chose to leave for the overworld stays there until they ask to go back. */
    public static final String STAY="starfold_stay";
    // By name, not by entity: someone reborn is a new entity that still equals the one that died.
    private static final java.util.Set<java.util.UUID> arrived=new java.util.HashSet<>();
    /** In a world with a system, whoever stands in the overworld is taken to the home planet: a new player, and
     *  whoever died with no bed to wake in. It is tried once for each time they appear there. */
    static void arrive(net.minecraft.server.MinecraftServer server){
        var planet=server.getLevel(PLANET);if(planet==null||!(planet.getChunkSource().getGenerator() instanceof NativeSystemChunkGenerator))return;
        for(var player:List.copyOf(server.overworld().players())){if(!player.isAlive()||player.isSpectator()||player.getTags().contains(STAY)||!arrived.add(player.getUUID()))continue;enter(player,PLANET);}
    }
    public static int enter(ServerPlayer player){return enter(player,DIMENSION);}
    private static int enter(ServerPlayer player,ResourceKey<Level> destination){
        player.removeTag(STAY);if(player.level().dimension().equals(destination))return status(player);
        if(player.level().dimension().location().getNamespace().equals("starfold")){player.sendSystemMessage(Component.translatable("message.starfold.leave_first"));return 0;}
        if(player.isSpectator()||(destination.equals(PLANET)&&!player.connection.hasChannel(NativePlanetPackets.Profile.TYPE))||(!player.connection.hasChannel(NativeGroundPackets.Sky.TYPE)||!NativeGroundFlightServer.compatible(player))){player.sendSystemMessage(Component.translatable("message.starfold.client"));return 0;}
        var level=player.getServer().getLevel(destination);if(level==null)return 0;
        if(!level.players().isEmpty()||NativePlanetSystem.multiple(level)&&!NativePlanetSystem.level(level,1).players().isEmpty()){player.sendSystemMessage(Component.translatable("message.starfold.one_player"));return 0;}
        NativeGroundClock clock;NativeGroundDynamics.State saved;dev.starfold.BentStripGeometry.Point spawn;
        try{clock=NativeGroundClock.get(level);saved=NativeGroundFlightServer.entry(player,level,clock);if(saved!=null&&NativePlanetSystem.multiple(level))level=NativePlanetSystem.level(level,saved.planet());spawn=saved==null?NativeStarterShip.home(level,NativePlanetSystem.shared(level)?NativeGroundDaylight.homeFace(clock.clock.model,NativePlanetShape.of(0)):0):NativePlanetAccess.of(level).feet(saved.planet(),saved.space()?NativeGroundTopology.locate(NativePlanetShape.of(saved.planet()),clock.clock.model.at(saved.planet(),saved.tick()).local(saved.position())):saved.local());}catch(IllegalArgumentException|IllegalStateException error){player.sendSystemMessage(Component.translatable("message.starfold.restore_failed",NativeWaypointServer.failure(error)));return 0;}
        LabSave.get(NativePlanetSystem.authority(level)).remember(player);border(level,0);player.teleportTo(level,spawn.x(),spawn.y(),spawn.z(),saved==null?NativeStarterShip.homeYaw(level):0,0);whole(player);send(player,clock);NativeGroundFlightServer.enter(player,clock,saved);
        return 1;
    }
    private static void border(net.minecraft.server.level.ServerLevel level,int face){level.getWorldBorder().setCenter(0,0);level.getWorldBorder().setSize(60_000_000);}
    /** The running table takes the size and depth of every planet the system now has. */
    static void system(NativeSystemChunkGenerator system){int[] sizes=new int[system.count()],depths=new int[sizes.length];for(int id=0;id<sizes.length;id++){sizes[id]=system.planet(id).terrain().shape().size();depths[id]=system.planet(id).generation().depth();}NativePlanetShape.system(sizes,depths);NativePlanetSystem.names(system.names());NativePlanetSystem.identities(java.util.stream.IntStream.range(0,system.count()).mapToObj(id->NativePlanetIdentity.of(system.planet(id))).toList());}
    static void shapes(ServerPlayer player){if(!player.connection.hasChannel(NativeGroundPackets.Shapes.TYPE))return;int[] sizes=new int[NativePlanetShape.count()],depths=new int[sizes.length];for(int id=0;id<sizes.length;id++){sizes[id]=NativePlanetShape.of(id).size();depths[id]=NativePlanetShape.depth(id);}net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new NativeGroundPackets.Shapes(sizes,depths,NativePlanetSystem.names(),java.util.stream.IntStream.range(0,sizes.length).mapToObj(NativePlanetSystem::identity).toList()));}
    /** The model each player was last sent, and when: it is sent again when it is another model, and every WHOLE ticks
     *  in case the first was missed while the client was between worlds. The table of shapes goes with it. */
    private static final java.util.Map<java.util.UUID,GroundEphemeris.Model> skies=new java.util.HashMap<>();
    private static final java.util.Map<java.util.UUID,Long> wholes=new java.util.HashMap<>();
    public static final int WHOLE=1200;
    /** Entry/login metadata for a passive observer, with the normal eager whole-model retries. */
    static void regionSky(ServerPlayer player){whole(player);send(player,NativeGroundClock.get(player.serverLevel()));}
    private static void send(ServerPlayer player,NativeGroundClock clock){
        if(!player.connection.hasChannel(NativeGroundPackets.Sky.TYPE))return;var model=clock.clock.model;long now=player.level().getGameTime();
        int eager=arriving.getOrDefault(player.getUUID(),0);if(eager>0)arriving.put(player.getUUID(),eager-1);
        boolean whole=eager>0||skies.get(player.getUUID())!=model||now-wholes.getOrDefault(player.getUUID(),-1000L)>=WHOLE;
        if(whole){shapes(player);skies.put(player.getUUID(),model);wholes.put(player.getUUID(),now);}
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,new NativeGroundPackets.Sky(clock.clock.tick(),whole?model:null));
    }
    /** The next sky a player is sent is whole: they have just come, or come back. */
    static void whole(ServerPlayer player){skies.remove(player.getUUID());wholes.remove(player.getUUID());arriving.put(player.getUUID(),ARRIVING);}
    /** A client between worlds drops what it is sent: for its first seconds every sky is whole. */
    private static final java.util.Map<java.util.UUID,Integer> arriving=new java.util.HashMap<>();
    public static final int ARRIVING=5;
    private static int face(ServerPlayer player,int face){if(NativeSpaceServer.in(player))return regionMessage(player);if(!is(player.level()))return 0;border(player.serverLevel(),face);var spawn=NativePlanetSpawn.site(player.serverLevel(),NativePlanetSystem.planet(player),face);player.teleportTo(player.serverLevel(),spawn.x(),spawn.y(),spawn.z(),0,0);send(player,NativeGroundClock.get(player.serverLevel()));NativeGroundFlightServer.site(player);return status(player);}
    private static int spin(ServerPlayer player,boolean on){if(!is(player.level()))return 0;var clock=NativeGroundClock.get(player.serverLevel());clock.spin(on);send(player,clock);NativeGroundFlightServer.restart(player,clock);return status(player);}
    private static int status(ServerPlayer player){if(NativeSpaceServer.in(player))return regionMessage(player);if(!is(player.level()))return 0;player.sendSystemMessage(Component.translatable("message.starfold.status",NativePlanetSystem.shared(player.level())?NativePlanetSystem.name(NativePlanetSystem.planet(player)):"A",NativePlanetAccess.of(player.level()).face(player.getX())+1,Math.round(100*NativeGroundDaylight.daylight(player.serverLevel(),player.blockPosition()))));return 1;}
    /** Every planet of this level: its name, size, depth, what it is made of and how far it is. */
    private static int universe(ServerPlayer player){
        if(NativeSpaceServer.in(player))return regionMessage(player);
        if(!is(player.level())||!NativePlanetSystem.shared(player.level()))return 0;var level=player.serverLevel();var clock=NativeGroundClock.get(level);var model=clock.clock.model;int here=NativePlanetSystem.planet(player);var from=model.at(here,clock.clock.tick()).center();
        player.sendSystemMessage(Component.translatable("message.starfold.universe",model.everyStar().size(),model.count(),NativePlanetSystem.name(here)));
        for(int id=0;id<model.count();id++){var g=((NativeSystemChunkGenerator)level.getChunkSource().getGenerator()).planet(id);var centre=model.at(id,clock.clock.tick()).center();var star=model.star(centre);
            player.sendSystemMessage(Component.translatable("message.starfold.planet",NativePlanetSystem.name(id),id,g.terrain().shape().size(),g.generation().depth(),Component.translatable(g.generation().rocky()?g.generation().palette().cover().orElse(g.generation().palette().rock()).getDescriptionId():"message.starfold.living"),model.everyStar().indexOf(star)+1,Math.round(centre.sub(from).length())));}
        return 1;
    }
    public static int leave(ServerPlayer player){if(NativeSpaceServer.in(player))return regionMessage(player);if(!is(player.level()))return 0;player.addTag(STAY);arrived.add(player.getUUID());NativeGroundFlightServer.detach(player);NativeFlightPermit.revoke(player);if(!LabSave.get(NativePlanetSystem.authority(player.serverLevel())).restore(player)){var target=player.getServer().overworld();var at=target.getSharedSpawnPos();player.teleportTo(target,at.getX()+.5,at.getY()+1,at.getZ()+.5,0,0);}return 1;}
    static int regionMessage(ServerPlayer player){player.sendSystemMessage(Component.translatable("message.starfold.space_region"));return 0;}
    private NativeGroundServer(){}
}
