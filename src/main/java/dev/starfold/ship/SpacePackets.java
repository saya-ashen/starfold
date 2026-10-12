package dev.starfold.ship;

import dev.starfold.chart.GroundSpace;
import dev.starfold.flight.ArrivalState;
import dev.starfold.geometry.BentStripGeometry.Point;
import dev.starfold.sky.Space;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import org.joml.Quaterniond;

/** Passive region coordinates, never a flight session. Null means the observer has left. */
public final class SpacePackets {
    public record Frame(
            Space.Frame frame,
            long tick,
            boolean turning,
            ArrivalState.Phase phase,
            float blend,
            int notice)
            implements CustomPacketPayload {
        public Frame(Space.Frame frame, long tick) {
            this(frame, tick, false);
        }

        public Frame(Space.Frame frame, long tick, boolean turning) {
            this(
                    frame,
                    tick,
                    turning,
                    turning ? ArrivalState.Phase.ARRIVAL : ArrivalState.Phase.CRUISE,
                    turning ? 1 : 0,
                    0);
        }

        public Frame(Space.Frame frame, long tick, boolean turning, ArrivalState state) {
            this(frame, tick, turning, state.phase(), (float) state.blend(), state.seconds());
        }

        public Frame {
            if (tick < 0) throw new IllegalArgumentException("Space frame epoch");
            if (phase == null
                    || !Float.isFinite(blend)
                    || blend < 0
                    || blend > 1
                    || notice < 0
                    || notice > 4) throw new IllegalArgumentException("Arrival phase");
            if (frame != null)
                frame =
                        new Space.Frame(
                                frame.rotation(),
                                frame.shift(),
                                frame.planet(),
                                frame.offset(),
                                frame.drift(),
                                frame.epoch());
        }

        public static final Type<Frame> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_space_v3_frame"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Frame> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeBoolean(p.frame != null);
                            if (p.frame == null) return;
                            var q = p.frame.rotation();
                            var s = p.frame.shift();
                            b.writeDouble(q.x);
                            b.writeDouble(q.y);
                            b.writeDouble(q.z);
                            b.writeDouble(q.w);
                            b.writeDouble(s.x());
                            b.writeDouble(s.y());
                            b.writeDouble(s.z());
                            b.writeInt(p.frame.planet());
                            var o = p.frame.offset();
                            var w = p.frame.drift();
                            b.writeDouble(o.x());
                            b.writeDouble(o.y());
                            b.writeDouble(o.z());
                            b.writeDouble(w.x());
                            b.writeDouble(w.y());
                            b.writeDouble(w.z());
                            b.writeLong(p.frame.epoch());
                            b.writeLong(p.tick);
                            b.writeBoolean(p.turning);
                            b.writeEnum(p.phase);
                            b.writeFloat(p.blend);
                            b.writeVarInt(p.notice);
                        },
                        b ->
                                b.readBoolean()
                                        ? new Frame(
                                                new Space.Frame(
                                                        new Quaterniond(
                                                                b.readDouble(),
                                                                b.readDouble(),
                                                                b.readDouble(),
                                                                b.readDouble()),
                                                        new Point(
                                                                b.readDouble(),
                                                                b.readDouble(),
                                                                b.readDouble()),
                                                        b.readInt(),
                                                        new Point(
                                                                b.readDouble(),
                                                                b.readDouble(),
                                                                b.readDouble()),
                                                        new Point(
                                                                b.readDouble(),
                                                                b.readDouble(),
                                                                b.readDouble()),
                                                        b.readLong()),
                                                b.readLong(),
                                                b.readBoolean(),
                                                b.readEnum(ArrivalState.Phase.class),
                                                b.readFloat(),
                                                b.readVarInt())
                                        : new Frame(null, 0));

        @Override
        public Type<Frame> type() {
            return TYPE;
        }
    }

    /** A render-only epoch, independent of both the moving frame tick and flight ownership. Zero ends it. */
    public record View(long session, GroundSpace.Config mask, boolean shared)
            implements CustomPacketPayload {
        public View {
            if (mask == null) throw new IllegalArgumentException("Space view mask");
        }

        public static final Type<View> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_space_v1_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeLong(p.session);
                            b.writeInt(p.mask.core());
                            b.writeInt(p.mask.height());
                            b.writeBoolean(p.shared);
                        },
                        b ->
                                new View(
                                        b.readLong(),
                                        new GroundSpace.Config(b.readInt(), b.readInt()),
                                        b.readBoolean()));

        @Override
        public Type<View> type() {
            return TYPE;
        }
    }

    /** Ephemeral rates and one-shot mouse degrees; the server owns axes, pivot and clock. */
    public record Attitude(float pitch, float roll, float mouseYaw, float mousePitch)
            implements CustomPacketPayload {
        public Attitude(float pitch, float roll) {
            this(pitch, roll, 0, 0);
        }

        public static final Type<Attitude> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_space_v2_attitude"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Attitude> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeFloat(p.pitch);
                            b.writeFloat(p.roll);
                            b.writeFloat(p.mouseYaw);
                            b.writeFloat(p.mousePitch);
                        },
                        b ->
                                new Attitude(
                                        b.readFloat(),
                                        b.readFloat(),
                                        b.readFloat(),
                                        b.readFloat()));

        @Override
        public Type<Attitude> type() {
            return TYPE;
        }
    }

    /** A transient navigation choice, not permission to fly or a saved flight plan. -1 clears it. */
    public record Target(int planet) implements CustomPacketPayload {
        public static final Type<Target> TYPE =
                new Type<>(ResourceLocation.parse("starfold:native_space_v1_target"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Target> CODEC =
                StreamCodec.of((b, p) -> b.writeInt(p.planet), b -> new Target(b.readInt()));

        @Override
        public Type<Target> type() {
            return TYPE;
        }
    }

    private SpacePackets() {}
}
