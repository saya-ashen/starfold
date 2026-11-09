package dev.starfold.universe;

import dev.starfold.worldgen.plan.PlanetPlan;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.Consumer;

/** Separate bounded plan packets. A referenced Profile is published only after the whole digest checks. */
public final class PlanetPlanSync {
    public static final int PART_BYTES=262_144;
    public static boolean acceptsSession(long incoming,long ground,long space,long retained) {
        return incoming!=0 && (incoming==ground || incoming==space || incoming==retained);
    }
    public record Reference(String hash,int bytes) {
        public Reference {
            if(hash==null || !hash.matches("[0-9a-f]{64}") || bytes<1 || bytes>PlanetPlan.TRANSFER_LIMIT)
                throw new IllegalArgumentException("Planet plan reference");
        }
        public int parts() { return (bytes+PART_BYTES-1)/PART_BYTES; }
    }
    public record Part(long session,long seed,Reference reference,int index,byte[] bytes) implements CustomPacketPayload {
        public Part {
            if(session==0 || reference==null || index<0 || index>=reference.parts() || bytes==null
                    || bytes.length!=Math.min(PART_BYTES,reference.bytes-index*PART_BYTES))throw new IllegalArgumentException("Planet plan part");
            bytes=bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
        public static final Type<Part> TYPE=new Type<>(ResourceLocation.parse("starfold:native_planet_v12_plan_part"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Part> CODEC=StreamCodec.of((b,p)->{
            b.writeLong(p.session);b.writeLong(p.seed);b.writeUtf(p.reference.hash,64);b.writeVarInt(p.reference.bytes);
            b.writeVarInt(p.index);b.writeByteArray(p.bytes);
        },b->new Part(b.readLong(),b.readLong(),new Reference(b.readUtf(64),b.readVarInt()),b.readVarInt(),b.readByteArray(PART_BYTES)));
        @Override public Type<Part> type() { return TYPE; }
    }
    /** Only a previously announced transfer may be requested again; this never starts planet generation. */
    public record Request(long session,long seed,Reference reference) implements CustomPacketPayload {
        public Request {
            if(session==0 || reference==null)throw new IllegalArgumentException("Planet plan request");
        }
        public static final Type<Request> TYPE=new Type<>(ResourceLocation.parse("starfold:native_planet_v12_plan_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Request> CODEC=StreamCodec.of((b,p)->{
            b.writeLong(p.session);b.writeLong(p.seed);b.writeUtf(p.reference.hash,64);b.writeVarInt(p.reference.bytes);
        },b->new Request(b.readLong(),b.readLong(),new Reference(b.readUtf(64),b.readVarInt())));
        public boolean matches(PlanetPackets.Profile profile) {
            return profile!=null && session==profile.session() && seed==profile.seed()
                    && profile.planReference().filter(reference::equals).isPresent();
        }
        @Override public Type<Request> type() { return TYPE; }
    }
    public record Prepared(PlanetPackets.Profile profile,List<Part> parts,int fullProfileBytes,int maxPacketBytes) {
        public Prepared { parts=List.copyOf(parts); }
    }
    public static Prepared prepare(PlanetPackets.Profile profile,RegistryAccess registries) {
        if(profile.plan().isEmpty())return new Prepared(profile,List.of(),encodedSize(profile,registries),encodedSize(profile,registries));
        var plan=profile.plan().orElseThrow();var data=plan.compressed();
        int base=encodedSize(profile.withPlan(Optional.empty(),Optional.empty()),registries);
        int lengthBytes=1;for(int n=data.length;n>=128;n>>>=7)lengthBytes++;
        int full=base+lengthBytes+data.length;
        // Always by reference: Summaries repeat every second and must not carry the plan itself.
        var reference=new Reference(plan.identity(),data.length);
        var metadata=profile.withPlan(Optional.empty(),Optional.of(reference));
        int maximum=encodedSize(metadata,registries);var parts=new ArrayList<Part>();
        for(int i=0;i<reference.parts();i++) {
            var part=new Part(profile.session(),profile.seed(),reference,i,Arrays.copyOfRange(data,i*PART_BYTES,Math.min(data.length,(i+1)*PART_BYTES)));
            parts.add(part);
            var raw=Unpooled.buffer();
            try {Part.CODEC.encode(new RegistryFriendlyByteBuf(raw,registries),part);maximum=Math.max(maximum,raw.readableBytes());}
            finally {raw.release();}
        }
        return new Prepared(metadata,parts,full,maximum);
    }
    private static int encodedSize(PlanetPackets.Profile profile,RegistryAccess registries) {
        var raw=Unpooled.buffer();
        try {PlanetPackets.Profile.CODEC.encode(new RegistryFriendlyByteBuf(raw,registries),profile);return raw.readableBytes();}
        finally {raw.release();}
    }
    private record Key(long seed,Reference reference) {}
    private static final class Incoming {
        final byte[][] parts;
        final Map<Object,Runnable> waiting=new LinkedHashMap<>();
        PlanetPlan complete;
        Incoming(int count) { parts=new byte[count][]; }
    }
    /** At most 16 transfers and 32 MB of compressed data per session; no partial terrain escapes. */
    // Plans are immutable and named by digest: one decoded copy serves every session, view and re-sent transfer.
    private static final Map<String,PlanetPlan> COMPLETE=new LinkedHashMap<>(64,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,PlanetPlan> eldest) { return size()>64; }
    };
    public static void clearCompleted() { synchronized(COMPLETE) { COMPLETE.clear(); } }
    static PlanetPlan complete(Reference reference) {
        synchronized(COMPLETE) { return COMPLETE.get(reference.hash); }
    }
    public static final class Assembler {
        private final Map<Key,Incoming> incoming=new LinkedHashMap<>(16,.75f,true);
        private long session;
        private int reserved;
        public void clear() {incoming.clear();session=0;reserved=0;}
        private Incoming state(long session,long seed,Reference reference) {
            if(this.session!=session) {clear();this.session=session;}
            var key=new Key(seed,reference);var existing=incoming.get(key);
            if(existing!=null)return existing;
            var oldest=incoming.entrySet().iterator();
            while((incoming.size()>=16 || (long)reserved+reference.bytes>32_000_000) && oldest.hasNext()) {
                var candidate=oldest.next();
                if(candidate.getValue().complete!=null) {reserved-=candidate.getKey().reference.bytes;oldest.remove();}
            }
            if(incoming.size()>=16 || (long)reserved+reference.bytes>32_000_000)return null;
            var created=new Incoming(reference.parts());incoming.put(key,created);reserved+=reference.bytes;return created;
        }
        public void await(PlanetPackets.Profile profile,Object owner,Consumer<PlanetPackets.Profile> ready) {
            await(profile,owner,null,ready);
        }
        /** Returns true once for a resend. At capacity, the next Summary/Hello retries without reserving memory. */
        public boolean await(PlanetPackets.Profile profile,Object owner,PlanetPackets.Profile known,Consumer<PlanetPackets.Profile> ready) {
            if(profile.planReference().isEmpty()) {ready.accept(profile);return false;}
            var ref=profile.planReference().orElseThrow();
            // Active views own their complete plans independently of the bounded transfer cache.
            if(known!=null && known.session()==profile.session() && known.seed()==profile.seed()
                    && known.plan().filter(p->p.identity().equals(ref.hash)).isPresent()) {
                ready.accept(profile.withPlan(known.plan(),Optional.empty()));return false;
            }
            var finished=complete(ref);
            if(finished!=null) {ready.accept(profile.withPlan(Optional.of(finished),Optional.empty()));return false;}
            var entry=state(profile.session(),profile.seed(),ref);
            if(entry==null)return false;
            if(entry.complete!=null) {ready.accept(profile.withPlan(Optional.of(entry.complete),Optional.empty()));return false;}
            if(!entry.waiting.containsKey(owner) && entry.waiting.size()>=16)throw new IllegalArgumentException("Planet plan waiter budget");
            boolean request=entry.waiting.isEmpty();
            entry.waiting.put(owner,()->ready.accept(profile.withPlan(Optional.of(entry.complete),Optional.empty())));
            return request;
        }
        public void accept(Part part) {
            accept(part,Runnable::run,Runnable::run);
        }
        /** Assemble and decode off-thread; only publication may touch session state and waiters. */
        public void accept(Part part,java.util.concurrent.Executor decoder,java.util.concurrent.Executor publisher) {
            if(complete(part.reference)!=null)return;
            var entry=state(part.session,part.seed,part.reference);
            if(entry==null)return; // Unreserved transfers are retried through their next Profile reference.
            var previous=entry.parts[part.index];
            if(previous!=null) {
                if(!Arrays.equals(previous,part.bytes))throw new IllegalArgumentException("Conflicting planet plan part");
                return;
            }
            if(entry.complete!=null)return;
            entry.parts[part.index]=part.bytes;
            for(var bytes:entry.parts)if(bytes==null)return;
            var key=new Key(part.seed,part.reference);
            decoder.execute(()->{
                final PlanetPlan plan;
                try {
                    var bytes=new ByteArrayOutputStream(part.reference.bytes);
                    for(var data:entry.parts)bytes.writeBytes(data);
                    plan=PlanetPlan.decode(bytes.toByteArray());
                    if(!plan.identity().equals(part.reference.hash))throw new IllegalArgumentException("Planet plan transfer digest");
                } catch(RuntimeException failure) {
                    publisher.execute(()->{
                        if(session==part.session && incoming.get(key)==entry)throw failure;
                    });
                    return;
                }
                publisher.execute(()->{
                    if(session!=part.session || incoming.get(key)!=entry)return;
                    entry.complete=plan;synchronized(COMPLETE){COMPLETE.put(part.reference.hash,plan);}
                    var waiting=List.copyOf(entry.waiting.values());entry.waiting.clear();waiting.forEach(Runnable::run);
                });
            });
        }
    }
    private PlanetPlanSync() {}
}
