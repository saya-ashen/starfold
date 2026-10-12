package dev.starfold.waypoint;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Common wire types only; registration and sending belong to the server adapter. */
public final class WaypointPackets {
    /** A null marker clears the target for this flight session. */
    public record Target(long session, Waypoint marker) implements CustomPacketPayload {
        public static final Type<Target> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_waypoint_v1_target"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Target> CODEC =
                StreamCodec.of(WaypointPackets::write, WaypointPackets::read);

        public Target {
            if (session == 0) throw new IllegalArgumentException("Waypoint session");
        }

        @Override
        public Type<Target> type() {
            return TYPE;
        }
    }

    private static void write(RegistryFriendlyByteBuf buffer, Target target) {
        buffer.writeLong(target.session());
        buffer.writeBoolean(target.marker() != null);
        if (target.marker() != null) buffer.writeNbt(WaypointCodec.encode(target.marker()));
    }

    private static Target read(RegistryFriendlyByteBuf buffer) {
        long session = buffer.readLong();
        if (!buffer.readBoolean()) return new Target(session, null);
        var tag = buffer.readNbt();
        if (tag == null) throw new IllegalArgumentException("Waypoint missing");
        return new Target(session, WaypointCodec.decode(tag));
    }

    private WaypointPackets() {}
}
