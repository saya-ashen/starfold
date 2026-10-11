package dev.starfold;

import net.minecraft.server.level.*;
import java.util.*;

/** Bounded pull of actual loaded chunks. Does not generate/load the whole planet for a view. */
public final class NativeGroundViewServer {
    private record Sent(NativeGroundChunkSnapshot image,long version,long sampledAt,List<NativeGroundDisplayEntities.Image> entities){}
    private static final Map<ServerLevel,NativeGroundViewVersions> versions=new HashMap<>();
    private static final class Viewer {
        long coverageRevision=-1;int cursor;boolean profiled;final Map<Long,Long> surfaces=new HashMap<>();
        final long session;final NativeGroundViewPackets.Subscription subscription=new NativeGroundViewPackets.Subscription();
        final LinkedHashMap<Long,Sent> sent=new LinkedHashMap<>(16,.75f,true);
        Viewer(long id){session=id;}
    }
    private static final Map<UUID,Viewer> viewers=new HashMap<>();
    private static final Map<ServerLevel,LinkedHashSet<net.minecraft.core.BlockPos>> changes=new HashMap<>();
    public static void initialize(){
        LocalNet.playS2C(NativePlanetPackets.Profile.TYPE,NativePlanetPackets.Profile.CODEC);
        LocalNet.playS2C(NativeGroundViewPackets.Delta.TYPE,NativeGroundViewPackets.Delta.CODEC);
        LocalNet.playS2C(NativeGroundViewPackets.Event.TYPE,NativeGroundViewPackets.Event.CODEC);
        LocalNet.playS2C(NativeGroundViewPackets.Coverage.TYPE,NativeGroundViewPackets.Coverage.CODEC);
        LocalNet.playS2C(NativeGroundViewPackets.Surface.TYPE,NativeGroundViewPackets.Surface.CODEC);
        LocalNet.playC2S(NativeGroundViewPackets.Request.TYPE,NativeGroundViewPackets.Request.CODEC);
        LocalNet.playC2S(NativeGroundViewPackets.Hello.TYPE,NativeGroundViewPackets.Hello.CODEC);
        // A fresh viewer resends profile and coverage; the client asks once its cache is bound to the session.
        LocalNet.serverReceiver(NativeGroundViewPackets.Hello.TYPE,(packet,c)->{var p=((net.minecraft.server.level.ServerPlayer)c.player());if(!NativeSpaceServer.in(p)&&NativeGroundServer.is(p.level())&&packet.session()==NativeGroundFlightServer.session(p))viewers.put(p.getUUID(),new Viewer(packet.session()));});
        LocalNet.playS2C(NativeGroundViewPackets.Image.TYPE,NativeGroundViewPackets.Image.CODEC);
        LocalNet.serverReceiver(NativeGroundViewPackets.Request.TYPE,(packet,c)->{
            var p=((net.minecraft.server.level.ServerPlayer)c.player());if(NativeSpaceServer.in(p)||!NativeGroundServer.is(p.level())||packet.session()!=NativeGroundFlightServer.session(p)||!p.connection.hasChannel(NativeGroundViewPackets.Image.TYPE))return;
            var v=viewers.get(p.getUUID());if(v==null||v.session!=packet.session()){v=new Viewer(packet.session());viewers.put(p.getUUID(),v);}v.subscription.replace(packet,p.level().getGameTime());
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent cpEvent)->{var h=((net.minecraft.server.level.ServerPlayer)cpEvent.getEntity()).connection;var s=h.player.getServer();remove(h.player);});net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent cpEvent)->{viewers.clear();changes.clear();versions.clear();});
    }
    public static void changed(ServerLevel level,net.minecraft.core.BlockPos pos,net.minecraft.world.level.block.state.BlockState state){if(NativePlanetAccess.of(level).key(pos.getX()>>4,pos.getZ()>>4)<0)return;NativeImageStream.changed(level,pos);versions.computeIfAbsent(level,l->new NativeGroundViewVersions()).changed(NativePlanetAccess.of(level).key(pos.getX()>>4,pos.getZ()>>4));var pending=changes.computeIfAbsent(level,l->new LinkedHashSet<>());pending.add(pos.immutable());while(pending.size()>8192)pending.remove(pending.iterator().next());}
    private static boolean belongs(ServerPlayer p,net.minecraft.core.BlockPos pos){return !NativeSpaceServer.in(p)&&(!NativePlanetSystem.shared(p.level())||NativePlanetSystem.planet(p)==NativePlanetStorage.find(pos.getX(),pos.getZ()));}
    private static net.minecraft.core.BlockPos local(ServerLevel level,net.minecraft.core.BlockPos pos){return NativePlanetSystem.shared(level)?NativePlanetStorage.local(NativePlanetStorage.planet(pos),pos):pos;}
    public static void event(ServerLevel level,net.minecraft.world.level.BlockEventData event){if(NativePlanetAccess.of(level).key(event.pos().getX()>>4,event.pos().getZ()>>4)<0)return;for(var p:level.players()){long id=NativeGroundFlightServer.session(p);if(id!=0&&belongs(p,event.pos())&&p.connection.hasChannel(NativeGroundViewPackets.Event.TYPE))net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new NativeGroundViewPackets.Event(id,local(level,event.pos()),event.paramA(),event.paramB()));}}
    public static void remove(ServerPlayer player){viewers.remove(player.getUUID());}
    public static boolean flat(ServerLevel level){
        if(!(level.getChunkSource().getGenerator() instanceof net.minecraft.world.level.levelgen.FlatLevelSource flat))return false;
        var settings=flat.settings();var flags=(dev.starfold.mixin.NativeGroundFlatAccess)(Object)settings;
        if(flags.starfold$decoration()||flags.starfold$lakes()||settings.structureOverrides().isEmpty()||settings.structureOverrides().get().size()!=0)return false;
        var layers=settings.getLayers();if(layers.size()!=192||level.getMinBuildHeight()!=-64)return false;
        for(int i=0;i<192;i++){var expected=i==0?net.minecraft.world.level.block.Blocks.BEDROCK:i<188?net.minecraft.world.level.block.Blocks.STONE:i<191?net.minecraft.world.level.block.Blocks.DIRT:net.minecraft.world.level.block.Blocks.GRASS_BLOCK;if(!layers.get(i).equals(expected.defaultBlockState()))return false;}return true;
    }
    public static void tick(ServerLevel level,NativeGroundClock clock){
        int budget=256;var updates=changes.computeIfAbsent(level,l->new LinkedHashSet<>()).iterator();while(updates.hasNext()&&budget-->0){var pos=updates.next();updates.remove();int state=net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(level.getBlockState(pos));for(var p:level.players()){long id=NativeGroundFlightServer.session(p);if(id!=0&&belongs(p,pos)&&p.connection.hasChannel(NativeGroundViewPackets.Delta.TYPE))net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new NativeGroundViewPackets.Delta(id,clock.clock.tick(),local(level,pos),state));}}
        // The index of every planet someone is on is kept up to date; the first two always were.
        var ticked=new java.util.TreeSet<Integer>();ticked.add(0);if(NativePlanetSystem.shared(level)){ticked.add(1);for(var p:level.players())if(!NativeSpaceServer.in(p))ticked.add(NativePlanetSystem.planet(p));}
        // A lead's destination gets the same non-loading index refresh before it owns a chart observer.
        for(var p:level.players())if(NativeSpaceServer.in(p)){int destination=NativeSpaceApproach.destination(p);if(destination>=0)ticked.add(destination);}
        for(int id:ticked)NativeGroundSurfaceIndex.get(level,id).tick(level,clock.clock.tick());
        for(var p:level.players()){
            if(NativeSpaceServer.in(p)){remove(p);NativePlanetRemoteServer.tick(p,clock);continue;}
            int planet=NativePlanetSystem.planet(p),offset=NativePlanetSystem.shared(level)?NativePlanetStorage.offset(planet)/16:0,offsetZ=NativePlanetSystem.shared(level)?NativePlanetStorage.offsetZ(planet)/16:0;var index=NativeGroundSurfaceIndex.get(level,planet);
            NativePlanetRemoteServer.tick(p,clock);
            var v=viewers.get(p.getUUID());if(v==null)continue;
            if(v.session!=NativeGroundFlightServer.session(p)){remove(p);continue;}
            var source=level.getChunkSource().getGenerator();var generator=source instanceof NativeSystemChunkGenerator system?system.planet(planet):source instanceof NativePlanetChunkGenerator single?single:null;
            if(!v.profiled&&generator!=null){if(!p.connection.hasChannel(NativePlanetPackets.Profile.TYPE))continue;net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,NativePlanetPackets.Profile.of(v.session,generator));v.profiled=true;}
            if(v.coverageRevision!=index.revision()){v.coverageRevision=index.revision();net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new NativeGroundViewPackets.Coverage(v.session,index.revision(),flat(level),index.coverage().toLongArray()));}
            var keys=index.coverage();int count=keys.cardinality();if(count>0){int key=keys.nextSetBit(v.cursor);if(key<0)key=keys.nextSetBit(0);v.cursor=key+1;var surface=index.surface(key);if(surface!=null&&!index.dirty(key)&&v.surfaces.getOrDefault((long)key,-1L)!=surface.revision()){v.surfaces.put((long)key,surface.revision());net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new NativeGroundViewPackets.Surface(v.session,key,surface));}}
            // Several volumes per tick under a time budget: a 17-wide row of new owners per chunk step
            // (and a cold window across a seam) must not queue behind one capture per tick.
            long deadline=System.nanoTime()+NativeGroundStreaming.SERVE_BUDGET_NS;
            for(int served=0;served<NativeGroundStreaming.SERVE_TILES&&(served==0||System.nanoTime()<deadline);){
            var tile=v.subscription.poll();if(tile==null)break;int face=(int)(tile.key()/(128*128)),u=(int)(tile.key()%128),z=(int)(tile.key()/128%128);
            // Unknown remains unknown. Server chunk tickets/vanilla tracking own loading, not this cache.
            var chunk=level.getChunkSource().getChunkNow(offset+face*NativeGroundCoordinates.STRIDE/16+u,offsetZ+z);
            if(chunk==null&&!NativePlanetSystem.shared(level)&&keys.get((int)tile.key()))chunk=level.getChunk(offset+face*NativeGroundCoordinates.STRIDE/16+u,offsetZ+z);
            if(chunk==null)continue;
            var prior=v.sent.get(tile.key());long version=versions.computeIfAbsent(level,l->new NativeGroundViewVersions()).get(tile.key()+(NativePlanetSystem.shared(level)?(long)planet*NativePlanetStorage.CHUNKS_PER_PLANET:0)),now=level.getGameTime();boolean refresh=NativeGroundViewPolicy.captureNeeded(prior!=null,version,prior==null?-1:prior.version,now,prior==null?-1:prior.sampledAt);
            if(refresh)served++;
            NativeGroundChunkSnapshot image;long sampledAt;if(refresh){image=NativeGroundChunkSnapshot.capture(chunk,clock.clock.tick());sampledAt=now;}else{image=prior.image;sampledAt=prior.sampledAt;}
            var entities=NativeGroundDisplayEntities.capture(chunk,level.registryAccess());
            if(prior!=null&&prior.image.fingerprint().equals(image.fingerprint())&&prior.entities.equals(entities)){
                image=prior.image;v.sent.put(tile.key(),new Sent(image,version,sampledAt,entities));if(tile.known()==image.revision())continue;
            }
            v.sent.put(tile.key(),new Sent(image,version,sampledAt,entities));while(v.sent.size()>NativeGroundViewCache.DETAIL_LIMIT||v.sent.values().stream().mapToLong(s->s.image.memoryBytes()).sum()>NativeGroundViewCache.BYTE_LIMIT)v.sent.remove(v.sent.keySet().iterator().next());
            if(!refresh)served++;
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,new NativeGroundViewPackets.Image(v.session,image,entities));
            }
        }
    }
    private NativeGroundViewServer(){}
}
