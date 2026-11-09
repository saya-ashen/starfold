package dev.starfold.ship;

import dev.starfold.network.StarfoldNetwork;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.List;
import java.util.UUID;

/** Only the server's private snapshot, discarded on disconnect or dimension change. */
public final class ShipMapClient {
    private static List<ShipRecord> ships = List.of();
    public static List<ShipRecord> ships() { return ships; }
    public static ShipRecord find(UUID id) { return ships.stream().filter(r -> r.uuid().equals(id)).findFirst().orElse(null); }
    public static void refresh() { PacketDistributor.sendToServer(new ShipMapPackets.Request()); }
    public static void initialize() {
        StarfoldNetwork.clientReceiver(ShipMapPackets.Ships.TYPE, (p, c) -> ships = p.records());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) -> ships = List.of());
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.Clone e) -> { ships = List.of(); refresh(); });
    }
    private ShipMapClient() {}
}
