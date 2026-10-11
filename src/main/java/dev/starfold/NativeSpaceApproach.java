package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import java.util.*;

/** Predict the current handoff, without moving a body or borrowing a player's chart session. */
final class NativeSpaceApproach {
    static final double APPROACH=4096,PREPARE=8192;
    static final int RADIUS=3,INTERVAL=10;
    private static final TicketType<Long> TYPE=TicketType.create("starfold_space_approach",Long::compare);
    private record Held(ServerSubLevel body,long tick,int planet,ChunkPos center,Set<Long> columns){}
    private static final Map<ServerLevel,Held> held=new HashMap<>();
    static ChunkPos column(Point anchor){return new ChunkPos((int)Math.floor(anchor.x())>>4,(int)Math.floor(anchor.z())>>4);}
    static boolean ready(double height,boolean full){return NativeSpace.enters(height)&&(full||height<NativeSpace.IN-512);}
    static boolean full(ServerLevel level,Point anchor){var c=column(anchor);for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)if(level.getChunkSource().getChunkNow(c.x+x,c.z+z)==null)return false;return true;}
    /** Integrate the existing governors in centre-relative inertial space, including future planet spin.
     * No tickets or world reads; a bounded forecast is replaced on the next cadence if the pilot turns. */
    static NativeSpace.Arrival predict(GroundEphemeris.Model model,int planet,NativePlanetShape shape,NativeSpace.Motion motion,double tick){
        var position=motion.position().sub(model.at(planet,tick).center());var velocity=motion.velocity().sub(model.at(planet,tick).velocity());int steps=0;
        for(;steps<600;steps++){
            double height=position.length()-shape.radius();if(height<=NativeSpace.IN||velocity.dot(position)>=0)break;
            var toward=position.scale(-1);var radial=NativeSpaceServer.arrival(velocity,toward,velocity,height);velocity=NativeSpaceServer.near(radial,toward,radial,height);position=position.add(velocity);
        }
        double future=tick+steps;var frame=model.at(planet,future);
        return NativeSpace.in(model,planet,shape,new NativeSpace.Motion(position.add(frame.center()),motion.orientation(),velocity.add(frame.velocity()),motion.spin()),future);
    }
    static ChunkPos stable(ChunkPos previous,ChunkPos predicted){return previous!=null&&Math.abs(previous.x-predicted.x)<=1&&Math.abs(previous.z-predicted.z)<=1?previous:predicted;}
    /** Lead changes, empty regions and removed bodies release before the cadence check. */
    static void update(ServerLevel level,ServerSubLevelContainer container,ServerSubLevel lead){
        long start=NativeArrivalProbe.start();try{updateMeasured(level,container,lead);}finally{NativeArrivalProbe.end("approach",start);}
    }
    private static void updateMeasured(ServerLevel level,ServerSubLevelContainer container,ServerSubLevel lead){
        var old=held.get(level);if(old!=null&&(old.body()!=lead||old.body().isRemoved()))release(level);
        if(lead==null)return;
        var at=lead.logicalPose().position();var clock=NativeGroundClock.get(level).clock;var access=NativePlanetAccess.of(level);
        var universe=NativeSpaceServer.frame(level).universe(NativeSableFrames.motion(container,lead,new Point(at.x,at.y,at.z)),clock.model,clock.tick());
        int planet=NativeSpace.nearest(clock.model,universe.position(),clock.tick());var shape=access==NativePlanetAccess.SHARED?NativePlanetShape.of(planet):NativePlanetShape.STANDARD;
        if(!NativeSpace.holds(at.x,at.z)||access==NativePlanetAccess.LEGACY&&planet!=(clock.model.count()==1?0:NativePlanetSystem.planet(level))||NativeSpace.height(clock.model,planet,shape,universe.position(),clock.tick())>=PREPARE){release(level);return;}
        old=held.get(level);long tick=level.getGameTime();if(old!=null&&tick-old.tick()<INTERVAL)return;
        var arrival=predict(clock.model,planet,shape,universe,clock.tick());var anchor=access.bodyAnchor(planet,arrival.pose());var predicted=column(anchor);var center=stable(old!=null&&old.planet()==planet?old.center():null,predicted);var wanted=new HashSet<Long>();
        for(int x=-RADIUS;x<=RADIUS;x++)for(int z=-RADIUS;z<=RADIUS;z++)wanted.add(ChunkPos.asLong(center.x+x,center.z+z));
        // Transform all eight corners of the old world AABB conservatively into the destination.
        var bounds=lead.boundingBox();
        var turn=arrival.motion().orientation().mul(new org.joml.Quaterniond(lead.logicalPose().orientation()).conjugate()).normalize();
        double minX=Double.POSITIVE_INFINITY,minZ=minX,maxX=Double.NEGATIVE_INFINITY,maxZ=maxX;
        for(int i=0;i<8;i++){var p=turn.transform(new org.joml.Vector3d((i&1)==0?bounds.minX():bounds.maxX(),(i&2)==0?bounds.minY():bounds.maxY(),(i&4)==0?bounds.minZ():bounds.maxZ()).sub(at));minX=Math.min(minX,p.x+anchor.x());maxX=Math.max(maxX,p.x+anchor.x());minZ=Math.min(minZ,p.z+anchor.z());maxZ=Math.max(maxZ,p.z+anchor.z());}
        for(int x=(int)Math.floor(minX-1)>>4;x<=((int)Math.floor(maxX+1)>>4);x++)for(int z=(int)Math.floor(minZ-1)>>4;z<=((int)Math.floor(maxZ+1)>>4);z++)wanted.add(ChunkPos.asLong(x,z));
        // Raw columns satisfy Sable; canonical owners also satisfy the chart's image stream at seams.
        for(long key:List.copyOf(wanted)){int x=ChunkPos.getX(key),z=ChunkPos.getZ(key);if(NativeChunkViews.ghost(access,x,z))for(long owner:NativeChunkViews.columns(access,x,z).owners())wanted.add(owner);}
        var previous=old==null?Set.<Long>of():old.columns();for(long key:wanted)if(!previous.contains(key))level.getChunkSource().addRegionTicket(TYPE,new ChunkPos(key),0,key);
        for(long key:previous)if(!wanted.contains(key))level.getChunkSource().removeRegionTicket(TYPE,new ChunkPos(key),0,key);
        held.put(level,new Held(lead,tick,planet,center,Set.copyOf(wanted)));
        if(Boolean.getBoolean("starfold.carryProbe"))org.slf4j.LoggerFactory.getLogger("starfold-probe").info("PROBE APPROACH tick {} planet {} column {} {} tickets {} full {} height {} predicted {}",tick,planet,center.x,center.z,wanted.size(),full(level,anchor),NativeSpace.height(clock.model,planet,shape,universe.position(),clock.tick()),predicted);
    }
    static int destination(ServerPlayer player){var value=held.get(player.serverLevel());return value!=null&&NativeSableFrames.aboard(player)==value.body()?value.planet():-1;}
    static void arrived(ServerLevel level,ServerSubLevel body){var value=held.get(level);if(value!=null&&value.body()==body)release(level);}
    static void release(ServerLevel level){var value=held.remove(level);if(value!=null)for(long key:value.columns())level.getChunkSource().removeRegionTicket(TYPE,new ChunkPos(key),0,key);}
    static void clear(){for(var level:List.copyOf(held.keySet()))release(level);}
    private NativeSpaceApproach(){}
}
