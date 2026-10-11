package dev.starfold;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Display subscriptions only: no writes, block actions, inventory or BlockEntity save NBT. */
public final class NativeGroundViewPackets {
    public static final long CHUNKS=6L*128*128;
    /** The client has bound this session's far-cover cache and wants profile/coverage (re)sent. */
    public record Hello(long session) implements CustomPacketPayload {
        public Hello{if(session==0)throw new IllegalArgumentException("Native view hello");}
        public static final Type<Hello> TYPE=new Type<>(ResourceLocation.parse("starfold:native_ground_v2_view_hello"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Hello> CODEC=StreamCodec.of((b,p)->b.writeLong(p.session),b->new Hello(b.readLong()));
        @Override public Type<Hello> type(){return TYPE;}
    }
    public record Tile(long key,long known){public Tile{if(key<0||key>=CHUNKS||known< -1)throw new IllegalArgumentException("Native view tile");}}
    public record Request(long session,List<Tile> tiles) implements CustomPacketPayload {
        public Request{if(session==0||tiles==null||tiles.isEmpty()||tiles.size()>NativeGroundStreaming.REQUEST_TILES)throw new IllegalArgumentException("Native view subscription");tiles=List.copyOf(tiles);var seen=new HashSet<Long>();for(var tile:tiles)if(!seen.add(tile.key()))throw new IllegalArgumentException("Duplicate native tile");}
        public static final Type<Request> TYPE=new Type<>(ResourceLocation.parse("starfold:native_ground_v2_view_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->{b.writeLong(p.session);b.writeVarInt(p.tiles.size());for(var tile:p.tiles){b.writeVarLong(tile.key);b.writeVarLong(tile.known);}},b->{long id=b.readLong();int count=b.readVarInt();if(count<1||count>NativeGroundStreaming.REQUEST_TILES)throw new IllegalArgumentException("Native view request size");var out=new ArrayList<Tile>(count);for(int i=0;i<count;i++)out.add(new Tile(b.readVarLong(),b.readVarLong()));return new Request(id,out);});
        @Override public Type<Request> type(){return TYPE;}
    }
    public record Image(long session,NativeGroundChunkSnapshot chunk,List<NativeGroundDisplayEntities.Image> entities) implements CustomPacketPayload {
        public Image(long session,NativeGroundChunkSnapshot chunk){this(session,chunk,List.of());}
        public Image{if(session==0||chunk==null||entities==null||entities.size()>1024)throw new IllegalArgumentException("Native view image");entities=List.copyOf(entities);var seen=new HashSet<net.minecraft.core.BlockPos>();for(var entity:entities)if(!chunk.owns(entity.pos())||!seen.add(entity.pos())||!chunk.state(entity.pos()).hasBlockEntity())throw new IllegalArgumentException("Native display entity owner");}
        public static final Type<Image> TYPE=new Type<>(ResourceLocation.parse("starfold:native_ground_v2_view_image"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Image> CODEC=StreamCodec.of((b,p)->{b.writeLong(p.session);p.chunk.write(b);b.writeVarInt(p.entities.size());for(var entity:p.entities){b.writeBlockPos(entity.pos());b.writeResourceLocation(entity.type());b.writeNbt(entity.update());}},b->{long session=b.readLong();var chunk=NativeGroundChunkSnapshot.read(b);int count=b.readVarInt();if(count<0||count>1024)throw new IllegalArgumentException("Native entity image count");var entities=new ArrayList<NativeGroundDisplayEntities.Image>(count);var quota=net.minecraft.nbt.NbtAccounter.create(128*1024);for(int i=0;i<count;i++){var pos=b.readBlockPos();var type=b.readResourceLocation();var tag=b.readNbt(quota);if(!(tag instanceof net.minecraft.nbt.CompoundTag compound))throw new IllegalArgumentException("Native display tag");entities.add(new NativeGroundDisplayEntities.Image(pos,type,compound));}return new Image(session,chunk,entities);});
        @Override public Type<Image> type(){return TYPE;}
    }
    public record Delta(long session,long epoch,net.minecraft.core.BlockPos pos,int state) implements CustomPacketPayload {
        public Delta{if(session==0||epoch<0||pos==null||!NativeGroundTopology.address(pos).owner().equals(pos)||net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(state)==null)throw new IllegalArgumentException("Native display delta");pos=pos.immutable();}
        public static final Type<Delta> TYPE=new Type<>(ResourceLocation.parse("starfold:native_ground_v2_delta"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Delta> CODEC=StreamCodec.of((b,p)->{b.writeLong(p.session);b.writeVarLong(p.epoch);b.writeBlockPos(p.pos);b.writeVarInt(p.state);},b->new Delta(b.readLong(),b.readVarLong(),b.readBlockPos(),b.readVarInt()));
        @Override public Type<Delta> type(){return TYPE;}
    }
    public record Event(long session,net.minecraft.core.BlockPos pos,int id,int data) implements CustomPacketPayload {
        public Event{if(session==0||pos==null||!NativeGroundTopology.address(pos).owner().equals(pos))throw new IllegalArgumentException("Native display event");pos=pos.immutable();}
        public static final Type<Event> TYPE=new Type<>(ResourceLocation.parse("starfold:native_ground_v2_block_event"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Event> CODEC=StreamCodec.of((b,p)->{b.writeLong(p.session);b.writeBlockPos(p.pos);b.writeInt(p.id);b.writeInt(p.data);},b->new Event(b.readLong(),b.readBlockPos(),b.readInt(),b.readInt()));
        @Override public Type<Event> type(){return TYPE;}
    }
    public record Coverage(long session,long revision,boolean flat,long[] changed) implements CustomPacketPayload {
        public Coverage{if(session==0||revision<0||changed==null||changed.length>1536||BitSet.valueOf(changed).length()>CHUNKS)throw new IllegalArgumentException("Native coverage");changed=changed.clone();}
        @Override public long[] changed(){return changed.clone();}
        public static final Type<Coverage> TYPE=new Type<>(ResourceLocation.parse("starfold:native_ground_v2_coverage"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Coverage> CODEC=StreamCodec.of((b,p)->{b.writeLong(p.session);b.writeVarLong(p.revision);b.writeBoolean(p.flat);b.writeLongArray(p.changed);},b->new Coverage(b.readLong(),b.readVarLong(),b.readBoolean(),b.readLongArray(new long[0],1536)));
        @Override public Type<Coverage> type(){return TYPE;}
    }
    public record Surface(long session,long key,NativeGroundViewCache.Surface image) implements CustomPacketPayload {
        public Surface{if(session==0||key<0||key>=CHUNKS||image==null)throw new IllegalArgumentException("Native surface packet");}
        public static final Type<Surface> TYPE=new Type<>(ResourceLocation.parse("starfold:native_ground_v2_surface"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Surface> CODEC=StreamCodec.of((b,p)->{b.writeLong(p.session);b.writeVarLong(p.key);b.writeVarLong(p.image.revision());b.writeVarIntArray(p.image.heights());b.writeVarIntArray(p.image.states());},b->new Surface(b.readLong(),b.readVarLong(),new NativeGroundViewCache.Surface(b.readVarLong(),b.readVarIntArray(256),b.readVarIntArray(256))));
        @Override public Type<Surface> type(){return TYPE;}
    }
    /** Replacement subscription, not an unbounded backlog. The client sends one request per
     * REQUEST_TICKS; half that interval is tolerated so tick jitter cannot drop every other request. */
    public static final class Subscription {
        private final ArrayDeque<Tile> queue=new ArrayDeque<>();private long received=Long.MIN_VALUE;
        public boolean replace(Request request,long tick){if(tick<0||received!=Long.MIN_VALUE&&tick-received<NativeGroundStreaming.REQUEST_TICKS/2)return false;received=tick;queue.clear();queue.addAll(request.tiles());return true;}
        public Tile poll(){return queue.pollFirst();}public int size(){return queue.size();}
    }
    private NativeGroundViewPackets(){}
}
