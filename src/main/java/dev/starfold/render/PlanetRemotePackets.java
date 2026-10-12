package dev.starfold.render;

import dev.starfold.planet.PlanetPackets;
import dev.starfold.planet.PlanetShape;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.BitSet;

/** Read-only distant planet identity and authoritative derived surface summaries. */
public final class PlanetRemotePackets {
    private static void bits(long[] a) {
        if (a == null || a.length > 1536 || BitSet.valueOf(a).length() > GroundViewPackets.CHUNKS)
            throw new IllegalArgumentException("Remote coverage");
    }

    public record Summary(
            int planet,
            PlanetPackets.Profile profile,
            long revision,
            long[] changed,
            long[] unknown)
            implements CustomPacketPayload {
        public Summary {
            PlanetShape.valid(planet);
            if (profile == null || revision < 0)
                throw new IllegalArgumentException("Remote profile");
            bits(changed);
            bits(unknown);
            var extra = BitSet.valueOf(unknown);
            extra.andNot(BitSet.valueOf(changed));
            if (!extra.isEmpty()) throw new IllegalArgumentException("Remote unknown owner");
            changed = changed.clone();
            unknown = unknown.clone();
        }

        @Override
        public long[] changed() {
            return changed.clone();
        }

        @Override
        public long[] unknown() {
            return unknown.clone();
        }

        public static final Type<Summary> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_remote_v2_summary"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Summary> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeByte(p.planet);
                            PlanetPackets.Profile.CODEC.encode(b, p.profile);
                            b.writeVarLong(p.revision);
                            b.writeLongArray(p.changed);
                            b.writeLongArray(p.unknown);
                        },
                        b ->
                                new Summary(
                                        b.readUnsignedByte(),
                                        PlanetPackets.Profile.CODEC.decode(b),
                                        b.readVarLong(),
                                        b.readLongArray(new long[0], 1536),
                                        b.readLongArray(new long[0], 1536)));

        @Override
        public Type<Summary> type() {
            return TYPE;
        }
    }

    public record Surface(int planet, GroundViewPackets.Surface surface)
            implements CustomPacketPayload {
        public Surface {
            PlanetShape.valid(planet);
            if (surface == null) throw new IllegalArgumentException("Remote surface");
        }

        public static final Type<Surface> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_remote_v1_surface"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Surface> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeByte(p.planet);
                            GroundViewPackets.Surface.CODEC.encode(b, p.surface);
                        },
                        b ->
                                new Surface(
                                        b.readUnsignedByte(),
                                        GroundViewPackets.Surface.CODEC.decode(b)));

        @Override
        public Type<Surface> type() {
            return TYPE;
        }
    }

    private PlanetRemotePackets() {}
}
