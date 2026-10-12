package dev.starfold.flight;

import dev.starfold.chart.GroundAir;
import dev.starfold.chart.GroundSpace;
import dev.starfold.geometry.BentStripGeometry;
import dev.starfold.geometry.CubeSurfaceTopology;
import dev.starfold.geometry.PlanetMotion;
import dev.starfold.ground.GroundNavigationSave;
import dev.starfold.ground.GroundTopology;
import dev.starfold.ground.ObserverFrame;
import dev.starfold.sky.GroundEphemeris;
import dev.starfold.sky.GroundEphemerisSave;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;

/** Only vessel/input metadata; native block placement, inventory and menus stay vanilla. */
public final class GroundFlightPackets {
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String n) {
        return new CustomPacketPayload.Type<>(
                ResourceLocation.parse("starfold:native_ground_v4_" + n));
    }

    private static void session(long id) {
        if (id == 0) throw new IllegalArgumentException("Native session");
    }

    public record Setup(
            long session,
            GroundEphemeris.Model model,
            GroundSpace.Config mask,
            GroundDynamics.State state,
            boolean controlled,
            GroundAir.Config air,
            boolean shared)
            implements CustomPacketPayload {
        public Setup(
                long session,
                GroundEphemeris.Model model,
                GroundSpace.Config mask,
                GroundDynamics.State state,
                boolean controlled,
                GroundAir.Config air) {
            this(session, model, mask, state, controlled, air, false);
        }

        public Setup(
                long session,
                GroundEphemeris.Model model,
                GroundSpace.Config mask,
                GroundDynamics.State state,
                boolean controlled) {
            this(session, model, mask, state, controlled, GroundAir.DEFAULT);
        }

        public Setup {
            if (shared && (model == null || model.count() < 2))
                throw new IllegalArgumentException("Shared layout needs its planets");
            if (air == null) throw new IllegalArgumentException("Native air setup");
            GroundFlightPackets.session(session);
            if (model == null
                    || !model.sun().enabled()
                    || mask == null
                    || state == null
                    || state.planet() >= model.count()
                    || state.guidance().target() >= model.count()
                    || state.space() && !controlled
                    || controlled
                            && !state.space()
                            && state.local().point().y() <= GroundDynamics.HANDOFF)
                throw new IllegalArgumentException("Native setup");
        }

        public static final Type<Setup> TYPE = GroundFlightPackets.type("setup");
        public static final StreamCodec<RegistryFriendlyByteBuf, Setup> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeLong(p.session);
                            b.writeNbt(GroundEphemerisSave.modelTag(p.model));
                            b.writeVarInt(p.mask.core());
                            b.writeVarInt(p.mask.height());
                            b.writeNbt(GroundNavigationSave.encode(p.state));
                            b.writeBoolean(p.controlled);
                            b.writeVarInt(p.air.start());
                            b.writeVarInt(p.air.end());
                            b.writeBoolean(p.shared);
                        },
                        b -> {
                            long id = b.readLong();
                            var model = b.readNbt();
                            if (model == null || !model.contains("sun", 10))
                                throw new IllegalArgumentException("Native Sun missing");
                            var mask = new GroundSpace.Config(b.readVarInt(), b.readVarInt());
                            var state = b.readNbt();
                            if (state == null)
                                throw new IllegalArgumentException("Native flight missing");
                            return new Setup(
                                    id,
                                    GroundEphemerisSave.model(model),
                                    mask,
                                    GroundNavigationSave.decode(state),
                                    b.readBoolean(),
                                    new GroundAir.Config(b.readVarInt(), b.readVarInt()),
                                    b.readBoolean());
                        });

        @Override
        public Type<Setup> type() {
            return TYPE;
        }
    }

    public record State(long session, long ack, GroundDynamics.State state, boolean controlled)
            implements CustomPacketPayload {
        public State {
            GroundFlightPackets.session(session);
            if (ack < 0
                    || state == null
                    || state.space() && !controlled
                    || controlled
                            && !state.space()
                            && state.local().point().y() <= GroundDynamics.HANDOFF)
                throw new IllegalArgumentException("Native state");
        }

        public static final Type<State> TYPE = GroundFlightPackets.type("state");
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeLong(p.session);
                            b.writeVarLong(p.ack);
                            b.writeNbt(GroundNavigationSave.encode(p.state));
                            b.writeBoolean(p.controlled);
                        },
                        b -> {
                            long id = b.readLong(), ack = b.readVarLong();
                            var t = b.readNbt();
                            if (t == null)
                                throw new IllegalArgumentException("Native state missing");
                            return new State(
                                    id, ack, GroundNavigationSave.decode(t), b.readBoolean());
                        });

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }

    public record Input(long session, PlanetMotion.Input motion, boolean toggle)
            implements CustomPacketPayload {
        public Input {
            GroundFlightPackets.session(session);
            if (motion == null || motion.sequence() <= 0)
                throw new IllegalArgumentException("Native input");
        }

        public static final Type<Input> TYPE = GroundFlightPackets.type("input");
        public static final StreamCodec<RegistryFriendlyByteBuf, Input> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeLong(p.session);
                            b.writeVarLong(p.motion.sequence());
                            b.writeDouble(p.motion.qx());
                            b.writeDouble(p.motion.qy());
                            b.writeDouble(p.motion.qz());
                            b.writeDouble(p.motion.qw());
                            b.writeFloat(p.motion.forward());
                            b.writeFloat(p.motion.left());
                            b.writeFloat(p.motion.rise());
                            b.writeBoolean(p.motion.direct());
                            b.writeBoolean(p.motion.fast());
                            b.writeBoolean(p.toggle);
                        },
                        b ->
                                new Input(
                                        b.readLong(),
                                        new PlanetMotion.Input(
                                                b.readVarLong(),
                                                b.readDouble(),
                                                b.readDouble(),
                                                b.readDouble(),
                                                b.readDouble(),
                                                b.readFloat(),
                                                b.readFloat(),
                                                b.readFloat(),
                                                b.readBoolean(),
                                                b.readBoolean()),
                                        b.readBoolean()));

        @Override
        public Type<Input> type() {
            return TYPE;
        }
    }

    public record Seam(long session, GroundTopology.Pose pose, int turns)
            implements CustomPacketPayload {
        public Seam {
            GroundFlightPackets.session(session);
            if (pose == null || turns < 0 || turns > 3)
                throw new IllegalArgumentException("Native seam receipt");
        }

        public static final Type<Seam> TYPE = GroundFlightPackets.type("seam");
        public static final StreamCodec<RegistryFriendlyByteBuf, Seam> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeLong(p.session);
                            b.writeByte(p.pose.face().ordinal());
                            b.writeDouble(p.pose.point().x());
                            b.writeDouble(p.pose.point().y());
                            b.writeDouble(p.pose.point().z());
                            b.writeByte(p.turns);
                        },
                        b -> {
                            long id = b.readLong();
                            int face = b.readUnsignedByte();
                            if (face >= 6) throw new IllegalArgumentException("Native seam face");
                            return new Seam(
                                    id,
                                    new GroundTopology.Pose(
                                            CubeSurfaceTopology.Face.values()[face],
                                            new BentStripGeometry.Point(
                                                    b.readDouble(),
                                                    b.readDouble(),
                                                    b.readDouble())),
                                    b.readUnsignedByte());
                        });

        @Override
        public Type<Seam> type() {
            return TYPE;
        }
    }

    /** How the observer's continuous client coordinates label the root storage chart. */
    public record Frame(ObserverFrame frame) implements CustomPacketPayload {
        public Frame {
            if (frame == null) throw new IllegalArgumentException("Native frame");
        }

        public static final Type<Frame> TYPE = GroundFlightPackets.type("frame");
        public static final StreamCodec<RegistryFriendlyByteBuf, Frame> CODEC =
                StreamCodec.of(
                        (b, p) -> {
                            b.writeByte(p.frame.planet());
                            b.writeByte(p.frame.root());
                            b.writeByte(p.frame.turns());
                            b.writeInt(p.frame.tx());
                            b.writeInt(p.frame.tz());
                        },
                        b ->
                                new Frame(
                                        new ObserverFrame(
                                                b.readUnsignedByte(),
                                                b.readUnsignedByte(),
                                                b.readUnsignedByte(),
                                                b.readInt(),
                                                b.readInt())));

        @Override
        public Type<Frame> type() {
            return TYPE;
        }
    }

    public record Sync(long session) implements CustomPacketPayload {
        public Sync {
            GroundFlightPackets.session(session);
        }

        public static final Type<Sync> TYPE = GroundFlightPackets.type("sync");
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC =
                StreamCodec.of((b, p) -> b.writeLong(p.session), b -> new Sync(b.readLong()));

        @Override
        public Type<Sync> type() {
            return TYPE;
        }
    }

    public static final class Queue {
        private final ArrayDeque<Input> queue = new ArrayDeque<>();
        private long last, ack, arrival = -1, count;

        public long ack() {
            return ack;
        }

        public boolean offer(Input input, long gameTick) {
            long n = input.motion.sequence();
            if (n != last + 1 || queue.size() >= 24 || gameTick < arrival) return false;
            if (arrival != gameTick) {
                arrival = gameTick;
                count = 0;
            }
            if (count >= 4) return false;
            queue.addLast(input);
            last = n;
            count++;
            return true;
        }

        public Input poll() {
            var input = queue.pollFirst();
            if (input != null) ack = input.motion.sequence();
            return input;
        }
    }

    private GroundFlightPackets() {}
}
