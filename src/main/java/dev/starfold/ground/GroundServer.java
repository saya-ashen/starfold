package dev.starfold.ground;

import dev.starfold.chart.GroundEphemeris;
import dev.starfold.chart.PlanetShape;
import dev.starfold.planet.PlanetIdentity;
import dev.starfold.planet.PlanetSystem;
import dev.starfold.sky.GroundClock;
import dev.starfold.universe.SystemChunkGenerator;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Real-chunk backend metadata and per-player sky publication state. */
public final class GroundServer {
    /** A player who has left takes their per-player sending state with them. */
    public static void forget(java.util.UUID player) {
        arrived.remove(player);
        skies.remove(player);
        wholes.remove(player);
        arriving.remove(player);
    }

    public static void clear() {
        arrived.clear();
        skies.clear();
        wholes.clear();
        arriving.clear();
    }

    // By name, not by entity: someone reborn is a new entity that still equals the one that died.
    private static final java.util.Set<java.util.UUID> arrived = new java.util.HashSet<>();

    /** Forget only the completed arrival attempt after a player dies. */
    public static void arrivalForgotten(java.util.UUID player) {
        arrived.remove(player);
    }

    /** Mark an arrival attempt; returns false if this appearance was already tried. */
    public static boolean arrivalAttempted(java.util.UUID player) {
        return arrived.add(player);
    }

    /** The running table takes the size and depth of every planet the system now has. */
    public static void system(SystemChunkGenerator system) {
        int[] sizes = new int[system.count()], depths = new int[sizes.length];
        for (int id = 0; id < sizes.length; id++) {
            sizes[id] = system.planet(id).terrain().shape().size();
            depths[id] = system.planet(id).generation().depth();
        }
        PlanetShape.system(sizes, depths);
        PlanetSystem.names(system.names());
        PlanetSystem.identities(
                java.util.stream.IntStream.range(0, system.count())
                        .mapToObj(id -> PlanetIdentity.of(system.planet(id)))
                        .toList());
    }

    public static void shapes(ServerPlayer player) {
        if (!player.connection.hasChannel(GroundPackets.Shapes.TYPE)) return;
        int[] sizes = new int[PlanetShape.count()], depths = new int[sizes.length];
        for (int id = 0; id < sizes.length; id++) {
            sizes[id] = PlanetShape.of(id).size();
            depths[id] = PlanetShape.depth(id);
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player,
                new GroundPackets.Shapes(
                        sizes,
                        depths,
                        PlanetSystem.names(),
                        java.util.stream.IntStream.range(0, sizes.length)
                                .mapToObj(PlanetSystem::identity)
                                .toList()));
    }

    /** The model each player was last sent, and when: it is sent again when it is another model, and every WHOLE ticks
     *  in case the first was missed while the client was between worlds. The table of shapes goes with it. */
    private static final java.util.Map<java.util.UUID, GroundEphemeris.Model> skies =
            new java.util.HashMap<>();

    private static final java.util.Map<java.util.UUID, Long> wholes = new java.util.HashMap<>();
    public static final int WHOLE = 1200;

    /** Entry/login metadata for a passive observer, with the normal eager whole-model retries. */
    public static void regionSky(ServerPlayer player) {
        whole(player);
        send(player, GroundClock.get(player.serverLevel()));
    }

    public static void send(ServerPlayer player, GroundClock clock) {
        if (!player.connection.hasChannel(GroundPackets.Sky.TYPE)) return;
        var model = clock.clock.model;
        long now = player.level().getGameTime();
        int eager = arriving.getOrDefault(player.getUUID(), 0);
        if (eager > 0) arriving.put(player.getUUID(), eager - 1);
        boolean whole =
                eager > 0
                        || skies.get(player.getUUID()) != model
                        || now - wholes.getOrDefault(player.getUUID(), -1000L) >= WHOLE;
        if (whole) {
            shapes(player);
            skies.put(player.getUUID(), model);
            wholes.put(player.getUUID(), now);
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, new GroundPackets.Sky(clock.clock.tick(), whole ? model : null));
    }

    /** The next sky a player is sent is whole: they have just come, or come back. */
    public static void whole(ServerPlayer player) {
        skies.remove(player.getUUID());
        wholes.remove(player.getUUID());
        arriving.put(player.getUUID(), ARRIVING);
    }

    /** A client between worlds drops what it is sent: for its first seconds every sky is whole. */
    private static final java.util.Map<java.util.UUID, Integer> arriving =
            new java.util.HashMap<>();

    public static final int ARRIVING = 5;

    public static int regionMessage(ServerPlayer player) {
        player.sendSystemMessage(Component.translatable("message.starfold.space_region"));
        return 0;
    }

    private GroundServer() {}
}
