package dev.starfold.render;

import dev.starfold.planet.GroundTopology;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.*;

/** Display subscriptions only: no writes, block actions, inventory or BlockEntity save NBT. */
public final class GroundViewPackets {
    public static final long CHUNKS = 6L * 128 * 128;

    /** The client has bound this session's far-cover cache and wants profile/coverage (re)sent. */
    public record Hello(long session) implements CustomPacketPayload {
        public Hello {
            if (session == 0) throw new IllegalArgumentException("Native view hello");
        }

        public static final Type<Hello> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_ground_v2_view_hello"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC =
                StreamCodec.of((b, p) -> b.writeLong(p.session), b -> new Hello(b.readLong()));

        @Override
        public Type<Hello> type() {
            return TYPE;
        }
    }

    public record Known(long session, long[] keys, long[] revisions) implements CustomPacketPayload {
        public static final Type<Known> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_ground_view_known"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Known> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeLong(p.session);
                    b.writeVarInt(p.keys.length);
                    for (int i = 0; i < p.keys.length; i++) {
                        b.writeVarLong(p.keys[i]);
                        b.writeVarLong(p.revisions[i]);
                    }
                }, b -> {
                    long session = b.readLong();
                    int count = b.readVarInt();
                    if (session == 0 || count < 0 || count > CHUNKS)
                        throw new IllegalArgumentException("Known surface count/session");
                    long[] keys = new long[count], revisions = new long[count];
                    var seen = new BitSet();
                    for (int i = 0; i < count; i++) {
                        keys[i] = b.readVarLong();
                        revisions[i] = b.readVarLong();
                        if (keys[i] < 0 || keys[i] >= CHUNKS || revisions[i] < 0
                                || seen.get((int) keys[i]))
                            throw new IllegalArgumentException("Known surface key/revision");
                        seen.set((int) keys[i]);
                    }
                    return new Known(session, keys, revisions);
                });

        @Override
        public Type<Known> type() { return TYPE; }
    }

    public record Delta(long session, long epoch, net.minecraft.core.BlockPos pos, int state)
            implements CustomPacketPayload {
        public Delta {
            if (session == 0
                    || epoch < 0
                    || pos == null
                    || !GroundTopology.address(pos).owner().equals(pos)
                    || net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(state)
                            == null) throw new IllegalArgumentException("Native display delta");
            pos = pos.immutable();
        }

        public static final Type<Delta> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_ground_v2_delta"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Delta> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeLong(p.session);
                            b.writeVarLong(p.epoch);
                            b.writeBlockPos(p.pos);
                            b.writeVarInt(p.state);
                        },
                        b ->
                                new Delta(
                                        b.readLong(),
                                        b.readVarLong(),
                                        b.readBlockPos(),
                                        b.readVarInt()));

        @Override
        public Type<Delta> type() {
            return TYPE;
        }
    }

    public record Coverage(long session, long identity, long revision, boolean flat, long[] changed)
            implements CustomPacketPayload {
        public Coverage {
            if (session == 0
                    || revision < 0
                    || changed == null
                    || changed.length > 1536
                    || BitSet.valueOf(changed).length() > CHUNKS)
                throw new IllegalArgumentException("Native coverage");
            changed = changed.clone();
        }

        @Override
        public long[] changed() {
            return changed.clone();
        }

        public static final Type<Coverage> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_ground_v2_coverage"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Coverage> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeLong(p.session);
                            b.writeLong(p.identity);
                            b.writeVarLong(p.revision);
                            b.writeBoolean(p.flat);
                            b.writeLongArray(p.changed);
                        },
                        b ->
                                new Coverage(
                                        b.readLong(),
                                        b.readLong(),
                                        b.readVarLong(),
                                        b.readBoolean(),
                                        b.readLongArray(new long[0], 1536)));

        @Override
        public Type<Coverage> type() {
            return TYPE;
        }
    }

    public record Surface(long session, long key, int algorithmVersion,long revision,GroundSurface image)
            implements CustomPacketPayload {
        public Surface(long session,long key,GroundSurface image) {
            this(session,key,dev.starfold.worldgen.PlanetGeneration.SURFACE_VERSION,image.revision(),image);
        }
        public static Surface removal(long session,long key,long revision) {
            return new Surface(session,key,dev.starfold.worldgen.PlanetGeneration.SURFACE_VERSION,revision,null);
        }
        public boolean compatible() { return algorithmVersion==dev.starfold.worldgen.PlanetGeneration.SURFACE_VERSION; }
        public Surface {
            if (session == 0 || key < 0 || key >= CHUNKS || algorithmVersion<1 || revision<0
                    || image!=null && image.revision()!=revision)
                throw new IllegalArgumentException("Native surface packet");
        }

        public static final Type<Surface> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_ground_v7_surface"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Surface> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeLong(p.session);
                            b.writeVarLong(p.key);
                            b.writeVarInt(p.algorithmVersion);
                            b.writeVarInt(GroundSurfaceIndex.SURFACE_FORM);
                            b.writeVarLong(p.revision);
                            b.writeBoolean(p.image!=null);
                            if(p.image!=null) {
                                b.writeByteArray(GroundSurfaceCodec.pack(p.image));
                                GroundVoxelCodec.write(b, p.image.voxels());
                            }
                        },
                        b -> {
                            long session = b.readLong(),key=b.readVarLong();
                            int algorithm=b.readVarInt(),form=b.readVarInt();
                            if(form!=GroundSurfaceIndex.SURFACE_FORM) throw new IllegalArgumentException("Surface form");
                            long revision=b.readVarLong();
                            if(!b.readBoolean()) return new Surface(session,key,algorithm,revision,null);
                            var columns =
                                    GroundSurfaceCodec.unpack(
                                            revision,
                                            b.readByteArray(GroundSurfaceCodec.MAX_BYTES));
                            var voxels = GroundVoxelCodec.read(b);
                            return new Surface(
                                    session,
                                    key,algorithm,revision,
                                    voxels == null
                                            ? columns
                                            : new GroundSurface(
                                                    revision,
                                                    columns.heights(),
                                                    columns.states(),
                                                    voxels));
                        });

        @Override
        public Type<Surface> type() {
            return TYPE;
        }
    }

    private GroundViewPackets() {}
}
