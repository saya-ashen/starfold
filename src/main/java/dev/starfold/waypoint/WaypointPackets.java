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

    public enum Action {
        LIST("list"), ADD("add"), REMOVE("remove"), TARGET("target"), UNKNOWN("");

        public final String wireName;

        Action(String wireName) { this.wireName = wireName; }

        static Action read(String name) {
            return switch (name) {
                case "list" -> LIST;
                case "add" -> ADD;
                case "remove" -> REMOVE;
                case "target" -> TARGET;
                default -> UNKNOWN;
            };
        }
    }

    /** Planet-map edit intent; server validates against its own catalog and saved book. */
    public record Edit(Action action, String name, int body, int face, double u, double v) implements CustomPacketPayload {

        public boolean validCoordinates(int size) {
            return face >= 0 && face < 6 && Double.isFinite(u) && Double.isFinite(v)
                    && u >= 0 && v >= 0 && u < size && v < size;
        }

        public static final Type<Edit> TYPE = new Type<>(ResourceLocation.parse("starfold:waypoint_edit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Edit> CODEC = StreamCodec.of(
                (b, p) -> { b.writeUtf(p.action().wireName, 16); b.writeUtf(p.name(), 32); b.writeInt(p.body()); b.writeInt(p.face()); b.writeDouble(p.u()); b.writeDouble(p.v()); },
                b -> new Edit(Action.read(b.readUtf(16)), b.readUtf(32), b.readInt(), b.readInt(), b.readDouble(), b.readDouble()));
        public Type<Edit> type() { return TYPE; }
    }
    public record Book(java.util.List<Waypoint> markers) implements CustomPacketPayload {
        public static final Type<Book> TYPE = new Type<>(ResourceLocation.parse("starfold:waypoint_book"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Book> CODEC = StreamCodec.of(
                (b, p) -> { b.writeVarInt(p.markers().size()); for (var m : p.markers()) b.writeNbt(WaypointCodec.encode(m)); },
                b -> {
                    int n = b.readVarInt();
                    if (n < 0 || n > Waypoint.MAX_MARKERS + dev.starfold.chart.PlanetShape.MAX_PLANETS)
                        throw new IllegalArgumentException("Waypoint book size");
                    var markers = new java.util.ArrayList<Waypoint>();
                    for (int i = 0; i < n; i++) { var tag = b.readNbt(); if (tag == null) throw new IllegalArgumentException("Missing waypoint"); markers.add(WaypointCodec.decode(tag)); }
                    return new Book(java.util.List.copyOf(markers));
                });
        public Type<Book> type() { return TYPE; }
    }

    private WaypointPackets() {}
}
