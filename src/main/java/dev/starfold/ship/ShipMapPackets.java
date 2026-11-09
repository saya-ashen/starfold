package dev.starfold.ship;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.List;
import java.util.UUID;

/** Server-filtered location records; never sends storage pointers or owner identities. */
public final class ShipMapPackets {
    public record Request() implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.parse("starfold:ships_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.unit(new Request());
        public Type<Request> type() { return TYPE; }
    }
    public record Ships(List<ShipRecord> records) implements CustomPacketPayload {
        public static final Type<Ships> TYPE = new Type<>(ResourceLocation.parse("starfold:ships"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ships> CODEC = StreamCodec.of(
                (b, p) -> {
                    b.writeVarInt(p.records().size());
                    for (var r : p.records()) b.writeNbt(ShipCodec.write(new ShipRecord(r.id(), r.uuid(), r.dimension(), r.name(), r.state(), java.util.Optional.empty())));
                }, b -> {
                    int n = b.readVarInt();
                    if (n < 0 || n > 4096) throw new IllegalArgumentException("Ship map count");
                    var records = new java.util.ArrayList<ShipRecord>();
                    var ids = new java.util.HashSet<UUID>();
                    for (int i = 0; i < n; i++) {
                        var tag = b.readNbt();
                        if (tag == null) throw new IllegalArgumentException("Missing ship");
                        var r = ShipCodec.read(tag);
                        if (!ids.add(r.uuid()) || r.name().map(s -> s.length() > 256).orElse(false)) throw new IllegalArgumentException("Ship map record");
                        records.add(r);
                    }
                    return new Ships(List.copyOf(records));
                });
        public Type<Ships> type() { return TYPE; }
    }
    /** A body index, or a private ship UUID. No client-supplied destination coordinates. */
    public record Target(int body, UUID ship) implements CustomPacketPayload {
        public Target {
            if (ship == null ? body < 0 : body != -1) throw new IllegalArgumentException("Map target identity");
        }
        public static final Type<Target> TYPE = new Type<>(ResourceLocation.parse("starfold:map_target"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Target> CODEC = StreamCodec.of(
                (b, p) -> { b.writeInt(p.body()); b.writeBoolean(p.ship() != null); if (p.ship() != null) b.writeUUID(p.ship()); },
                b -> new Target(b.readInt(), b.readBoolean() ? b.readUUID() : null));
        public Type<Target> type() { return TYPE; }
    }
    private ShipMapPackets() {}
}
