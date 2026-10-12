package dev.starfold.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public final class HelmPackets {
    public record Input(UUID body, int bits, boolean leave) implements CustomPacketPayload {
        public static final Type<Input> TYPE =
                new Type<>(ResourceLocation.parse("starfold:helm_input"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeUUID(p.body);
                            b.writeByte(p.bits);
                            b.writeBoolean(p.leave);
                        },
                        b -> new Input(b.readUUID(), b.readUnsignedByte(), b.readBoolean()));

        @Override
        public Type<Input> type() {
            return TYPE;
        }
    }

    /** Repeated with the command lease; pilot is recipient-specific, active describes the hull session. */
    public record State(
            UUID body,
            BlockPos pos,
            Direction facing,
            boolean active,
            boolean pilot,
            int bits,
            boolean free,
            double cx,
            double cy,
            double cz,
            HullLanding.Site landing,
            double cruiseCap,
            double boostCap,
            boolean boosted)
            implements CustomPacketPayload {
        public State(
                UUID body,
                BlockPos pos,
                Direction facing,
                boolean active,
                boolean pilot,
                int bits,
                boolean free,
                double cx,
                double cy,
                double cz) {
            this(
                    body,
                    pos,
                    facing,
                    active,
                    pilot,
                    bits,
                    free,
                    cx,
                    cy,
                    cz,
                    HullLanding.Site.UNKNOWN,
                    ShipHost.get().minimumSpeed(),
                    ShipHost.get().minimumSpeed(),
                    false);
        }

        public State(
                UUID body,
                BlockPos pos,
                Direction facing,
                boolean active,
                boolean pilot,
                int bits,
                boolean free,
                double cx,
                double cy,
                double cz,
                HullLanding.Site landing) {
            this(
                    body,
                    pos,
                    facing,
                    active,
                    pilot,
                    bits,
                    free,
                    cx,
                    cy,
                    cz,
                    landing,
                    ShipHost.get().minimumSpeed(),
                    ShipHost.get().minimumSpeed(),
                    false);
        }

        public static final Type<State> TYPE =
                new Type<>(ResourceLocation.parse("starfold:helm_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeUUID(p.body);
                            b.writeBlockPos(p.pos);
                            b.writeEnum(p.facing);
                            b.writeBoolean(p.active);
                            b.writeBoolean(p.pilot);
                            b.writeByte(p.bits);
                            b.writeBoolean(p.free);
                            b.writeDouble(p.cx);
                            b.writeDouble(p.cy);
                            b.writeDouble(p.cz);
                            b.writeEnum(p.landing.kind());
                            b.writeBoolean(p.landing.deployable());
                            b.writeEnum(p.landing.aid().status());
                            b.writeDouble(p.landing.aid().right());
                            b.writeDouble(p.landing.aid().forward());
                            b.writeDouble(p.cruiseCap);
                            b.writeDouble(p.boostCap);
                            b.writeBoolean(p.boosted);
                        },
                        b ->
                                new State(
                                        b.readUUID(),
                                        b.readBlockPos(),
                                        b.readEnum(Direction.class),
                                        b.readBoolean(),
                                        b.readBoolean(),
                                        b.readUnsignedByte(),
                                        b.readBoolean(),
                                        b.readDouble(),
                                        b.readDouble(),
                                        b.readDouble(),
                                        new HullLanding.Site(
                                                b.readEnum(HullLanding.Kind.class),
                                                b.readBoolean(),
                                                new HullLanding.Aid(
                                                        b.readEnum(HullLanding.AidStatus.class),
                                                        b.readDouble(),
                                                        b.readDouble())),
                                        b.readDouble(),
                                        b.readDouble(),
                                        b.readBoolean()));

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }

    /** Actual server throttle, also sent for unpiloted powered-gyro ships. */
    public record Lift(
            UUID body, boolean active, double up, double down, boolean insufficient, double limit)
            implements CustomPacketPayload {
        public static final Type<Lift> TYPE =
                new Type<>(ResourceLocation.parse("starfold:lift_state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Lift> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeUUID(p.body);
                            b.writeBoolean(p.active);
                            b.writeDouble(p.up);
                            b.writeDouble(p.down);
                            b.writeBoolean(p.insufficient);
                            b.writeDouble(p.limit);
                        },
                        b ->
                                new Lift(
                                        b.readUUID(),
                                        b.readBoolean(),
                                        b.readDouble(),
                                        b.readDouble(),
                                        b.readBoolean(),
                                        b.readDouble()));

        @Override
        public Type<Lift> type() {
            return TYPE;
        }
    }

    private HelmPackets() {}
}
