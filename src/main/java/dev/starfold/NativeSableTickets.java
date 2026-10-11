package dev.starfold;

import dev.starfold.BentStripGeometry.Point;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import java.util.*;

/** Cargo lives in raw entity sections, including the trailing halo of a hull in its new chart. */
final class NativeSableTickets {
    private static final TicketType<Long> TYPE=TicketType.create("starfold_sable_cargo",Long::compare);
    private static final Map<ServerLevel,Set<Long>> held=new HashMap<>();
    static void update(ServerLevel level,dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container){
        var wanted=new HashSet<Long>();
        if(container!=null)for(var body:container.getAllSubLevels())if(!body.isRemoved()){
            var bounds=body.boundingBox();var box=new net.minecraft.world.phys.AABB(bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxX(),bounds.maxY(),bounds.maxZ()).inflate(32);
            columns(wanted,box);
            // Ask ahead of the bow, while the destination's raw sections can still become entity-ticking.
            for(int i=0;i<4;i++){
                var t=NativeGroundSeams.transport(level,new Point((i&1)==0?box.minX:box.maxX,body.logicalPose().position().y,(i&2)==0?box.minZ:box.maxZ));
                if(t!=null)columns(wanted,box,t);
            }
        }
        var previous=held.getOrDefault(level,Set.of());
        for(long key:wanted)if(!previous.contains(key))level.getChunkSource().addRegionTicket(TYPE,new ChunkPos(key),2,key);
        for(long key:previous)if(!wanted.contains(key))level.getChunkSource().removeRegionTicket(TYPE,new ChunkPos(key),2,key);
        if(wanted.isEmpty())held.remove(level);else held.put(level,Set.copyOf(wanted));
    }
    /** Quarter turns preserve an axis-aligned box, including its noncanonical trailing edge. */
    static void columns(Set<Long> result,net.minecraft.world.phys.AABB box,NativeGroundSeams.Transport t){var a=t.apply(new Point(box.minX,box.minY,box.minZ));var b=t.apply(new Point(box.maxX,box.maxY,box.maxZ));columns(result,new net.minecraft.world.phys.AABB(a.x(),a.y(),a.z(),b.x(),b.y(),b.z()));}
    static void columns(Set<Long> result,net.minecraft.world.phys.AABB box){for(int x=(int)Math.floor(box.minX)>>4;x<=((int)Math.floor(box.maxX)>>4);x++)for(int z=(int)Math.floor(box.minZ)>>4;z<=((int)Math.floor(box.maxZ)>>4);z++)result.add(ChunkPos.asLong(x,z));}
    static void clear(){for(var level:List.copyOf(held.keySet()))update(level,null);}
    private NativeSableTickets(){}
}
